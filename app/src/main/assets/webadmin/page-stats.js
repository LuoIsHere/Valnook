import { api, state, t, typeLabel } from "./core.js";
import { decAdd } from "./decimal.js";
import { el, money, fmt, gradient, compact, seg, tone, when, pctText, pct, paren } from "./ui.js";
import { pageHead, chartDefs, kpi } from "./layout.js";

const B = () => state.session?.baseCurrency;
let chartSeq = 0;

/** Net worth over a range, what it is made of now, and how it is spread. */
export async function statsPage() {
  const [s, dist] = await Promise.all([api("/api/v1/statistics/summary"), api("/api/v1/statistics/distribution")]);
  const sum = key => decAdd(...state.accounts.map(a => a[key] ?? "0"));
  const head = pageHead(t("statistics"), [`${t("asOf").replace("{when}", when(Date.now()))} · ${t("inBase").replace("{c}", B() || "—")}`], [], "Insights");
  const unrealized = sum("unrealized");
  const kpis = el("div", { class: "kpis" },
    kpi(t("availableCash"), money(s.availableCash, B(), { count: true })),
    kpi(t("deposits"), money(sum("deposits"), B(), { count: true })),
    kpi(t("investmentValue"), money(s.investmentValue, B(), { count: true }), el("span", { class: tone(unrealized) }, `${t("unrealizedShort")} `, el("span", { class: "mask", text: fmt(unrealized, B(), { sign: true }) }))),
    kpi(t("creditDebt"), money(sum("creditBalance"), B(), { tone: true, count: true })));
  return [head, el("div", { class: "page" }, trendCard(s), el("div", { class: "spaced-k" }, kpis),
    s.complete === false ? el("p", { class: "incomplete-note", text: t("incomplete") }) : null,
    el("div", { class: "section-head reveal spaced-l" }, el("h2", { class: "h22", text: t("distribution") })),
    el("div", { class: "dist-grid" }, distCard(t("byAccount"), dist.accounts.filter(v => Number(v.value) > 0), t("negativeLeftOut")),
      distCard(t("byAssetClass"), dist.assetTypes.map(v => ({ ...v, label: typeLabel(v.label) }))), distCard(t("byCurrency"), dist.currencies)))];
}

/* ---------- Net worth trend ---------- */
const RANGES = ["1m", "3m", "6m", "1y", "all"];
/** Daily points for the last [n] months, or monthly ones for a year or everything; days before the baseline are left out. */
async function series(range) {
  const now = new Date(), y = now.getFullYear(), m = now.getMonth();
  const daily = n => Promise.all(Array.from({ length: n }, (_, i) => new Date(y, m - (n - 1 - i), 1))
    .map(d => api(`/api/v1/statistics/history?granularity=DAILY&metric=TOTAL_ASSETS&year=${d.getFullYear()}&month=${d.getMonth() + 1}`)));
  const monthly = years => Promise.all(years.map(year => api(`/api/v1/statistics/history?granularity=MONTHLY&metric=TOTAL_ASSETS&year=${year}`)));
  let pages;
  if (range === "1m") pages = await daily(1);
  else if (range === "3m") pages = await daily(3);
  else if (range === "6m") pages = await daily(6);
  else if (range === "1y") pages = await monthly([y - 1, y]);
  else {
    // Year by year back until a year has no recorded value (at most twenty).
    pages = [];
    for (let year = y; year > y - 20; year--) {
      const page = (await monthly([year]))[0];
      if (!page.items.some(p => p.reliable && p.value != null)) break;
      pages.unshift(page);
    }
  }
  let points = pages.flatMap(p => p.items).filter(p => p.reliable && p.value != null && !p.future)
    .map(p => ({ t: new Date(`${p.date.length === 7 ? `${p.date}-01` : p.date}T00:00:00`).getTime(), v: Number(p.value), text: p.value }));
  if (range === "1y") points = points.slice(-12);
  return points;
}
function trendCard(s) {
  const id = `ch${++chartSeq}`;
  const wrap = el("div", { class: "chart-wrap", tabindex: "0", role: "img", "aria-label": t("trendAria") });
  const delta = el("div", { class: "hero-delta" });
  const labels = { "1m": t("range1m"), "3m": t("range3m"), "6m": t("range6m"), "1y": t("range1y"), all: t("rangeAll") };
  const card = el("section", { class: "tile chart-card" },
    el("div", { class: "chart-top" },
      el("div", {}, el("div", { class: "tile-k", text: t("totalAssets") }), gradient(el("div", { class: "hero-num" }, money(s.totalAssets, B(), { count: true }))), delta),
      seg(RANGES.map(v => ({ value: v, label: labels[v] })), state.range, v => { state.range = v; load(true); })), wrap);
  let points = [], loadSeq = 0;
  async function load(animate) {
    const seqNo = ++loadSeq;
    const got = await series(state.range).catch(() => null);
    if (seqNo !== loadSeq) return;
    points = got || [];
    if (points.length < 2) { delta.replaceChildren(); wrap.replaceChildren(el("div", { class: "empty", text: t("notEnoughHistory") })); return; }
    const first = points[0].v, last = points.at(-1).v, diff = last - first;
    delta.replaceChildren(el("span", { class: `tag ${diff >= 0 ? "pos" : "neg"}` },
      el("span", { class: "mask", text: `${fmt(diff.toFixed(2), B(), { sign: true })}${first ? paren(`${diff >= 0 ? "+" : ""}${pctText(diff / Math.abs(first) * 100)}`) : ""}` })), t("rangeChange"));
    draw(animate);
  }
  function draw(animate) {
    const W = wrap.clientWidth || 800, H = wrap.clientHeight || 320, L = 62, R = 14, T = 16, Bm = 28;
    const vs = points.map(p => p.v); let min = Math.min(...vs), max = Math.max(...vs);
    const pad = (max - min) * .08 || Math.abs(max) * .05 || 1; min -= pad; max += pad;
    const step = niceStep((max - min) / 4), lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
    const x = i => L + i / Math.max(1, points.length - 1) * (W - L - R), y = v => T + (hi - v) / (hi - lo || 1) * (H - T - Bm);
    const line = points.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(p.v).toFixed(1)}`).join("");
    const lastX = x(points.length - 1), lastY = y(points.at(-1).v);
    const svg = el("svg", { viewBox: `0 0 ${W} ${H}`, width: W, height: H }, chartDefs(id),
      el("g", { class: "grid" }, ticks(lo, hi, step).map(v => el("line", { x1: L, x2: W - R, y1: y(v), y2: y(v) }))),
      el("g", { class: "axis" }, ticks(lo, hi, step).map(v => el("text", { x: L - 12, y: y(v) + 4, "text-anchor": "end", class: "mask" }, compact(v, B()))),
        xLabels(points).map(i => el("text", { x: x(i), y: H - 6, "text-anchor": i === 0 ? "start" : i === points.length - 1 ? "end" : "middle" }, shortDate(points[i].t)))),
      // "draw" plays only as the page opens; "draw-now" replays when the range is switched.
      el("g", { class: animate ? "draw-now" : "draw" },
        el("path", { d: `${line}L${lastX},${H - Bm}L${L},${H - Bm}Z`, fill: `url(#${id}-f)` }),
        el("path", { class: "glow", d: line, stroke: `url(#${id}-s)`, filter: `url(#${id}-b)` }),
        el("path", { class: "line", d: line, stroke: `url(#${id}-s)` }),
        el("circle", { class: "halo", cx: lastX, cy: lastY, r: 10 }, el("animate", { attributeName: "r", values: "6;14;6", dur: "2.4s", repeatCount: "indefinite" }),
          el("animate", { attributeName: "opacity", values: ".35;0;.35", dur: "2.4s", repeatCount: "indefinite" })),
        el("circle", { class: "last-dot", cx: lastX, cy: lastY, r: 4 })),
      el("line", { class: "cross", x1: 0, x2: 0, y1: T, y2: H - Bm, visibility: "hidden" }),
      el("circle", { class: "dot", r: 5, visibility: "hidden" }));
    const tip = el("div", { class: "tip", hidden: true });
    wrap.replaceChildren(svg, tip);
    let at = -1;
    const show = i => {
      at = Math.max(0, Math.min(points.length - 1, i)); const p = points[at], px = x(at), py = y(p.v);
      const cross = svg.querySelector(".cross"), dot = svg.querySelector(".dot");
      cross.setAttribute("x1", px); cross.setAttribute("x2", px); cross.setAttribute("visibility", "visible");
      dot.setAttribute("cx", px); dot.setAttribute("cy", py); dot.setAttribute("visibility", "visible");
      const ch = p.v - points[0].v;
      tip.replaceChildren(el("span", { class: "faint", text: when(p.t, "date") }), el("strong", { class: "mask", text: fmt(p.text, B()) }),
        el("span", { class: `mask ${tone(ch)}`, text: `${t("sinceStart")} ${fmt(ch.toFixed(2), B(), { sign: true })}` }));
      tip.hidden = false; tip.style.setProperty("left", `${Math.max(100, Math.min(W - 100, px))}px`); tip.style.setProperty("top", `${py}px`);
    };
    const hide = () => { at = -1; tip.hidden = true; svg.querySelectorAll(".cross,.dot").forEach(n => n.setAttribute("visibility", "hidden")); };
    wrap.onmousemove = e => { const r = wrap.getBoundingClientRect(); show(Math.round((e.clientX - r.left - L) / (W - L - R) * (points.length - 1))); };
    wrap.onmouseleave = hide; wrap.onblur = hide;
    wrap.onkeydown = e => {
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") { e.preventDefault(); show((at < 0 ? points.length - 1 : at) + (e.key === "ArrowLeft" ? -1 : 1) * (e.shiftKey ? 7 : 1)); }
      if (e.key === "Home") show(0); if (e.key === "End") show(points.length - 1);
    };
  }
  let first = true;
  new ResizeObserver(() => { if (first) { first = false; return; } if (points.length > 1) draw(false); }).observe(wrap);
  load(false);
  return card;
}
function niceStep(raw) { const p = 10 ** Math.floor(Math.log10(raw || 1)), f = raw / p; return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p; }
function ticks(lo, hi, step) { const out = []; for (let v = lo; v <= hi + step / 2; v += step) out.push(v); return out; }
function xLabels(points) { const n = points.length, k = 5; return [...new Set(Array.from({ length: k }, (_, i) => Math.round(i * (n - 1) / (k - 1))))]; }
function shortDate(ms) { const d = new Date(ms); return `${d.getFullYear() !== new Date().getFullYear() ? `${d.getFullYear()}/` : ""}${d.getMonth() + 1}/${d.getDate()}`; }

/* ---------- Distribution ---------- */
export function distCard(title, items, note = "") {
  const total = items.reduce((a, v) => a + Math.max(0, Number(v.value)), 0) || 1;
  const sorted = [...items].sort((a, b) => Number(b.value) - Number(a.value));
  const R = 46, C = 2 * Math.PI * R;
  let offset = 0;
  const center = el("text", { x: 60, y: 62 }, t("itemCount").replace("{n}", sorted.length)), centerLabel = el("text", { x: 60, y: 77, class: "lbl" }, t("total"));
  const segs = sorted.map((v, i) => {
    const len = Math.max(0, Number(v.value)) / total * C;
    const c = el("circle", { class: "seg", cx: 60, cy: 60, r: R, stroke: `var(--cat-${Math.min(i + 1, 6)})`, "stroke-dasharray": `${Math.max(0.1, len - 4)} ${C}`,
      "stroke-dashoffset": -offset, transform: "rotate(-90 60 60)", css: { "animation-delay": `${.3 + i * .08}s` } });
    offset += len; return c;
  });
  const focus = i => {
    segs.forEach((g, j) => { g.style.setProperty("opacity", i == null || i === j ? "1" : ".25"); g.style.setProperty("stroke-width", i === j ? "16" : ""); });
    rows.forEach((r, j) => r.classList.toggle("on", i === j));
    center.textContent = i == null ? t("itemCount").replace("{n}", sorted.length) : pctText(pct(sorted[i].value, total));
    centerLabel.textContent = i == null ? t("total") : sorted[i].label;
  };
  const rows = sorted.map((v, i) => el("div", { tabindex: "0", onmouseenter: () => focus(i), onmouseleave: () => focus(null), onfocus: () => focus(i), onblur: () => focus(null) },
    el("span", { class: "sw", css: { background: `var(--cat-${Math.min(i + 1, 6)})` } }), el("span", { class: "lbl", title: v.label, text: v.label }),
    el("span", { class: "pct num", text: pctText(pct(Math.max(0, Number(v.value)), total)) })));
  segs.forEach((g, i) => { g.addEventListener("mouseenter", () => focus(i)); g.addEventListener("mouseleave", () => focus(null)); });
  return el("section", { class: "tile dist" }, el("h3", { text: title }), note ? el("p", { class: "faint small", text: note }) : null,
    sorted.length ? el("div", { class: "donut-row" }, el("svg", { class: "donut", viewBox: "0 0 120 120", role: "img", "aria-label": title },
      el("circle", { cx: 60, cy: 60, r: R, stroke: "var(--fill)" }), ...segs, center, centerLabel), el("div", { class: "dist-list" }, rows))
      : el("p", { class: "empty", text: t("empty") }));
}
