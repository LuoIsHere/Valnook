// Building blocks shared by every page: icons, amounts, buttons, dialogs, menus, notes and form controls.
// The server allows no inline style attributes: styles set here go through the CSSOM (style.setProperty).
import { state, t, hooks } from "./core.js";
import { decimalText } from "./decimal.js";
import { symbols, uiSymbols, billSymbols } from "./symbols.js";

export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
const SVG = new Set(["svg", "path", "circle", "line", "polyline", "rect", "g", "text", "defs", "linearGradient", "stop",
  "filter", "feGaussianBlur", "animate", "title"]);

/** An element: `text` sets its text, `on…` adds a listener, `css` sets custom properties and styles. */
export function el(tag, attrs = {}, ...children) {
  const svg = SVG.has(tag);
  const node = svg ? document.createElementNS("http://www.w3.org/2000/svg", tag) : document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === "text") node.textContent = String(v);
    else if (k === "css") for (const [p, value] of Object.entries(v)) node.style.setProperty(p, value);
    else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2), event => {
      try { Promise.resolve(v(event)).catch(error => hooks.error?.(error)); } catch (error) { hooks.error?.(error); }
    });
    else if (k === "class") node.setAttribute("class", v);
    else if (k === "value" && !svg && "value" in node) node.value = v;
    else if (["checked", "disabled", "readOnly", "selected", "hidden", "open", "required"].includes(k) && !svg) node[k] = !!v;
    else node.setAttribute(k, v === true ? "" : String(v));
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) node.append(c.nodeType ? c : document.createTextNode(String(c)));
  return node;
}

/* ---------- Icons ---------- */
// A few stroke glyphs that are not in the Font Awesome set the app ships; drawn for this page.
const glyphs = {
  sun: ["M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"],
  monitor: ["M3 5h18v11H3z", "M8 20h8M12 16v4"],
  logout: ["M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3", "M10 16l-4-4 4-4M6 12h10"],
  info: ["M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", "M12 11v5M12 7.5v.5"],
  alert: ["M12 3.5L2.5 20h19z", "M12 10v4.5M12 17v.5"],
  shield: ["M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6z"],
  arrow_right: ["M5 12h14M13 6l6 6-6 6"], undo: ["M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3"],
  filter_x: ["M18 6L6 18M6 6l12 12"], calendar: ["M4 6h16v14H4z", "M4 10h16M8 3v4M16 3v4"],
};
/** An icon by name: an interface icon (ui_…), an account icon, a bill category icon (its stored key) or a glyph. */
export function sym(name, cls = "") {
  const fa = uiSymbols[`ui_${name}`] || uiSymbols[name] || symbols[name] || billSymbols[name];
  if (fa) return el("svg", { class: `sym ${cls}`, viewBox: fa.viewBox, "aria-hidden": "true" }, fa.paths.map(d => el("path", { d, fill: "currentColor" })));
  const s = glyphs[name];
  if (!s) return sym("circle-question", cls); // an unknown key (a newer version's) shows the question mark
  return el("svg", { class: `sym ${cls}`, viewBox: "0 0 24 24", "aria-hidden": "true", fill: "none", stroke: "currentColor",
    "stroke-width": "2", "stroke-linecap": "round", "stroke-linejoin": "round" }, s.map(d => el("path", { d })));
}

/* ---------- Amounts ---------- */
const prefixes = { CNY: "¥", USD: "$", HKD: "HK$", EUR: "€", GBP: "£", JPY: "JP¥" };
export function digitsOf(code) { return ["JPY", "KRW", "VND"].includes(code) ? 0 : ["KWD", "BHD", "OMR", "TND"].includes(code) ? 3 : 2; }
/** An exact amount (decimal text from the server) for display: U+2212 for minus, a symbol or a trailing code. */
export function fmt(value, currency, { sign = false, digits = currency ? digitsOf(currency) : 2, min = null } = {}) {
  if (value == null || value === "") return "—";
  // With [min], trailing zeros past [min] places are dropped (prices: up to 5 places, at least 2).
  let text = decimalText(String(value), state.locale, digits, min != null);
  if (min != null && text !== "—") { const [w, f = ""] = text.split("."); text = min || f ? `${w}.${f.padEnd(min, "0")}` : w; }
  if (text === "—") return text;
  const negative = text.startsWith("-"), zero = !/[1-9]/.test(text);
  const body = negative ? text.slice(1) : text;
  const lead = negative && !zero ? "−" : sign && !zero ? "+" : "";
  const p = prefixes[currency];
  return p ? `${lead}${p}${body}` : `${lead}${body}${currency ? ` ${currency}` : ""}`;
}
export const tone = v => (Number(v) > 0 ? "gain" : Number(v) < 0 ? "loss" : "");
/** An amount that blurs while amounts are hidden; `count` rolls it up when a page opens, `key` lets a switch move it. */
export function money(value, currency, opts = {}) {
  const node = el("span", { class: `num mask ${opts.tone ? tone(value) : ""} ${opts.class || ""}`, text: fmt(value, currency, opts) });
  if (opts.count && value != null) Object.assign(node.dataset, { count: String(value), cur: currency || "", sign: opts.sign ? "1" : "" });
  if (opts.key) node.dataset.key = opts.key;
  return node;
}
/** Large gradient numbers are painted by their parent, which therefore blurs as a whole when amounts are hidden. */
export function gradient(node) { node.classList.add("text-grad", "has-money"); return node; }
export function compact(value, currency) {
  const n = Number(value), a = Math.abs(n), s = n < 0 ? "−" : "", p = prefixes[currency] || "";
  const zh = state.locale.startsWith("zh");
  const nf = (v, max) => new Intl.NumberFormat(state.locale, { maximumFractionDigits: max }).format(v);
  if (zh && a >= 1e8) return `${s}${p}${nf(a / 1e8, 2)}亿`;
  if (zh && a >= 1e4) return `${s}${p}${nf(a / 1e4, 1)}万`;
  if (!zh && a >= 1e6) return `${s}${p}${nf(a / 1e6, 1)}M`;
  if (!zh && a >= 1e3) return `${s}${p}${nf(a / 1e3, 1)}K`;
  return `${s}${p}${nf(a, 0)}`;
}
export const pct = (part, whole) => (Number(whole) ? Number(part) / Number(whole) * 100 : 0);
export const pctText = (v, digits = 1) => `${new Intl.NumberFormat(state.locale, { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(v)}%`;
/** Brackets for the page language: full-width in Chinese. */
export const paren = text => (state.locale.startsWith("zh") ? `（${text}）` : ` (${text})`);
export const qtyText = v => (v == null ? "—" : decimalText(String(v), state.locale, 8, true));

const reduced = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
/** Rolls marked amounts up from zero; floats only on the way, the exact server text when done (or when frames pause). */
export function countUp(root) {
  const nodes = $$("[data-count]", root);
  if (!nodes.length || reduced()) return;
  animateNumbers(nodes, 1100, n => 0, k => 1 - Math.pow(1 - k, 4));
}
/** Moves marked amounts from the value shown before (data-from) to the new one, as when a month is switched. */
export function tweenNumbers(root) {
  const nodes = $$("[data-from]", root);
  if (!nodes.length || reduced()) return;
  animateNumbers(nodes, 650, n => Number(n.dataset.from), k => 1 - Math.pow(1 - k, 3));
}
function animateNumbers(nodes, dur, from, ease) {
  const finals = nodes.map(n => n.textContent), start = performance.now();
  const paint = k => nodes.forEach(n => {
    const a = from(n), b = Number(n.dataset.count);
    n.textContent = fmt((a + (b - a) * k).toFixed(digitsOf(n.dataset.cur)), n.dataset.cur || undefined, { sign: !!n.dataset.sign });
  });
  let done = false;
  const finish = () => { if (!done) { done = true; nodes.forEach((n, i) => { n.textContent = finals[i]; }); } };
  paint(0);
  const frame = now => {
    if (done) return;
    const p = Math.max(0, Math.min(1, (now - start) / dur));
    if (p >= 1) finish(); else { paint(ease(p)); requestAnimationFrame(frame); }
  };
  requestAnimationFrame(frame); setTimeout(finish, dur + 60);
}

/* ---------- Dates ---------- */
export function when(ms, style = "full") {
  if (ms == null) return "—";
  const d = new Date(ms);
  if (style === "time") return d.toLocaleTimeString(state.locale, { hour: "2-digit", minute: "2-digit" });
  if (style === "date") return d.toLocaleDateString(state.locale, { year: "numeric", month: "long", day: "numeric" });
  return d.toLocaleString(state.locale, { year: "numeric", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
}
export function relative(ms) {
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return t("justNow");
  if (m < 60) return t("minutesAgo").replace("{n}", m);
  const h = Math.round(m / 60); if (h < 24) return t("hoursAgo").replace("{n}", h);
  const d = Math.round(h / 24); return d < 30 ? t("daysAgo").replace("{n}", d) : when(ms, "date");
}
export function dayLabel(ms) {
  const d = new Date(ms), today = new Date(); today.setHours(0, 0, 0, 0);
  const diff = Math.round((today - new Date(d).setHours(0, 0, 0, 0)) / 86400000);
  const wk = d.toLocaleDateString(state.locale, { weekday: "short" });
  if (diff === 0) return `${t("today")} · ${wk}`;
  if (diff === 1) return `${t("yesterday")} · ${wk}`;
  const opts = d.getFullYear() === today.getFullYear() ? { month: "long", day: "numeric" } : { year: "numeric", month: "long", day: "numeric" };
  return `${d.toLocaleDateString(state.locale, opts)} · ${wk}`;
}
export const localInput = ms => { const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000); return d.toISOString().slice(0, 16); };
export const localDay = date => localInput(date.getTime()).slice(0, 10);
/** The field shows minutes only: left as it was, the record keeps its own seconds and order within the minute. */
export const keptTime = (value, original) => (original != null && value === localInput(original) ? original : new Date(value).getTime());
export const epochDay = date => Math.floor(new Date(`${date}T00:00:00Z`).getTime() / 86400000);
export const wait = ms => new Promise(r => setTimeout(r, ms));

/* ---------- Buttons ---------- */
export function btn(label, onclick, kind = "tonal", icon = null, attrs = {}) {
  return el("button", { type: "button", class: `btn ${kind}`, onclick, ...attrs }, icon ? sym(icon, "sm") : null, label ? el("span", { class: "lbl", text: label }) : null);
}
export function iconBtn(icon, label, onclick, cls = "") {
  return el("button", { type: "button", class: `icon-btn ${cls}`, "aria-label": label, title: label, onclick }, sym(icon, "sm"));
}
/** Shows a spinner on the button while [task] runs; a second click meanwhile does nothing. */
export async function busy(button, task) {
  if (button.dataset.busy) return;
  button.dataset.busy = "1"; const old = [...button.childNodes];
  button.style.setProperty("min-width", `${button.offsetWidth}px`);
  button.replaceChildren(el("span", { class: "spinner" })); button.disabled = true;
  try { return await task(); } finally {
    button.replaceChildren(...old); button.disabled = false; delete button.dataset.busy; button.style.removeProperty("min-width");
  }
}

/* ---------- Layers: dialogs, menus and the palette close with Escape, newest first ---------- */
const layers = [];
export function pushLayer(close) { layers.push(close); return () => { const i = layers.indexOf(close); if (i >= 0) layers.splice(i, 1); }; }
export const hasLayers = () => layers.length > 0;
document.addEventListener("keydown", e => { if (e.key === "Escape" && layers.length) { e.preventDefault(); layers.at(-1)(); } });

/**
 * A dialog that does one thing. Closing with unsaved input asks first; while a save runs, or while its result is
 * still unknown, it does not close at all (the operation must be reconciled before anything else is sent).
 */
export function dialog({ title, sub = "", width = 520, body, foot = [], onClose }) {
  const prev = document.activeElement;
  let dirty = false;
  const head = el("div", { class: "dialog-head" }, el("div", { class: "title" }, el("h2", { id: "dlg-title", text: title }), sub ? el("p", { class: "sub", text: sub }) : null),
    iconBtn("close", t("close"), () => close()));
  const error = el("p", { class: "dialog-error", role: "alert" });
  const footer = el("div", { class: "dialog-foot" }, error, el("span", { class: "grow" }), ...foot);
  const box = el("div", { class: "dialog", role: "dialog", "aria-modal": "true", "aria-labelledby": "dlg-title", css: { "--w": `${width}px` } },
    head, el("div", { class: "dialog-body" }, body), footer);
  const scrim = el("div", { class: "scrim", onmousedown: e => { if (e.target === scrim) close(); } }, box);
  // Pickers and controls that save at once are marked data-nodirty: leaving them never loses a draft.
  const markDirty = e => { if (!e.target.closest?.("[data-nodirty]")) dirty = true; };
  box.addEventListener("input", markDirty); box.addEventListener("change", markDirty);
  box.addEventListener("keydown", e => {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { const p = $(".btn.primary:not(:disabled)", footer); if (p) { e.preventDefault(); p.click(); } }
    if (e.key !== "Tab") return;
    const f = $$("button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),summary,[tabindex='0']", box).filter(n => n.getClientRects().length);
    if (!f.length) return;
    if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f.at(-1).focus(); }
    else if (!e.shiftKey && document.activeElement === f.at(-1)) { e.preventDefault(); f[0].focus(); }
  });
  let removeLayer;
  const api = {
    root: box, error, footer, pending: null, saving: false,
    get dirty() { return dirty; }, set dirty(v) { dirty = v; },
    get open() { return scrim.isConnected; },
    close: force => close(force),
    fail(message) { error.textContent = message; },
  };
  async function close(force = false) {
    if (!force && (api.saving || api.pending)) { if (api.pending) error.textContent = t("unknownResult"); return false; }
    if (!force && dirty && !(await confirmDiscard())) return false;
    removeLayer(); scrim.remove(); onClose?.(); openDialogs.delete(api);
    prev?.focus?.({ preventScroll: true });
    if (!openDialogs.size) hooks.dialogsClosed?.();
    return true;
  }
  removeLayer = pushLayer(() => close());
  openDialogs.add(api);
  document.body.append(scrim);
  requestAnimationFrame(() => ($("[autofocus]", box) || $("input:not([readonly]):not([type=checkbox]):not([type=file]),select,textarea", box) || $(".btn.primary", footer))?.focus());
  return api;
}
const openDialogs = new Set();
export const dialogsOpen = () => openDialogs.size > 0;
/** Closes every dialog at once (the session ended): nothing is saved and nothing asks. */
export function closeAllDialogs() { for (const d of [...openDialogs]) { d.saving = false; d.pending = null; d.close(true); } }

export function confirmDiscard() {
  return confirmDialog({ title: t("discard"), message: t("discardMessage"), confirm: t("discardConfirm"), danger: true });
}
export function confirmDialog({ title, message, confirm = t("ok"), danger = false }) {
  return new Promise(resolve => {
    let done = false;
    const finish = v => { if (done) return; done = true; d.close(true); resolve(v); };
    const d = dialog({ title, width: 400, body: el("p", { class: "muted", text: message }), onClose: () => { if (!done) { done = true; resolve(false); } },
      foot: [btn(t("cancel"), () => finish(false), "ghost"), btn(confirm, () => finish(true), danger ? "danger tonal" : "primary", null, { autofocus: true })] });
  });
}

export function menu(anchor, items) {
  const r = anchor.getBoundingClientRect();
  const box = el("div", { class: "menu", role: "menu" }, items.map(it => it === "-" ? el("hr") :
    el("button", { type: "button", role: "menuitem", class: it.danger ? "danger" : "", onclick: () => { close(); it.run(); } }, it.icon ? sym(it.icon, "sm") : null, it.label)));
  document.body.append(box);
  const w = box.offsetWidth, h = box.offsetHeight;
  box.style.setProperty("left", `${Math.max(8, Math.min(innerWidth - w - 8, r.right - w))}px`);
  box.style.setProperty("top", `${r.bottom + 6 + h > innerHeight ? Math.max(8, r.top - h - 6) : r.bottom + 6}px`);
  const away = e => { if (!box.contains(e.target)) close(); };
  setTimeout(() => document.addEventListener("mousedown", away), 0);
  const remove = pushLayer(() => close());
  function close() { remove(); box.remove(); document.removeEventListener("mousedown", away); anchor.focus?.(); }
  box.addEventListener("keydown", e => {
    const b = $$("button", box), i = b.indexOf(document.activeElement);
    if (e.key === "ArrowDown") { e.preventDefault(); b[(i + 1) % b.length].focus(); }
    if (e.key === "ArrowUp") { e.preventDefault(); b[(i - 1 + b.length) % b.length].focus(); }
  });
  $("button", box)?.focus();
}

export function toast(message, ms = 3600) {
  if (document.body.dataset.ended) return;
  let host = $(".toasts"); if (!host) { host = el("div", { class: "toasts", role: "status", "aria-live": "polite" }); document.body.append(host); }
  const node = el("div", { class: "toast" }, el("span", { text: message }));
  host.append(node); setTimeout(() => node.remove(), ms);
}

/* ---------- Form controls ---------- */
let fieldSeq = 0;
export function field(label, control, { hint = "", full = false, affix = "" } = {}) {
  const id = control.id || `f${++fieldSeq}`; control.id = id;
  return el("div", { class: `fld ${full ? "full" : ""}` }, el("label", { for: id, text: label }),
    affix ? el("div", { class: "affix" }, control, el("span", { text: affix })) : control, hint ? el("p", { class: "hint", text: hint }) : null);
}
export const input = (name, value = "", attrs = {}) => el("input", { name, value: value ?? "", autocomplete: "off", ...attrs });
export function textarea(name, value = "", attrs = {}) { const n = el("textarea", { name, rows: 3, maxlength: 2000, ...attrs }); n.value = value ?? ""; return n; }
export function select(name, options, value) {
  const s = el("select", { name }, options.map(o => el("option", { value: o.value, text: o.label, disabled: o.disabled })));
  s.value = String(value ?? options[0]?.value ?? ""); return s;
}
let toggleSeq = 0;
/** A switch named by its title alone; the hint, when given, is read as its description. */
export function toggle(label, name, checked, hintText = "") {
  const hintId = hintText ? `switch-hint-${++toggleSeq}` : null;
  return el("label", { class: "switch-row" }, el("span", { class: "t" }, el("div", { text: label }), hintText ? el("small", { id: hintId, text: hintText }) : null),
    el("span", { class: "switch" }, el("input", { type: "checkbox", name, checked, role: "switch", "aria-label": label,
      "aria-describedby": hintId }), el("i")));
}
export function seg(options, value, onchange, cls = "") {
  const box = el("div", { class: `seg ${cls}`, role: "radiogroup" });
  const paint = v => $$("button", box).forEach(b => b.setAttribute("aria-pressed", String(b.dataset.v === String(v))));
  options.forEach(o => box.append(el("button", { type: "button", "data-v": o.value, role: "radio", "aria-label": o.title || o.label,
    onclick: () => { paint(o.value); box.value = o.value; onchange?.(o.value); } }, o.icon ? sym(o.icon, "sm") : null, o.label ? el("span", { text: o.label }) : null)));
  box.value = value; paint(value);
  return box;
}
/** Decimal check with the currency's fraction digits, as the server validates. */
export function validDecimal(value, { signed = false, scale = 2, positive = false } = {}) {
  const v = String(value).trim().replaceAll(",", "");
  if (!(signed ? /^-?\d+(\.\d+)?$/ : /^\d+(\.\d+)?$/).test(v)) return false;
  if ((v.split(".")[1]?.replace(/0+$/, "").length || 0) > scale) return false;
  return !positive || /[1-9]/.test(v);
}
export function markInvalid(control, message) {
  control.setAttribute("aria-invalid", "true");
  const f = control.closest(".fld"); f && $(".err", f)?.remove();
  f?.append(el("p", { class: "err", text: message }));
  control.addEventListener("input", () => { control.removeAttribute("aria-invalid"); f && $(".err", f)?.remove(); }, { once: true });
  control.focus();
  return false;
}
