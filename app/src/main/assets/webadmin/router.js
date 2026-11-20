// Address-bar routes (#/page/a/b): the open page and item survive a reload, and Back closes a detail.
// The pairing QR code's #pair=… fragment is read and removed before any route is shown (see app.js).
import { state } from "./core.js";

export const PAGES = ["accounts", "bills", "records", "investments", "statistics"];

export function route() {
  const parts = location.hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeURIComponent);
  return { page: PAGES.includes(parts[0]) ? parts[0] : "accounts", a: parts[1], b: parts[2] };
}

let renderer = () => {};
export function setRenderer(fn) { renderer = fn; }
export function render() { return renderer(); }

/**
 * Opens [path]. [motion] names a switch the user asked for inside a page ("next" or "prev" for months, "tab" for
 * tabs): the redraw then plays that transition instead of standing still.
 */
export function go(path, { replace = false, motion = null } = {}) {
  const hash = `#/${path}`;
  state.motion = motion;
  if (location.hash === hash) return render();
  if (replace) { history.replaceState(null, "", hash); return render(); }
  location.hash = hash;
}
