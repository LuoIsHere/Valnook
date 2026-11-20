// The shell: pairing and ended pages, the top bar, page routing, quick search, hidden amounts, theme and session notices.
import { api, state, t, hooks, restoreSessionCredentials } from "./core.js";
import { el, sym, btn, iconBtn, $, $$, toast, wait, pushLayer, menu, confirmDiscard, countUp, tweenNumbers, dialogsOpen, closeAllDialogs } from "./ui.js";
import { route, go, setRenderer, render, PAGES } from "./router.js";
import { clearDetailLayer } from "./layout.js";
import { errorMessage } from "./forms.js";
import { view, pair, reconnect, resumeExistingSession, endSession, sendActivity, installActivityTracking } from "./session.js";
import { accountsPage, accountDialog, groupsDialog } from "./page-accounts.js";
import { recordsPage, keepRecordPages } from "./page-records.js";
import { investmentsPage, tradeDialog, instrumentDialog, isPriceEditing, cancelPriceEdit } from "./page-investments.js";
import { statsPage } from "./page-stats.js";
import { billsPage } from "./page-bills.js";

const app = $("#app");
const NAV = [["accounts", "accounts"], ["bills", "bills"], ["records", "records"], ["investments", "investments"], ["statistics", "statistics"]];
const pageFor = { accounts: accountsPage, bills: billsPage, records: recordsPage, investments: investmentsPage, statistics: statsPage };

/* ---------- Theme and hidden amounts (this browser only) ---------- */
const stored = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const store = (k, v) => { try { localStorage.setItem(k, v); } catch { /* private window */ } };
state.theme = stored("valnook-theme", "system");
state.hidden = stored("valnook-hidden", "0") === "1";
const darkQuery = matchMedia("(prefers-color-scheme: dark)");
const themeIcon = () => ({ system: "monitor", light: "sun", dark: "moon" })[state.theme];
function applyTheme() {
  document.documentElement.dataset.theme = state.theme === "system" ? (darkQuery.matches ? "dark" : "light") : state.theme;
  $$(".theme-btn").forEach(b => b.replaceChildren(sym(themeIcon(), "sm")));
}
darkQuery.addEventListener("change", applyTheme);
function setTheme(v) { state.theme = v; store("valnook-theme", v); applyTheme(); }
function setHidden(v) {
  state.hidden = v; store("valnook-hidden", v ? "1" : "0");
  document.body.classList.toggle("money-hidden", v);
  $$(".hide-toggle").forEach(b => { b.replaceChildren(sym(v ? "eye_slash" : "eye", "sm")); b.setAttribute("aria-pressed", String(v)); b.title = v ? t("showAmounts") : t("hideAmounts"); });
}

/* ---------- Pairing and ended pages ---------- */
function pairingPage(note = "", { busy = false } = {}) {
  closeAllDialogs(); clearDetailLayer();
  document.documentElement.lang = state.locale;
  // No maxlength: one-time-code autofill puts all six digits into the first box, and input() spreads them.
  const boxes = Array.from({ length: 6 }, (_, i) => el("input", { inputmode: "numeric", autocomplete: i ? "off" : "one-time-code", placeholder: " ",
    "aria-label": t("pairDigit").replace("{n}", i + 1) }));
  const otp = el("div", { class: "otp" }, boxes);
  const status = el("p", { class: `pair-status ${busy ? "ok" : ""}`, role: "status", "aria-live": "polite", text: note });
  const code = () => boxes.map(b => b.value).join("");
  const fill = (digits, from = 0) => { digits.split("").forEach((d, i) => { if (boxes[from + i]) boxes[from + i].value = d; }); boxes[Math.min(5, from + digits.length)].focus(); };
  boxes.forEach((b, i) => {
    b.addEventListener("input", () => {
      const v = b.value.replace(/\D/g, "");
      if (v.length > 1) fill(v.slice(0, 6 - i), i); else { b.value = v; if (v && i < 5) boxes[i + 1].focus(); }
      otp.classList.remove("error"); status.textContent = "";
      if (code().length === 6) submitCode();
    });
    b.addEventListener("keydown", e => {
      if (e.key === "Backspace" && !b.value && i) { boxes[i - 1].value = ""; boxes[i - 1].focus(); e.preventDefault(); }
      if (e.key === "ArrowLeft" && i) boxes[i - 1].focus();
      if (e.key === "ArrowRight" && i < 5) boxes[i + 1].focus();
    });
    b.addEventListener("paste", e => { const v = (e.clipboardData.getData("text") || "").replace(/\D/g, "").slice(0, 6); if (v) { e.preventDefault(); fill(v, 0); if (code().length === 6) submitCode(); } });
    b.addEventListener("focus", () => b.select());
  });
  async function submitCode() {
    otp.classList.add("busy"); boxes.forEach(b => { b.disabled = true; });
    status.className = "pair-status ok"; status.textContent = t("connectingPhone");
    const failure = await pair("code", code());
    if (!failure) return;
    otp.classList.remove("busy"); boxes.forEach(b => { b.disabled = false; b.value = ""; });
    status.className = "pair-status"; status.textContent = failure.message;
    otp.classList.remove("error"); void otp.offsetWidth; otp.classList.add("error");
    boxes[0].focus();
  }
  if (busy) { otp.classList.add("busy"); boxes.forEach(b => { b.disabled = true; }); }
  app.replaceChildren(el("main", { class: "pair" }, el("div", { class: "pair-inner entering", "aria-labelledby": "pair-title" },
    el("img", { class: "logo", src: "/valnook.svg", alt: "" }),
    el("span", { class: "eyebrow", text: "Valnook Web" }),
    el("h1", { id: "pair-title", class: "text-grad", text: t("pairTitle") }),
    el("p", { class: "lead", text: t("pairLead") }),
    el("div", { class: "otp-wrap reveal" }, otp, status),
    el("ol", { class: "steps reveal" },
      el("li", {}, el("b", { text: "1" }), t("pairStep1")), el("li", {}, el("b", { text: "2" }), t("pairStep2")), el("li", {}, el("b", { text: "3" }), t("pairStep3"))),
    el("p", { class: "pair-note" }, sym("shield", "sm"), t("trustedLan")))));
  if (!busy) boxes[0].focus();
}
function endedPage(key) {
  closeAllDialogs(); clearDetailLayer(); clearInterval(idleTimer);
  document.body.classList.remove("money-hidden");
  // The phone's server stops when the phone ended it; otherwise the code on the phone can pair again.
  const phoneStopped = ["idleEnded", "phoneBackground", "phoneEnded"].includes(key);
  app.replaceChildren(el("main", { class: "pair" }, el("div", { class: "pair-inner entering" }, el("img", { class: "logo", src: "/valnook.svg", alt: "" }),
    el("span", { class: "eyebrow", text: "Session ended" }), el("h1", { class: "text-grad", text: t("sessionEnded") }), el("p", { class: "lead", text: t(key) }),
    phoneStopped ? null : el("div", { class: "spaced-l" }, btn(t("connectAgain"), () => { delete document.body.dataset.ended; pairingPage(); }, "primary lg")),
    el("p", { class: "pair-note" }, sym("shield", "sm"), t("pageCleared")))));
}

/* ---------- Workspace ---------- */
async function preload() {
  const [accounts, instruments, types, positions, categories] = await Promise.all([api("/api/v1/accounts"), api("/api/v1/instruments"),
    api("/api/v1/asset-types"), api("/api/v1/positions"), api("/api/v1/bill-categories")]);
  state.accounts = accounts.items; state.accountGroups = accounts.groups; state.instruments = instruments.items; state.assetTypes = types.items; state.positions = positions.items;
  state.billCategories = categories.items;
  state.lastSync = Date.now();
}
let idleTimer = null, lastPage = null, enterTimer = 0, switchTimer = 0, shownNumbers = {}, refreshing = false;
async function workspace() {
  app.replaceChildren(shell());
  setHidden(state.hidden); applyTheme();
  clearInterval(idleTimer); idleTimer = setInterval(tickIdle, 500);
  lastPage = null;
  await preload();
  if (!location.hash.startsWith("#/")) history.replaceState(null, "", "#/accounts");
  await render();
}
function shell() {
  const links = () => NAV.map(([k, icon]) => el("a", { href: `#/${k}`, "data-page": k }, sym(icon), el("span", { text: t(k) })));
  const connMenu = e => menu(e.currentTarget, [
    { label: state.session?.dataMode === "DEMO" ? `${t("connected")} · ${t("demoMode")}` : t("connectedSameLan"), icon: "phone", run: () => toast(t("sessionHint")) }, "-",
    { label: t("end"), icon: "logout", danger: true, run: async () => { if (dialogsOpen() && !(await confirmDiscard())) return; closeAllDialogs(); await endSession(); } }]);
  const themeMenu = e => menu(e.currentTarget, [["system", t("system"), "monitor"], ["light", t("light"), "sun"], ["dark", t("dark"), "moon"]]
    .map(([v, label, icon]) => ({ label: state.theme === v ? `${label} ✓` : label, icon, run: () => setTheme(v) })));
  return el("div", { class: "shell" },
    el("header", { class: "topbar" }, el("div", { class: "topbar-inner" },
      el("a", { class: "brand", href: "#/accounts" }, el("img", { src: "/valnook.svg", alt: "" }), el("span", { text: "Valnook" }),
        state.session?.dataMode === "DEMO" ? el("span", { class: "tag att demo", text: t("demoMode") }) : null),
      el("nav", { class: "topnav", "aria-label": t("mainNav") }, links()),
      el("div", { class: "top-actions" },
        el("button", { type: "button", class: "search-btn", "aria-label": t("quickSearch"), onclick: () => palette() }, sym("search", "sm"), el("span", { text: t("search") }), el("kbd", { text: "Ctrl K" })),
        el("button", { type: "button", class: "icon-btn hide-toggle", "aria-pressed": String(state.hidden), title: t("hideAmounts"), onclick: () => setHidden(!state.hidden) }, sym("eye", "sm")),
        el("button", { type: "button", class: "icon-btn theme-btn", title: t("theme"), "aria-label": t("theme"), onclick: themeMenu }, sym(themeIcon(), "sm")),
        el("button", { type: "button", class: "conn-pill", title: t("sessionHint"), onclick: connMenu },
          el("span", { class: "conn-dot", id: "conn-dot" }), el("span", { class: "name", id: "conn-label", text: t("connected") }), el("span", { id: "idle-label", hidden: true }))))),
    el("div", { id: "banners" }),
    el("main", { id: "main", tabindex: "-1" }),
    el("nav", { class: "tabbar", "aria-label": t("mainNav") }, links()));
}

/** Draws the current route. A switch inside a page (month, tab) plays its transition; any other redraw stays still. */
let loadingTimer = 0;
setRenderer(async () => {
  if (!state.session || document.body.dataset.ended) return;
  const r = route(), main = $("#main"); if (!main) return;
  const motion = state.motion; state.motion = null;
  const samePage = lastPage === r.page, epoch = ++state.viewEpoch, y = scrollY;
  if (samePage && r.page === "records" && !refreshing) keepRecordPages();
  clearDetailLayer();
  clearTimeout(loadingTimer);
  loadingTimer = setTimeout(() => { if (epoch === state.viewEpoch && !$(".page-loading")) document.body.append(el("div", { class: "page-loading", role: "status", text: refreshing ? t("updating") : t("loading") })); }, 180);
  let nodes;
  try { nodes = await pageFor[r.page](r); }
  catch (error) {
    if (error.name === "AbortError" || epoch !== state.viewEpoch) return;
    if (refreshing) { toast(t("loadFailed")); return; }
    nodes = [el("div", { class: "load-error" }, el("p", { text: `${t("loadFailed")}: ${errorMessage(error)}` }), btn(t("refresh"), () => render(), "tonal"))];
  } finally { if (epoch === state.viewEpoch) { clearTimeout(loadingTimer); $(".page-loading")?.remove(); } }
  if (epoch !== state.viewEpoch) return;
  main.replaceChildren(el("div", { class: "wrap" }, ...nodes));
  $$(".topnav a, .tabbar a").forEach(a => { if (a.dataset.page === r.page) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current"); });
  document.title = `${t(r.page)} · Valnook`;
  // Headline amounts remember what they showed, so a switched month moves them from the old value to the new one.
  const before = shownNumbers; shownNumbers = {};
  $$("[data-key][data-count]", main).forEach(n => { shownNumbers[n.dataset.key] = n.dataset.count; if (samePage && motion && before[n.dataset.key] != null) n.dataset.from = before[n.dataset.key]; });
  main.classList.remove("switching", "m-next", "m-prev", "m-tab");
  if (samePage) {
    scrollTo(0, y);
    if (motion) {
      void main.offsetWidth; clearTimeout(switchTimer);
      main.classList.add("switching", `m-${motion}`); switchTimer = setTimeout(() => main.classList.remove("switching", "m-next", "m-prev", "m-tab"), 1300);
      tweenNumbers(main);
    }
  } else {
    // A new page rises in and rolls its headline numbers up; kept until the longest entrance (the donut sweep) is done.
    scrollTo(0, 0); main.focus({ preventScroll: true });
    clearTimeout(enterTimer); main.classList.add("entering"); enterTimer = setTimeout(() => main.classList.remove("entering"), 2400);
    countUp(main);
  }
  lastPage = r.page;
});

/** Reloads the shared lists and redraws in place; while a dialog or a price edit is open it waits for them. */
async function refresh() {
  if (dialogsOpen() || state.saving || isPriceEditing()) { state.pendingRefresh = true; return; }
  state.pendingRefresh = false; refreshing = true;
  try { await preload(); await render(); } catch (error) { if (error.name !== "AbortError") toast(t("loadFailed")); }
  finally { refreshing = false; }
}
hooks.saved = () => refresh();
hooks.dialogsClosed = () => { if (state.pendingRefresh) refresh(); };
hooks.error = error => { if (error.name !== "AbortError") toast(errorMessage(error)); };

let shownHash = location.hash;
window.addEventListener("hashchange", async () => {
  if (!state.session) return;
  // Leaving a price edit with changes asks first, as closing a changed dialog does.
  const r = route(), leaving = !(r.page === "investments" && r.a === "instruments");
  if (isPriceEditing() && leaving) {
    const target = location.hash; history.replaceState(null, "", shownHash);
    if (!(await confirmDiscard())) return;
    history.replaceState(null, "", target);
  }
  if (leaving) cancelPriceEdit();
  shownHash = location.hash; render();
});
window.addEventListener("beforeunload", event => {
  if (state.session && (dialogsOpen() || isPriceEditing())) { event.preventDefault(); event.returnValue = ""; }
});

/* A soft spotlight follows the pointer across tiles and lists. */
let spotFrame = 0;
document.addEventListener("pointermove", e => {
  if (spotFrame) return;
  spotFrame = requestAnimationFrame(() => {
    spotFrame = 0;
    const tile = e.target.closest?.(".tile, .list"); if (!tile) return;
    const r = tile.getBoundingClientRect();
    tile.style.setProperty("--mx", `${e.clientX - r.left}px`); tile.style.setProperty("--my", `${e.clientY - r.top}px`);
  });
}, { passive: true });

/* ---------- Connection and idle notices ---------- */
function connection(kind) {
  const banners = $("#banners"), dot = $("#conn-dot"), label = $("#conn-label");
  $("#reconnect-banner")?.remove();
  if (kind === "reconnecting") {
    banners?.append(el("div", { class: "banner info", id: "reconnect-banner", role: "status" }, el("span", { class: "spinner" }), t("reconnectingNotice")));
    dot?.classList.add("warn"); if (label) label.textContent = t("reconnectingShort");
  } else { dot?.classList.remove("warn"); if (label) label.textContent = t("connected"); }
}
function tickIdle() {
  if (!state.session) return;
  const left = Math.max(0, Math.ceil((state.idleDeadline - performance.now()) / 1000));
  const show = left <= 60 && state.socket?.readyState === WebSocket.OPEN;
  const label = $("#idle-label"); if (label) { label.hidden = !show; label.textContent = `0:${String(left).padStart(2, "0")}`; }
  $("#conn-dot")?.classList.toggle("warn", show || !!$("#reconnect-banner"));
  let banner = $("#idle-banner");
  if (show) {
    if (!banner) { banner = el("div", { class: "banner", id: "idle-banner", role: "alert" }, sym("alert", "sm"), el("span", { id: "idle-text" }),
      btn(t("continueSession"), () => { sendActivity(true); banner.remove(); }, "primary sm")); $("#banners")?.prepend(banner); }
    $("#idle-text").textContent = t("idleCountdown").replace("{n}", left);
  } else banner?.remove();
}

/* ---------- Quick search ---------- */
function palette() {
  if ($(".palette") || !state.session) return;
  const input = el("input", { type: "text", placeholder: t("paletteHint"), "aria-label": t("search"), autocomplete: "off" });
  const results = el("div", { class: "palette-results", role: "listbox" });
  const box = el("div", { class: "palette", onmousedown: e => { if (e.target === box) close(); } },
    el("div", { class: "palette-box", role: "dialog", "aria-label": t("quickSearch") },
      el("div", { class: "palette-input" }, sym("search"), input, el("kbd", { text: "Esc" })), results,
      el("div", { class: "palette-foot" }, el("span", {}, el("kbd", { text: "↑" }), el("kbd", { text: "↓" }), t("select")), el("span", {}, el("kbd", { text: "Enter" }), t("open")),
        el("span", {}, el("kbd", { text: "/" }), t("openSearchAnytime")))));
  const items = [
    ...NAV.map(([k, icon]) => ({ group: t("pages"), label: t(k), icon, run: () => go(k) })),
    { group: t("pages"), label: t("expenseStats"), icon: "statistics", run: () => go("statistics/expense") },
    { group: t("pages"), label: t("incomeStats"), icon: "statistics", run: () => go("statistics/income") },
    { group: t("actions"), label: t("newAccount"), icon: "plus", run: () => accountDialog(null) },
    { group: t("actions"), label: t("newTrade"), icon: "plus", run: () => tradeDialog(null, null) },
    { group: t("actions"), label: t("newInstrument"), icon: "plus", run: () => instrumentDialog(null) },
    { group: t("actions"), label: t("accountGroups"), icon: "layers", run: () => groupsDialog() },
    { group: t("actions"), label: state.hidden ? t("showAmounts") : t("hideAmounts"), icon: "eye", run: () => setHidden(!state.hidden) },
    { group: t("actions"), label: t("toDark"), icon: "moon", run: () => setTheme("dark") },
    { group: t("actions"), label: t("toLight"), icon: "sun", run: () => setTheme("light") },
    ...state.accounts.map(a => ({ group: t("accounts"), label: a.name, sub: a.note, icon: "accounts", run: () => go(`accounts/${a.id}`) })),
    ...state.positions.filter(p => Number(p.quantity) > 0).map(p => ({ group: t("positions"), label: p.name,
      sub: `${p.symbol} · ${state.accounts.find(a => a.id === p.accountId)?.name || ""}`, icon: "investments", run: () => go(`investments/positions/${p.id}`) })),
    ...state.instruments.map(i => ({ group: t("instruments"), label: i.name, sub: `${i.symbol} · ${i.currencyCode}`, icon: "statistics", run: () => go(`investments/instruments/${i.id}`) })),
  ];
  let sel = 0, shown = [];
  function draw() {
    const q = input.value.trim().toLowerCase();
    shown = items.filter(i => !q || `${i.label} ${i.sub || ""}`.toLowerCase().includes(q)).slice(0, 40);
    if (!q) shown = shown.filter(i => i.group === t("pages") || i.group === t("actions"));
    sel = Math.min(sel, Math.max(0, shown.length - 1));
    const nodes = []; let g = "";
    shown.forEach((it, i) => {
      if (it.group !== g) { g = it.group; nodes.push(el("div", { class: "grp", text: g })); }
      nodes.push(el("button", { type: "button", role: "option", "aria-selected": String(i === sel), onmousemove: () => { if (sel !== i) { sel = i; draw(); } }, onclick: () => pick(i) },
        sym(it.icon, "sm"), el("div", { class: "grow" }, el("div", { text: it.label }), it.sub ? el("div", { class: "sub", text: it.sub }) : null),
        i === sel ? sym("arrow_right", "sm faint") : null));
    });
    results.replaceChildren(...(nodes.length ? nodes : [el("div", { class: "empty", text: t("nothingFound").replace("{q}", input.value) })]));
  }
  const pick = i => { const it = shown[i]; if (!it) return; close(); it.run(); };
  input.addEventListener("input", () => { sel = 0; draw(); });
  input.addEventListener("keydown", e => {
    if (e.key === "ArrowDown") { e.preventDefault(); sel = (sel + 1) % Math.max(1, shown.length); draw(); $("[aria-selected=true]", results)?.scrollIntoView({ block: "nearest" }); }
    if (e.key === "ArrowUp") { e.preventDefault(); sel = (sel - 1 + shown.length) % Math.max(1, shown.length); draw(); $("[aria-selected=true]", results)?.scrollIntoView({ block: "nearest" }); }
    if (e.key === "Enter") { e.preventDefault(); pick(sel); }
  });
  const remove = pushLayer(() => close());
  const prev = document.activeElement;
  function close() { remove(); box.remove(); prev?.focus?.({ preventScroll: true }); }
  document.body.append(box); draw(); input.focus();
}
document.addEventListener("keydown", e => {
  if (!state.session) return;
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName);
  if ((e.key === "k" && (e.ctrlKey || e.metaKey)) || (e.key === "/" && !typing && !dialogsOpen())) { e.preventDefault(); palette(); }
});

/* ---------- Start ---------- */
Object.assign(view, {
  pairing: pairingPage,
  workspace,
  ended: endedPage,
  connection,
  // A change this page just saved refreshes after the save itself; only someone else's change while editing is worth a note.
  dataChanged: () => {
    if (state.saving) { state.pendingRefresh = true; return; }
    if (dialogsOpen() || isPriceEditing()) { state.pendingRefresh = true; toast(t("changed")); } else refresh();
  },
});
window.addEventListener("online", () => reconnect());
window.addEventListener("pageshow", () => { if (state.session && state.socket?.readyState !== WebSocket.OPEN) reconnect(); });
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible" && state.session && state.socket?.readyState !== WebSocket.OPEN) reconnect(); });
installActivityTracking();
applyTheme();
restoreSessionCredentials();
// The QR code's one-time token leaves the address bar at once, so a reload never retries a spent one.
const qr = new URLSearchParams(location.hash.slice(1)).get("pair");
if (qr) {
  history.replaceState(null, "", location.pathname);
  pairingPage(t("qrConnecting"), { busy: true });
  pair("qr", qr).then(failure => { if (failure) pairingPage(failure.retry ? t("qrFailedUseCode") : failure.message); });
} else {
  resumeExistingSession().then(resumed => { if (!resumed && !state.session) pairingPage(); });
}
