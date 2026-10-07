import { el, clear, fmt, when, epochDay, gainClass, accountName, uuid, restoreSessionCredentials, rememberSessionCredentials, clearSessionCredentials, api, $, state, hooks, t } from "./core.js";
import { preload, navigate, renderNav, scheduleRefresh, closeDrawer, notify } from "./pages.js";
async function pair(kind, value) {
  const status = $("#pair-status");
  const button = $("#pair-submit");
  status.textContent = ""; button.disabled = true;
  try {
    const data = await api(`/api/v1/pair/${kind}`, { method: "POST", body: JSON.stringify({ [kind === "qr" ? "token" : "code"]: value }) });
    rememberSessionCredentials(data.sessionToken, data.csrfToken);
    state.allowReconnect = true;
    delete document.body.dataset.ended;
    history.replaceState(null, "", location.pathname);
    await connect();
  } catch (error) {
    if (String(error.message).startsWith("WS_")) {
      $("#pair-form").hidden = true; $("#pair-copy").textContent = t("wsFailed");
    }
    status.textContent = error.code === "PAIR_CODE_ROTATED" ? t("pairWrong") : `${t("pairFailed")}: ${error.code || error.message || "UNKNOWN"}`;
  } finally { button.disabled = false; }
}

async function connect() {
  const deadline = Date.now() + 40000;
  while (true) {
    try {
      await openSocket();
      break;
    } catch (error) {
      if (!String(error.message).startsWith("WS_") || Date.now() >= deadline) throw error;
      await delay(1000);
    }
  }
  const session = await api("/api/v1/session");
  applySession(session);
  showWorkspace();
  startHeartbeat();
  await preload();
  await navigate("accounts");
  if (state.socket?.readyState !== WebSocket.OPEN) reconnect();
}

function openSocket() {
  if (state.connecting) return state.connecting;
  const operation = new Promise((resolve, reject) => {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const ticket = state.sessionToken ? `?ticket=${encodeURIComponent(state.sessionToken)}` : "";
    const socket = new WebSocket(`${protocol}//${location.host}/ws/v1/session${ticket}`);
    const timer = setTimeout(() => { socket.close(); reject(new Error("WS_TIMEOUT")); }, 15000);
    let ready = false;
    socket.addEventListener("error", () => {
      if (!ready) { clearTimeout(timer); reject(new Error("WS_FAILED")); }
    }, { once: true });
    socket.addEventListener("message", event => {
      let msg; try { msg = JSON.parse(event.data); } catch (_) { return; }
      if (msg.type === "idle" || msg.type === "ready") state.idleDeadline = performance.now() + (msg.idleRemainingMs ?? 300000);
      if (msg.type === "ready" && !ready) {
        ready = true; clearTimeout(timer);
        const previous = state.socket;
        state.socket = socket;
        if (previous && previous !== socket && previous.readyState < WebSocket.CLOSING) previous.close(1000, "replaced");
        socket.send('{"type":"heartbeat"}'); resolve(); return;
      }
      if (msg.type === "dataChanged") scheduleRefresh();
      if (msg.type === "serverClosing") expireSession(msg.reason);
    });
    socket.addEventListener("close", () => {
      clearTimeout(timer);
      if (!ready) { reject(new Error("WS_CLOSED")); return; }
      if (state.socket === socket) {
        state.socket = null;
        if (state.session && state.allowReconnect && !document.body.dataset.ended) reconnect();
      }
    });
  });
  state.connecting = operation;
  return operation.finally(() => { if (state.connecting === operation) state.connecting = null; });
}

function applySession(session) {
  state.session = session;
  state.locale = session.language === "ENGLISH" ? "en" : "zh-CN";
  document.documentElement.lang = state.locale;
  applyGainPalette(session.gainLossColors);
}

function startHeartbeat() {
  if (state.heartbeat) clearInterval(state.heartbeat);
  state.heartbeat = setInterval(() => {
    if (state.socket?.readyState === WebSocket.OPEN) state.socket.send('{"type":"heartbeat"}');
  }, 5000);
}

function reconnect() {
  if (state.reconnecting || !state.allowReconnect || document.body.dataset.ended) return state.reconnecting;
  if (!$("#workspace").hidden) {
    $("#connected-label").textContent = t("reconnecting");
    $("#connection-banner").textContent = t("reconnecting"); $("#connection-banner").hidden = false;
  }
  const operation = (async () => {
    const deadline = Date.now() + 40000;
    while (Date.now() < deadline && !document.body.dataset.ended) {
      try {
        await openSocket();
        applySession(await api("/api/v1/session"));
        startHeartbeat();
        showWorkspace();
        await preload();
        scheduleRefresh();
        return;
      } catch (error) {
        if (error.code === "SESSION_EXPIRED" || document.body.dataset.ended) return;
        await delay(1000);
      }
    }
    expireSession();
  })();
  state.reconnecting = operation;
  operation.finally(() => { if (state.reconnecting === operation) state.reconnecting = null; });
  return operation;
}

function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

async function resumeExistingSession() {
  try {
    const data = await api("/api/v1/session/resume", { method: "POST", body: "{}" });
    rememberSessionCredentials(state.sessionToken, data.csrfToken);
    state.allowReconnect = true;
    delete document.body.dataset.ended;
    $("#pair-form").hidden = true;
    $("#pair-copy").textContent = t("reconnecting");
    await connect();
  } catch (error) {
    if (error.code === "SESSION_EXPIRED") clearSessionCredentials();
    if (!state.session) {
      $("#pair-form").hidden = false;
      applyPairingCopy();
    }
  }
}

function applyGainPalette(value) {
  if (value === "RED_GAIN") {
    document.documentElement.style.setProperty("--gain", "#c43c35");
    document.documentElement.style.setProperty("--loss", "#16855b");
  }
}
function applyPairingCopy() {
  document.documentElement.lang = state.locale;
  $("#pair-title").textContent = t("pairTitle");
  $("#pair-copy").textContent = t("pairCopy");
  $(".pair-form label").textContent = t("pairCode");
  $("#pair-submit").textContent = t("connect");
  $(".security-note").textContent = t("trustedLan");
}
function showWorkspace() {
  $("#connection-banner").hidden = true;
  Array.from($("#theme-toggle").options).forEach(option => option.textContent = t(option.value));
  $("#refresh-page").setAttribute("aria-label", t("refresh")); $("#refresh-page").title = t("refresh");
  $("#pairing").hidden = true; $("#workspace").hidden = false;
  $("#session-hint").textContent = t("sessionHint");
  $("#continue-session").textContent = t("continueSession");
  $("#connected-label").textContent = state.session?.dataMode === "DEMO" ? `${t("connected")} · ${t("demoMode")}` : t("connected");
  $("#end-session").textContent = t("end");
  $("#nav").setAttribute("aria-label", t("mainNav")); $("#theme-toggle").setAttribute("aria-label", t("theme"));
  $("#drawer-close").setAttribute("aria-label", t("close"));
  renderNav();
}
function expireSession(reason) {
  if (document.body.dataset.ended) return;
  document.body.dataset.ended = "true";
  state.allowReconnect = false;
  clearSessionCredentials();
  if (state.heartbeat) clearInterval(state.heartbeat);
  state.viewEpoch++; closeDrawer(true);
  state.socket?.close(); state.session = null; state.accounts = []; state.instruments = []; state.positions = []; state.recordFilters = {};
  state.expandedInvestmentAccounts.clear();
  clear($("#content")); $("#idle-warning").hidden = true;
  $("#toast").hidden = true; $("#connection-banner").hidden = true;
  $("#workspace").hidden = true; $("#pairing").hidden = false;
  $("#pair-form").hidden = true; $("#pair-copy").textContent = t(({ IDLE_TIMEOUT: "idleEnded", PHONE_BACKGROUND: "phoneBackground", PHONE_ENDED: "phoneEnded" })[reason] || "sessionLost");
  $("#pair-status").textContent = "";
}

let lastActivitySent = -Infinity;
function sendActivity(force = false) {
  if (!state.session || document.hidden || state.socket?.readyState !== WebSocket.OPEN) return;
  const now = performance.now();
  if (!force && now - lastActivitySent < 1000) return;
  lastActivitySent = now; state.socket.send('{"type":"activity"}');
}
function installActivityTracking() {
  ["pointerdown", "keydown", "input", "wheel", "touchmove"].forEach(name => document.addEventListener(name, event => {
    if (event.isTrusted) sendActivity();
  }, { passive: true, capture: true }));
  $("#continue-session").addEventListener("click", () => sendActivity(true));
  setInterval(() => {
    const remaining = Math.max(0, state.idleDeadline - performance.now());
    const show = !!state.session && remaining <= 30000 && state.socket?.readyState === WebSocket.OPEN;
    $("#idle-warning").hidden = !show;
    if (show) $("#idle-message").textContent = `${t("idleWarning")} · ${Math.ceil(remaining / 1000)}s`;
  }, 500);
}
hooks.expire = expireSession;
export { pair, reconnect, resumeExistingSession, applyPairingCopy, expireSession, installActivityTracking };
