// The session with the phone: pairing, the WebSocket that keeps it alive, reconnecting, and its end.
// What the page shows for each of these is up to the shell (app.js), reached through `view`.
import { rememberSessionCredentials, clearSessionCredentials, api, state, hooks, t } from "./core.js";
import { clearAvatarCache } from "./icons.js";

/** Set by app.js: pairing(status), workspace(), connection(kind), ended(reasonKey), dataChanged(). */
export const view = {};

export async function pair(kind, value) {
  try {
    const data = await api(`/api/v1/pair/${kind}`, { method: "POST", body: JSON.stringify({ [kind === "qr" ? "token" : "code"]: value }) });
    rememberSessionCredentials(data.sessionToken, data.csrfToken);
    state.allowReconnect = true;
    delete document.body.dataset.ended;
    await connect();
    return null;
  } catch (error) {
    if (String(error.message).startsWith("WS_")) return { message: t("wsFailed"), retry: false };
    if (error.code === "PAIR_CODE_ROTATED") return { message: t("pairWrong"), retry: true };
    if (error.code === "PAIR_INVALID") return { message: t("pairInvalid"), retry: true };
    return { message: `${t("pairFailed")}: ${error.code || error.message || "UNKNOWN"}`, retry: true };
  }
}

async function connect() {
  const deadline = Date.now() + 40000;
  while (true) {
    try { await openSocket(); break; } catch (error) {
      if (!String(error.message).startsWith("WS_") || Date.now() >= deadline) throw error;
      await delay(1000);
    }
  }
  applySession(await api("/api/v1/session"));
  startHeartbeat();
  await view.workspace();
  if (state.socket?.readyState !== WebSocket.OPEN) reconnect();
}

function openSocket() {
  if (state.connecting) return state.connecting;
  // The session secret stays out of the URL: each connection uses a one-time ticket good for 30 seconds.
  const operation = api("/api/v1/session/socket-ticket", { method: "POST", body: "{}" }).then(({ ticket }) => new Promise((resolve, reject) => {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(`${protocol}//${location.host}/ws/v1/session?ticket=${encodeURIComponent(ticket)}`);
    const timer = setTimeout(() => { socket.close(); reject(new Error("WS_TIMEOUT")); }, 15000);
    let ready = false;
    socket.addEventListener("error", () => { if (!ready) { clearTimeout(timer); reject(new Error("WS_FAILED")); } }, { once: true });
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
      if (msg.type === "dataChanged") view.dataChanged?.();
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
  }));
  state.connecting = operation;
  return operation.finally(() => { if (state.connecting === operation) state.connecting = null; });
}

function applySession(session) {
  state.session = session;
  state.locale = session.language === "ENGLISH" ? "en" : "zh-CN";
  document.documentElement.lang = state.locale;
  // Red for gain follows the phone; the stylesheet swaps the colours for both themes.
  if (session.gainLossColors === "RED_GAIN") document.documentElement.dataset.gain = "red"; else delete document.documentElement.dataset.gain;
}

function startHeartbeat() {
  if (state.heartbeat) clearInterval(state.heartbeat);
  state.heartbeat = setInterval(() => { if (state.socket?.readyState === WebSocket.OPEN) state.socket.send('{"type":"heartbeat"}'); }, 5000);
}

export function reconnect() {
  if (state.reconnecting || !state.allowReconnect || document.body.dataset.ended) return state.reconnecting;
  if (state.session) view.connection?.("reconnecting");
  const operation = (async () => {
    const deadline = Date.now() + 40000;
    while (Date.now() < deadline && !document.body.dataset.ended) {
      try {
        await openSocket();
        applySession(await api("/api/v1/session"));
        startHeartbeat();
        view.connection?.("online");
        view.dataChanged?.();
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

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

/** A reload keeps the session: its token is in this tab's storage, and the phone hands out a new CSRF token. */
export async function resumeExistingSession() {
  if (!state.sessionToken) return false;
  try {
    const data = await api("/api/v1/session/resume", { method: "POST", body: "{}" });
    rememberSessionCredentials(state.sessionToken, data.csrfToken);
    state.allowReconnect = true;
    delete document.body.dataset.ended;
    view.pairing?.(t("reconnecting"), { busy: true });
    await connect();
    return true;
  } catch (error) {
    if (error.code === "SESSION_EXPIRED") clearSessionCredentials();
    return false;
  }
}

export async function endSession() {
  try { await api("/api/v1/session/end", { method: "POST", body: "{}" }); } finally { expireSession("BROWSER_ENDED"); }
}

export function expireSession(reason) {
  if (document.body.dataset.ended) return;
  document.body.dataset.ended = "true";
  state.allowReconnect = false;
  clearSessionCredentials();
  if (state.heartbeat) clearInterval(state.heartbeat);
  state.viewEpoch++;
  state.socket?.close(); state.session = null; state.accounts = []; state.instruments = []; state.positions = []; state.assetTypes = [];
  clearAvatarCache();
  // When the phone ended the session its server stops too; otherwise the code on the phone can pair again.
  const key = ({ IDLE_TIMEOUT: "idleEnded", PHONE_BACKGROUND: "phoneBackground", PHONE_ENDED: "phoneEnded", BROWSER_ENDED: "browserEnded" })[reason] || "sessionLost";
  view.ended?.(key);
}

let lastActivitySent = -Infinity;
/** Real input only (never animations or timers) tells the phone someone is still here. */
export function sendActivity(force = false) {
  if (!state.session || document.hidden || state.socket?.readyState !== WebSocket.OPEN) return;
  const now = performance.now();
  if (!force && now - lastActivitySent < 1000) return;
  lastActivitySent = now; state.socket.send('{"type":"activity"}');
}
export function installActivityTracking() {
  ["pointerdown", "keydown", "input", "wheel", "touchmove"].forEach(name => document.addEventListener(name, event => {
    if (event.isTrusted) sendActivity();
  }, { passive: true, capture: true }));
}
hooks.expire = expireSession;
