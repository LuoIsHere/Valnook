import { el, clear, fmt, when, epochDay, gainClass, accountName, uuid, restoreSessionCredentials, rememberSessionCredentials, clearSessionCredentials, api, $, state, hooks, t } from "./core.js";
import { closeDrawer, notify, refreshCurrent } from "./pages.js";
import { symbol } from "./icons.js";
import { pair, reconnect, resumeExistingSession, applyPairingCopy, expireSession, installActivityTracking } from "./session.js";
$("#pair-form").addEventListener("submit", event => { event.preventDefault(); const code = $("#pair-code").value.trim(); if (/^[0-9]{6}$/.test(code)) pair("code", code); });
$("#drawer-close").addEventListener("click", closeDrawer); $("#scrim").addEventListener("click", closeDrawer);
document.addEventListener("keydown", event => { if (event.key === "Escape") closeDrawer(); });
window.addEventListener("online", () => reconnect());
window.addEventListener("pageshow", () => { if (state.session && state.socket?.readyState !== WebSocket.OPEN) reconnect(); });
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && state.session && state.socket?.readyState !== WebSocket.OPEN) reconnect();
});
$("#end-session").addEventListener("click", async () => { try { await api("/api/v1/session/end", { method: "POST", body: "{}" }); } finally { expireSession("PHONE_ENDED"); } });
const themeSelect = $("#theme-toggle");
try { themeSelect.value = localStorage.getItem("valnook-theme") || "system"; } catch (_) {}
function applyTheme() { document.documentElement.dataset.theme = themeSelect.value; }
themeSelect.addEventListener("change", () => { applyTheme(); try { localStorage.setItem("valnook-theme", themeSelect.value); } catch (_) {} }); applyTheme();
document.addEventListener("keydown", event => {
  if (event.key !== "Tab" || $("#drawer").hidden) return;
  const nodes = Array.from($("#drawer").querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), summary, [tabindex="0"]')).filter(v => v.getClientRects().length);
  const first = nodes[0], last = nodes.at(-1);
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
});
window.addEventListener("beforeunload", event => {
  if ($("#drawer-content form[data-dirty]") || $("#drawer-content form")?.pendingSubmission) { event.preventDefault(); event.returnValue = ""; }
});
installActivityTracking();
$("#refresh-page").append(symbol("currency_exchange"));
$("#refresh-page").addEventListener("click", () => refreshCurrent());

const fragment = new URLSearchParams(location.hash.slice(1)); const qr = fragment.get("pair");
restoreSessionCredentials();
applyPairingCopy();
if (qr) { $("#pair-form").hidden = true; $("#pair-copy").textContent = t("qrConnecting"); pair("qr", qr); }
else { resumeExistingSession(); }
