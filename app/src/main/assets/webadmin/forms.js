// Saving: every write carries an operation id and the data generation it was made against. A lost or failed answer
// is looked up by that id before anything else is sent; until its outcome is known the dialog stays frozen and the
// same payload is the only thing that can be sent again. A new id is never made for a retry.
import { api, state, t, hooks } from "./core.js";
import { $$, toast, busy } from "./ui.js";

const controls = d => $$("input,select,textarea,button", d.root).filter(n => !n.closest(".dialog-head") && !n.closest(".dialog-foot"));

/**
 * Sends [body] for dialog [d]; true once the phone has it. While an earlier send of this dialog is unresolved, that
 * one is checked or resent instead of the new arguments.
 */
export async function submit(d, button, path, method, body, { message = t("saved"), keepOpen = false } = {}) {
  if (state.saving) return false;
  if (state.socket?.readyState !== WebSocket.OPEN) { d.fail(t("connectionRequired")); return false; }
  if (d.pending) [path, method, body] = d.pending;
  state.saving = true; d.saving = true; d.fail("");
  const frozen = controls(d).filter(n => !n.disabled);
  frozen.forEach(n => { n.disabled = true; });
  let ok = false;
  try {
    await busy(button, async () => {
      let done = false;
      if (d.pending) {
        try { await api(`/api/v1/operations/${body.operationId}`); done = true; } catch (error) { if (error.code !== "NOT_FOUND") throw error; }
      }
      if (!done) await api(path, { method, body: JSON.stringify(body) });
    });
    ok = true;
  } catch (error) {
    // A browser or proxy may have retried a committed request before a conflict came back: look it up first.
    let confirmed = false, unknown = false;
    if (!document.body.dataset.ended) {
      try { await api(`/api/v1/operations/${body.operationId}`); confirmed = true; } catch (lookup) { unknown = lookup.code !== "NOT_FOUND"; }
    }
    if (confirmed) ok = true;
    else if (!document.body.dataset.ended && (unknown || !error.code || String(error.code).startsWith("HTTP_5") || error.code === "INTERNAL" || error.code === "TIMEOUT")) {
      d.pending = [path, method, body]; d.fail(t("unknownResult"));
      button.querySelector(".lbl")?.replaceChildren(t("confirmResult"));
    } else { d.pending = null; d.fail(errorMessage(error)); }
  } finally {
    state.saving = false; d.saving = false;
    // An unresolved save keeps the inputs frozen: what is checked again must be what was sent.
    if (!d.pending) frozen.forEach(n => { n.disabled = false; });
    button.disabled = false;
  }
  if (!ok) return false;
  d.pending = null;
  if (!keepOpen) d.close(true);
  if (message) toast(message);
  hooks.saved?.();
  return true;
}

export function errorMessage(error) {
  const labels = { STALE_RECORD: t("staleRecord"), STALE_BALANCE: t("staleBalance"), CURRENCY_LOCKED: t("currencyLocked"),
    SYMBOL_LOCKED: t("symbolLocked"), INSUFFICIENT_HOLDING: t("insufficientHolding"), FORMAT: t("invalidFormat") };
  return labels[error.code] || (error.code && t(error.code) !== error.code ? t(error.code) : null)
    || (error.code ? t("saveFailedCode").replace("{code}", error.code) : t("saveFailed").replace("{reason}", error.message));
}

/** A payload for a write: the id that makes it safe to retry, and the generation it was made against. */
export const write = (extra = {}) => ({ operationId: uuid(), dataGeneration: state.generation, ...extra });
export function uuid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
  const h = Array.from(b, x => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
