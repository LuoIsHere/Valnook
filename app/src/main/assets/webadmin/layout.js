// Page structure: headers, the list-and-detail split, tabs, small charts.
import { t } from "./core.js";
import { el, iconBtn, sym, $, pushLayer } from "./ui.js";
import { go } from "./router.js";

export function pageHead(title, sub, actions = [], eyebrow = "") {
  return el("header", { class: "page-head" },
    el("div", {}, eyebrow ? el("span", { class: "eyebrow", text: eyebrow }) : null, el("h1", { text: title }), sub ? el("p", { class: "sub" }, sub) : null),
    el("div", { class: "head-actions" }, actions));
}

/**
 * The detail panel sits beside the list on wide screens and slides over it on narrow ones. Closing it returns to the
 * list route, so the browser's Back button, Escape and the close button all do the same.
 */
let removeDetailLayer = null;
export function detailPanel({ back, avatar = null, title, sub = "", actions = [], total = null, body }) {
  removeDetailLayer?.();
  const close = () => go(back);
  removeDetailLayer = pushLayer(close);
  const panel = el("aside", { class: "detail", "aria-label": title },
    el("div", { class: "detail-head" }, avatar, el("div", { class: "title" }, el("h2", { text: title }), sub ? el("p", { class: "sub" }, sub) : null),
      ...actions, iconBtn("close", t("close"), close)),
    total ? el("div", { class: "detail-total" }, total) : null,
    el("div", { class: "detail-body" }, body));
  return [el("div", { class: "detail-scrim", onclick: close }), panel];
}
export function clearDetailLayer() { removeDetailLayer?.(); removeDetailLayer = null; }

export function split(list, detail) {
  return el("div", { class: `split ${detail ? "open" : ""}` }, el("div", { class: "split-main" }, list), detail);
}

let gradSeq = 0;
/** Gradient definitions shared by the charts: a left-to-right stroke and a fading area fill. */
export function chartDefs(id) {
  const stop = (offset, color, opacity = 1) => el("stop", { offset, "stop-color": `var(${color})`, "stop-opacity": opacity });
  return el("defs", {},
    el("linearGradient", { id: `${id}-s`, x1: 0, x2: 1, y1: 0, y2: 0 }, stop("0", "--accent", .55), stop(".7", "--accent"), stop("1", "--accent-2")),
    el("linearGradient", { id: `${id}-f`, x1: 0, x2: 0, y1: 0, y2: 1 }, stop("0", "--accent", .28), stop("1", "--accent", 0)),
    el("filter", { id: `${id}-b`, x: "-10%", y: "-50%", width: "120%", height: "200%" }, el("feGaussianBlur", { stdDeviation: "4" })));
}
export function sparkline(values) {
  if (values.length < 2) return el("div");
  const id = `sp${++gradSeq}`;
  const min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  const xy = values.map((v, i) => [i / (values.length - 1) * 100, 44 - (v - min) / span * 36]);
  const d = xy.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(2)},${y.toFixed(2)}`).join("");
  return el("svg", { class: "spark draw", viewBox: "0 0 100 50", preserveAspectRatio: "none", "aria-hidden": "true" }, chartDefs(id),
    el("path", { d: `${d}L100,50L0,50Z`, fill: `url(#${id}-f)` }),
    el("path", { class: "glow", d, stroke: `url(#${id}-s)` }),
    el("path", { class: "line", d, stroke: `url(#${id}-s)` }));
}

/** Underline tabs; arrow keys move between them. */
export function tabBar(tabs, value, onchange) {
  const bar = el("div", { class: "tabs", role: "tablist" }, tabs.map(([key, label, n]) => el("button", { type: "button", role: "tab", "data-key": key,
    "aria-selected": String(key === value), tabindex: key === value ? "0" : "-1", onclick: () => onchange(key) },
    label, n != null ? el("span", { class: "n", text: String(n) }) : null)));
  bar.addEventListener("keydown", e => {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    const i = tabs.findIndex(x => x[0] === value), next = tabs[(i + (e.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length][0];
    onchange(next); requestAnimationFrame(() => document.querySelector(`.tabs [data-key="${next}"]`)?.focus());
  });
  return bar;
}

export function emptyState(icon, text) { return el("div", { class: "empty" }, sym(icon), el("p", { text })); }

/** A list row that opens with a click, Enter or Space. */
export function rowKeys(row, open) {
  row.tabIndex = 0;
  row.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(); } });
  row.addEventListener("click", open);
  return row;
}
export const focusMain = () => $("#main")?.focus({ preventScroll: true });

export function kpi(label, value, extra = null) {
  return el("div", { class: "tile kpi" }, el("div", { class: "k", text: label }), el("div", { class: "v" }, value), extra ? el("div", { class: "d" }, extra) : null);
}
export function kv(rows) {
  return el("div", { class: "kv" }, rows.filter(Boolean).map(([k, v, full]) => el("div", { class: full ? "full" : "" },
    el("div", { class: "k", text: k }), el("div", { class: "v small-v" }, v))));
}
