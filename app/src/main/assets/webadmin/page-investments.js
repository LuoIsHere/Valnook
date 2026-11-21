import { api, state, t, typeLabel, hooks } from "./core.js";
import { decAdd, decSub, priceUnits } from "./decimal.js";
import { el, sym, money, fmt, gradient, btn, iconBtn, dialog, field, input, select, toggle, seg, menu, toast, validDecimal, markInvalid, digitsOf,
  $, $$, when, relative, localInput, keptTime, confirmDialog, confirmDiscard, tone, pct, pctText, qtyText, paren } from "./ui.js";
import { go, render } from "./router.js";
import { pageHead, detailPanel, split, emptyState, rowKeys, tabBar, kpi, kv, chartDefs } from "./layout.js";
import { submit, write, errorMessage } from "./forms.js";
import { avatar } from "./icons.js";

const B = () => state.session?.baseCurrency;
let priceEdit = null; // instrument id → typed price, while prices are edited in the table
const qty = v => qtyText(v);
const price = (v, c) => fmt(v, c, { digits: 5, min: 2 });

export async function investmentsPage(r) {
  const tab = r.a === "instruments" ? "instruments" : "positions", id = r.b ? Number(r.b) : null;
  const missing = e => (e.code === "NOT_FOUND" ? null : Promise.reject(e));
  const [detail, prices] = await Promise.all([id && tab === "positions" && !priceEdit ? api(`/api/v1/positions/${id}`).catch(missing) : null,
    id && tab === "instruments" && !priceEdit ? api(`/api/v1/instruments/${id}/prices`).then(r => r.items).catch(missing) : null]);
  const held = state.positions.filter(p => Number(p.quantity) > 0);
  const sum = key => decAdd(...state.accounts.map(a => a[key] ?? "0"));
  const value = sum("investments"), unrealized = sum("unrealized"), realized = sum("realized"), cost = decSub(value, unrealized);
  const latest = Math.max(0, ...state.instruments.map(i => i.priceUpdatedAtMs || 0));
  const actions = tab === "positions" ? [btn(t("newTrade"), () => tradeDialog(null, null), "primary", "plus")]
    : priceEdit ? [] : [btn(t("assetTypes"), () => assetTypesDialog(), "tonal", "layers"),
      btn(t("updatePrices"), startPriceEdit, "tonal", "pen", { disabled: !state.instruments.length }), btn(t("newInstrument"), () => instrumentDialog(null), "primary", "plus")];
  const head = pageHead(t("investments"), [[t("positionCount").replace("{n}", held.length), t("instrumentCountSub").replace("{n}", state.instruments.length),
    latest ? t("pricesUpdated").replace("{when}", relative(latest)) : null].filter(Boolean).join(" · ")], actions, "Investments");
  const kpis = el("div", { class: "kpis" },
    kpi(t("investmentValue"), gradient(el("span", {}, money(value, B(), { count: true })))),
    kpi(t("unrealized"), money(unrealized, B(), { sign: true, tone: true, count: true }),
      Number(cost) ? el("span", { class: `tag ${Number(unrealized) >= 0 ? "pos" : "neg"}`, text: `${Number(unrealized) >= 0 ? "+" : ""}${pctText(pct(unrealized, cost))}` }) : null),
    kpi(t("realized"), money(realized, B(), { sign: true, tone: true, count: true }), el("span", { class: "faint", text: t("includesDividends") })),
    kpi(t("holdingCost"), money(cost, B(), { count: true })));
  const tabs = tabBar([["positions", t("positions"), held.length], ["instruments", t("instruments"), state.instruments.length]], tab, k => { if (!priceEdit) go(`investments/${k}`); });
  const main = el("div", {}, kpis, el("div", { class: "reveal spaced-l" }, tabs, tab === "positions" ? positionGroups(id) : instrumentList(id)));
  const panel = priceEdit || !id ? null : tab === "positions" ? (detail ? positionDetail(detail) : null) : instrumentDetail(id, prices || []);
  return [head, el("div", { class: "page" }, split(main, panel))];
}

/* ---------- Positions ---------- */
function positionGroups(selected) {
  const box = el("div", {});
  for (const a of state.accounts) {
    // As in the app: an account appears while it holds or held something; positions whose trades were all deleted are hidden.
    const ps = state.positions.filter(p => p.accountId === a.id).sort((x, y) => y.lastActivityAtMs - x.lastActivityAtMs || x.id - y.id);
    const held = ps.filter(p => Number(p.quantity) > 0), closed = ps.filter(p => p.closed === true);
    if (!held.length && !closed.length) continue;
    const collapsed = state.invCollapsed.has(a.id);
    const list = el("div", { class: "list" },
      el("button", { type: "button", class: "inv-group-head", "aria-expanded": String(!collapsed),
        onclick: () => { if (collapsed) state.invCollapsed.delete(a.id); else state.invCollapsed.add(a.id); render(); } },
        sym("chevron_down", "sm chev"), avatar(a.icon, "sm"), el("strong", { text: a.name }),
        el("div", { class: "stats" },
          el("div", { class: "opt" }, el("div", { class: "k", text: t("realizedShort") }), el("div", { class: "v" }, money(a.realized, B(), { sign: true, tone: true }))),
          el("div", { class: "opt" }, el("div", { class: "k", text: t("unrealized") }), el("div", { class: "v" }, money(a.unrealized, B(), { sign: true, tone: true }))),
          el("div", {}, el("div", { class: "k", text: t("marketValue") }), el("div", { class: "v" }, money(a.investments, B()))))));
    if (!collapsed) {
      list.append(el("div", { class: "list-head cols-pos" }, el("span", { text: t("nameCode") }), el("span", { class: "r opt", text: t("quantity") }),
        el("span", { class: "r opt", text: t("priceCost") }), el("span", { class: "r opt", text: t("unrealized") }), el("span", { class: "r", text: t("marketValue") })));
      held.forEach(p => list.append(positionRow(p, selected)));
      if (!held.length) list.append(el("p", { class: "faint small pad", text: t("nothingHeld") }));
      if (closed.length) {
        const open = state.closedOpen.has(a.id);
        list.append(el("button", { type: "button", class: "closed-toggle", "aria-expanded": String(open),
          onclick: () => { if (open) state.closedOpen.delete(a.id); else state.closedOpen.add(a.id); render(); } },
          sym(open ? "chevron_down" : "chevron_right", "sm"), `${t("closedPositions")} ${closed.length}`));
        if (open) closed.forEach(p => list.append(positionRow(p, selected)));
      }
    }
    box.append(el("section", { class: "inv-group" }, list));
  }
  if (!box.children.length) box.append(el("div", { class: "list" }, emptyState("investments", t("noPositions"))));
  return box;
}
function positionRow(p, selected) {
  const c = p.currencyCode, holding = Number(p.quantity) > 0;
  return rowKeys(el("div", { class: "row cols-pos", "data-key": String(p.id), "aria-selected": String(p.id === selected) },
    el("div", { class: "ident" }, el("div", {}, el("strong", { text: p.name }), el("div", { class: "sub" }, `${p.symbol} · ${c}`, holding ? "" : ` · ${t("closed")}`))),
    el("span", { class: "r opt num", text: holding ? qty(p.quantity) : "—" }),
    el("span", { class: "r opt cell-2" }, el("div", { class: "num", text: price(p.currentPrice, c) }), el("div", { class: "sub num", text: holding && p.averageCost != null ? price(p.averageCost, c) : "—" })),
    el("span", { class: "r opt cell-2" }, holding && p.unrealized != null ? money(p.unrealized, c, { sign: true, tone: true }) : el("span", { class: "faint", text: "—" }),
      el("div", { class: `sub num ${tone(p.unrealized)}`, text: p.unrealizedPercent == null ? "" : `${Number(p.unrealizedPercent) >= 0 ? "+" : ""}${pctText(Number(p.unrealizedPercent), 2)}` })),
    el("span", { class: "r cell-2" }, el("strong", {}, money(p.marketValue, c)), holding ? null : el("div", { class: "sub" }, `${t("realizedShort")} `, money(p.realized, c, { sign: true, tone: true })))),
  () => go(selected === p.id ? "investments/positions" : `investments/positions/${p.id}`));
}
function positionDetail(data) {
  const p = data.position, c = p.currencyCode, a = state.accounts.find(v => v.id === p.accountId);
  const ins = state.instruments.find(i => i.id === p.instrumentId);
  const list = el("div", {}, data.trades.map(x => tradeRow(p, x)));
  let cursor = data.nextCursor;
  const more = btn(t("loadMore"), async () => {
    const page = await api(`/api/v1/positions/${p.id}/trades?pageSize=100&cursor=${encodeURIComponent(cursor)}`);
    list.append(...page.items.map(x => tradeRow(p, x))); cursor = page.nextCursor; more.hidden = !cursor;
  }, "ghost sm");
  more.hidden = !cursor;
  const body = el("div", {},
    kv([[t("heldQuantity"), qty(p.quantity)], [t("currentPrice"), el("span", {}, price(p.currentPrice, c), ins?.priceUpdatedAtMs ? el("div", { class: "k", text: t("updatedWhen").replace("{when}", relative(ins.priceUpdatedAtMs)) }) : null)],
      [t("averageCost"), p.averageCost != null && Number(p.quantity) > 0 ? price(p.averageCost, c) : "—"], [t("holdingCost"), money(p.remainingCost, c)],
      [t("unrealized"), money(p.unrealized, c, { sign: true, tone: true })], [t("realized"), money(p.realized, c, { sign: true, tone: true })]]),
    p.chronologyValid === false ? el("div", { class: "callout spaced" }, sym("alert", "sm"), el("div", { text: t("chronologyInvalid") })) : null,
    el("div", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("tradeRecords") }), btn(t("newTrade"), () => tradeDialog(p, null), "tonal sm", "plus")),
      list, el("div", { class: "load-more" }, more)));
  const menuBtn = iconBtn("more", t("moreActions"), e => menu(e.currentTarget, [
    ins ? { label: t("editInstrument"), icon: "pen", run: () => instrumentDialog(ins) } : null,
    a ? { label: t("viewAccount").replace("{name}", a.name), icon: "accounts", run: () => go(`accounts/${a.id}`) } : null].filter(Boolean)));
  return detailPanel({ back: "investments/positions", title: p.name, sub: [`${p.symbol} · `, a ? el("a", { href: `#/accounts/${a.id}`, class: "link-btn", text: a.name }) : ""],
    actions: [menuBtn], body,
    total: el("div", {}, el("div", { class: "muted small", text: t("marketValue") }), el("div", { class: "big" }, money(p.marketValue, c)),
      Number(p.quantity) > 0 ? el("span", { class: `tag ${Number(p.unrealized) >= 0 ? "pos" : "neg"}` }, el("span", { class: "mask", text: fmt(p.unrealized, c, { sign: true }) }),
        p.unrealizedPercent == null ? "" : paren(`${Number(p.unrealizedPercent) >= 0 ? "+" : ""}${pctText(Number(p.unrealizedPercent), 2)}`)) : el("span", { class: "tag", text: t("closed") })) });
}
function tradeRow(p, x) {
  const c = p.currencyCode;
  if (x.action === "DIVIDEND") return el("div", { class: "mini-row", tabindex: "0", onclick: () => toast(t("dividendOnPhone")) },
    el("span", { class: "kind-icon dep" }, sym("dividend", "sm")),
    el("div", { class: "grow" }, el("strong", { text: t("dividend") }), el("div", { class: "sub", text: `${when(x.businessAtMs)} · ${t("phoneOnly")}` })),
    el("div", { class: "r" }, money(x.amount, c, { sign: true, tone: true })));
  const buy = x.action === "BUY";
  return rowKeys(el("div", { class: "mini-row" },
    el("span", { class: `kind-icon ${buy ? "buy" : "sell"}` }, sym(buy ? "plus" : "trade", "sm")),
    el("div", { class: "grow" }, el("strong", { text: `${buy ? t("buy") : t("sell")} ${qty(x.quantity)}` }),
      el("div", { class: "sub", text: `${when(x.businessAtMs)} · @${price(x.unitPrice, c)}${Number(x.fee) ? ` · ${t("fee")} ${fmt(x.fee, c)}` : ""}` })),
    el("div", { class: "r" }, money(buy ? `-${x.amount}` : x.amount, c, { sign: true }), x.cashLinked ? el("div", { class: "small faint", text: t("cashLinked") }) : null)),
  () => tradeDialog(p, x));
}

/** Opens a trade by id from the records page: older trades lie beyond the first page of the position. */
export async function openTrade(positionId, tradeId) {
  const data = await api(`/api/v1/positions/${positionId}`);
  let trade = data.trades.find(v => v.id === tradeId), cursor = data.nextCursor;
  while (!trade && cursor) {
    const page = await api(`/api/v1/positions/${positionId}/trades?pageSize=100&cursor=${encodeURIComponent(cursor)}`);
    trade = page.items.find(v => v.id === tradeId); cursor = page.nextCursor;
  }
  if (!trade) { toast(t("NOT_FOUND")); return; }
  tradeDialog(data.position, trade);
}

/* ---------- Instruments ---------- */
function instrumentList(selected) {
  const counts = new Map(state.assetTypes.map(x => [x.id, state.instruments.filter(i => i.typeId === x.id).length]));
  if (state.insType && !state.assetTypes.some(x => String(x.id) === state.insType)) state.insType = "";
  const chips = el("div", { class: "filters" },
    [{ id: "", name: `${t("all")} ${state.instruments.length}` }, ...state.assetTypes.filter(x => counts.get(x.id)).map(x => ({ id: String(x.id), name: `${typeLabel(x.name, x.isDefault)} ${counts.get(x.id)}` }))]
      .map(x => el("button", { type: "button", class: "chip", "aria-pressed": String(state.insType === x.id), onclick: () => { state.insType = x.id; render(); } }, x.name)));
  const items = state.instruments.filter(i => !state.insType || String(i.typeId) === state.insType);
  const list = el("div", { class: `list ${priceEdit ? "editing" : ""}` },
    el("div", { class: "list-head cols-ins" }, el("span", { text: t("nameCode") }), el("span", { class: "opt", text: t("assetType") }),
      el("span", { class: "opt", text: t("priceUpdated") }), el("span", { class: "r", text: priceEdit ? t("newPrice") : t("currentPrice") })));
  items.forEach(i => {
    const row = el("div", { class: "row cols-ins", "data-key": String(i.id), "aria-selected": String(i.id === selected) },
      el("div", { class: "ident" }, el("div", {}, el("strong", { text: i.name }), el("div", { class: "sub", text: `${i.symbol} · ${i.currencyCode}` }))),
      el("span", { class: "opt" }, el("span", { class: "tag", text: typeLabel(i.typeName) })),
      el("span", { class: "opt faint small", text: i.priceUpdatedAtMs ? relative(i.priceUpdatedAtMs) : "—" }),
      priceEdit ? priceCell(i) : el("span", { class: "r num strong", text: price(i.currentPrice, i.currencyCode) }));
    list.append(priceEdit ? row : rowKeys(row, () => go(selected === i.id ? "investments/instruments" : `investments/instruments/${i.id}`)));
  });
  if (!items.length) list.append(emptyState("statistics", state.instruments.length ? t("noInstrumentsOfType") : t("noInstruments")));
  const wrap = el("div", {}, chips, list);
  if (priceEdit) wrap.append(savebar());
  return wrap;
}
function priceCell(i) {
  const v = priceEdit.has(i.id) ? priceEdit.get(i.id) : i.currentPrice;
  const box = el("input", { class: `price-input ${priceEdit.has(i.id) ? "changed" : ""}`, value: v, inputmode: "decimal", maxlength: "64",
    "aria-label": t("newPriceOf").replace("{name}", i.name).replace("{currency}", i.currencyCode), "data-ins": String(i.id) });
  box.addEventListener("input", () => {
    if (priceUnits(box.value) === priceUnits(i.currentPrice)) priceEdit.delete(i.id); else priceEdit.set(i.id, box.value);
    box.classList.toggle("changed", priceEdit.has(i.id)); box.removeAttribute("aria-invalid");
    const n = $(".savebar .count"); if (n) n.textContent = priceEdit.size ? t("priceChanges").replace("{n}", priceEdit.size) : t("editPricesHint");
  });
  box.addEventListener("keydown", e => {
    if (e.key !== "Enter") return; e.preventDefault();
    const all = $$(".price-input"), next = all[all.indexOf(box) + (e.shiftKey ? -1 : 1)];
    if (next) { next.focus(); next.select(); } else $(".savebar .btn.primary")?.focus();
  });
  return el("span", { class: "r price-cell" }, box);
}
function savebar() {
  const generation = state.generation;
  const ok = btn(t("savePrices"), async () => {
    for (const [id, v] of priceEdit) if (priceUnits(v) === null) {
      const n = $(`.price-input[data-ins="${id}"]`); n?.setAttribute("aria-invalid", "true"); n?.focus(); toast(t("priceInvalid")); return;
    }
    if (!priceEdit.size) { priceEdit = null; render(); return; }
    // The batch is sent as one write; leaving edit mode waits until it went through, so an offline try keeps every price.
    const changes = [...priceEdit].map(([id, v]) => ({ instrumentId: id, expectedRevision: state.instruments.find(i => i.id === id).revision, price: v.trim() }));
    const n = changes.length, payload = write({ dataGeneration: generation, changes });
    // The batch goes out from a small dialog of its own, so a lost answer is checked like any other save.
    const send = () => submit(host, retry, "/api/v1/instrument-prices", "PUT", payload, { message: t("pricesSaved").replace("{n}", n) })
      // The save's own refresh waited for the price edit to end; with it ended, reload the prices now.
      .then(done => { if (done) { priceEdit = null; hooks.saved?.(); } });
    const retry = btn(t("savePrices"), send, "primary");
    const host = dialog({ title: t("savePrices"), width: 400, body: el("p", { class: "muted", text: t("priceChanges").replace("{n}", n) }),
      foot: [btn(t("cancel"), () => host.close(), "ghost"), retry] });
    await send();
  }, "primary", "check");
  return el("div", { class: "savebar", role: "region", "aria-label": t("updatePrices") },
    el("span", { class: "count", text: priceEdit.size ? t("priceChanges").replace("{n}", priceEdit.size) : t("editPricesHint") }),
    btn(t("cancel"), async () => { if (priceEdit.size && !(await confirmDiscard())) return; priceEdit = null; render(); }, "ghost"), ok);
}
function startPriceEdit() {
  priceEdit = new Map(); go("investments/instruments");
  render(); requestAnimationFrame(() => { const f = $(".price-input"); f?.focus(); f?.select(); });
}
export const isPriceEditing = () => !!priceEdit && priceEdit.size > 0;
export function cancelPriceEdit() { priceEdit = null; }

function instrumentDetail(id, prices) {
  const i = state.instruments.find(v => v.id === id); if (!i) return null;
  const holders = state.positions.filter(p => p.instrumentId === id && (Number(p.quantity) > 0 || p.closed));
  return detailPanel({ back: "investments/instruments", title: i.name, sub: `${i.symbol} · ${typeLabel(i.typeName)} · ${i.currencyCode}`,
    actions: [iconBtn("pen", t("editInstrument"), () => instrumentDialog(i))],
    total: el("div", {}, el("div", { class: "muted small", text: t("currentPrice") }), el("div", { class: "big num", text: price(i.currentPrice, i.currencyCode) }),
      el("span", { class: "faint small", text: i.priceUpdatedAtMs ? t("updatedWhen").replace("{when}", when(i.priceUpdatedAtMs)) : "" })),
    body: el("div", {},
      el("div", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("priceTrend") }),
        prices.length ? el("span", { class: "faint small", text: t("priceDays").replace("{n}", prices.length) }) : null), priceChart(prices, i.currencyCode)),
      el("div", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("heldIn") })),
        holders.length ? holders.map(p => { const a = state.accounts.find(v => v.id === p.accountId);
          return rowKeys(el("div", { class: "mini-row" }, avatar(a?.icon, "sm"),
            el("div", { class: "grow" }, el("strong", { text: a?.name || "—" }), el("div", { class: "sub", text: Number(p.quantity) > 0 ? qty(p.quantity) : t("closed") })),
            el("div", { class: "r" }, money(p.marketValue, i.currencyCode)), sym("chevron_right", "sm faint")), () => go(`investments/positions/${p.id}`)); })
          : el("p", { class: "faint small", text: t("noHolders") })),
      el("div", { class: "section" }, btn(t("newTradeFor"), () => tradeDialog(null, null, { instrumentId: i.id }), "tonal", "plus"))) });
}

let priceChartSeq = 0;
const shortDay = ms => new Date(ms).toLocaleDateString(state.locale, { month: "numeric", day: "numeric" });
/** The latest days' prices, placed by date as on the phone; hover or the arrow keys read a day's price. */
function priceChart(items, currency) {
  if (!items.length) return el("p", { class: "faint small", text: t("noPriceHistory") });
  const pts = items.map(p => ({ t: p.atMs, v: Number(p.price), text: p.price }));
  const id = `pc${++priceChartSeq}`;
  const wrap = el("div", { class: "chart-wrap price-chart", tabindex: "0", role: "img", "aria-label": t("priceTrendAria") });
  function draw() {
    const W = wrap.clientWidth || 440, H = wrap.clientHeight || 180, L = 10, R = 10, T = 14, Bm = 24;
    const vs = pts.map(p => p.v); let min = Math.min(...vs), max = Math.max(...vs);
    const pad = (max - min) * .12 || Math.abs(max) * .05 || 1; min -= pad; max += pad;
    const t0 = pts[0].t, span = pts.at(-1).t - t0;
    const x = p => span ? L + (p.t - t0) / span * (W - L - R) : W / 2, y = v => T + (max - v) / (max - min) * (H - T - Bm);
    const line = pts.map((p, i) => `${i ? "L" : "M"}${x(p).toFixed(1)},${y(p.v).toFixed(1)}`).join("");
    const last = pts.at(-1), lx = x(last), ly = y(last.v);
    const svg = el("svg", { viewBox: `0 0 ${W} ${H}`, width: W, height: H }, chartDefs(id),
      el("g", { class: "axis" }, el("text", { x: pts.length > 1 ? L : W / 2, y: H - 6, "text-anchor": pts.length > 1 ? "start" : "middle" }, shortDay(pts[0].t)),
        pts.length > 1 ? el("text", { x: W - R, y: H - 6, "text-anchor": "end" }, shortDay(last.t)) : null),
      el("g", { class: "draw" },
        pts.length > 1 ? el("path", { d: `${line}L${lx},${H - Bm}L${x(pts[0])},${H - Bm}Z`, fill: `url(#${id}-f)` }) : null,
        pts.length > 1 ? el("path", { class: "glow", d: line, stroke: `url(#${id}-s)`, filter: `url(#${id}-b)` }) : null,
        pts.length > 1 ? el("path", { class: "line", d: line, stroke: `url(#${id}-s)` }) : null,
        el("circle", { class: "last-dot", cx: lx, cy: ly, r: 4 })),
      el("line", { class: "cross", x1: 0, x2: 0, y1: T, y2: H - Bm, visibility: "hidden" }),
      el("circle", { class: "dot", r: 5, visibility: "hidden" }));
    const tip = el("div", { class: "tip", hidden: true });
    wrap.replaceChildren(svg, tip);
    let at = -1;
    const show = i => {
      at = Math.max(0, Math.min(pts.length - 1, i)); const p = pts[at], px = x(p), py = y(p.v);
      const cross = svg.querySelector(".cross"), dot = svg.querySelector(".dot");
      cross.setAttribute("x1", px); cross.setAttribute("x2", px); cross.setAttribute("visibility", "visible");
      dot.setAttribute("cx", px); dot.setAttribute("cy", py); dot.setAttribute("visibility", "visible");
      const change = pts[0].v ? pct(String(p.v - pts[0].v), String(pts[0].v)) : 0;
      tip.replaceChildren(...[el("span", { class: "faint", text: when(p.t, "date") }), el("strong", { text: price(p.text, currency) }),
        at ? el("span", { class: tone(change), text: `${t("sinceStart")} ${change >= 0 ? "+" : ""}${pctText(change)}` }) : null].filter(Boolean));
      tip.hidden = false; tip.style.setProperty("left", `${Math.max(80, Math.min(W - 80, px))}px`); tip.style.setProperty("top", `${py}px`);
    };
    const hide = () => { at = -1; tip.hidden = true; svg.querySelectorAll(".cross,.dot").forEach(n => n.setAttribute("visibility", "hidden")); };
    const nearest = mx => pts.reduce((best, p, i) => (Math.abs(x(p) - mx) < Math.abs(x(pts[best]) - mx) ? i : best), 0);
    wrap.onmousemove = e => show(nearest(e.clientX - wrap.getBoundingClientRect().left));
    wrap.onmouseleave = hide; wrap.onblur = hide;
    wrap.onkeydown = e => {
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") { e.preventDefault(); show((at < 0 ? pts.length - 1 : at) + (e.key === "ArrowLeft" ? -1 : 1)); }
    };
  }
  let first = true;
  new ResizeObserver(() => { if (first) { first = false; return; } draw(); }).observe(wrap);
  setTimeout(draw); // once the panel is in the document and its width is known
  return wrap;
}

/* ---------- Dialogs ---------- */
/** A new trade picks an account and an instrument first, then fills in the trade in the same dialog. */
export function tradeDialog(p, trade, preset = {}) {
  const host = el("div", {});
  const foot = el("div", { class: "contents" });
  const d = dialog({ title: trade ? t("editTrade") : t("newTrade"), sub: "", width: 580, body: host, foot: [foot] });
  const sub = $(".dialog-head .sub", d.root) || $(".dialog-head .title", d.root).appendChild(el("p", { class: "sub" }));
  if (p) form(p); else picker();

  function picker() {
    sub.textContent = t("tradeStep1");
    // The account traded last, else the first one holding investments.
    const accountId = preset.accountId || state.lastTradeAccount || state.positions[0]?.accountId || state.accounts[0]?.id;
    const accSel = select("account", state.accounts.map(a => ({ value: a.id, label: a.name })), accountId);
    const q = input("q", "", { type: "search", placeholder: t("searchInstrumentHint"), autofocus: true });
    const results = el("div", { class: "pick-list", role: "listbox" });
    const draw = () => {
      const s = q.value.trim().toLowerCase();
      const items = state.instruments.filter(i => `${i.name} ${i.symbol} ${i.currencyCode}`.toLowerCase().includes(s));
      results.replaceChildren(...items.map(i => {
        const existing = state.positions.find(x => x.accountId === Number(accSel.value) && x.instrumentId === i.id);
        return el("button", { type: "button", class: "pick", onclick: () => choose(i) },
          el("div", { class: "grow" }, el("strong", { text: i.name }), el("div", { class: "sub", text: `${i.symbol} · ${i.currencyCode} · ${t("currentPrice")} ${price(i.currentPrice, i.currencyCode)}` })),
          Number(existing?.quantity) > 0 ? el("span", { class: "tag pos", text: `${t("holding")} ${qty(existing.quantity)}` }) : null, sym("chevron_right", "sm faint"));
      }));
      if (!items.length) results.append(el("div", { class: "empty" }, state.instruments.length ? t("noInstrumentMatch").replace("{q}", q.value) : t("noInstruments"),
        el("button", { type: "button", class: "btn ghost sm", onclick: () => { d.close(true); instrumentDialog(null, q.value); } }, t("addThisInstrument"))));
    };
    q.addEventListener("input", draw); accSel.addEventListener("change", draw);
    q.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); $(".pick", results)?.click(); } });
    const choose = i => {
      state.lastTradeAccount = Number(accSel.value);
      const existing = state.positions.find(x => x.accountId === Number(accSel.value) && x.instrumentId === i.id);
      form(existing || { id: 0, accountId: Number(accSel.value), instrumentId: i.id, name: i.name, symbol: i.symbol, currencyCode: i.currencyCode, quantity: "0" }, true);
    };
    host.replaceChildren(el("div", { class: "form", "data-nodirty": "" }, field(t("account"), accSel), field(t("searchInstrument"), q), el("div", { class: "full" }, results)));
    d.dirty = false;
    foot.replaceChildren(btn(t("cancel"), () => d.close(true), "ghost"));
    draw();
    if (preset.instrumentId) { const i = state.instruments.find(v => v.id === preset.instrumentId); preset.instrumentId = null; if (i) choose(i); }
  }

  async function form(pos, fromPicker = false) {
    const c = pos.currencyCode, ins = state.instruments.find(i => i.id === pos.instrumentId);
    const account = state.accounts.find(a => a.id === pos.accountId);
    sub.textContent = `${pos.name}${paren(pos.symbol)} · ${account?.name || ""}`;
    const detail = await api(`/api/v1/accounts/${pos.accountId}`);
    const cashOpts = detail.cash.filter(s => s.type === "SAVINGS" && s.currencyCode === c);
    let dir = trade?.action || "BUY";
    const dirSeg = seg([{ value: "BUY", label: t("buy") }, { value: "SELL", label: t("sell") }], dir, v => { dir = v; update(); }, "lg");
    const q = input("qty", trade?.quantity || "", { inputmode: "decimal", placeholder: "0", autofocus: true });
    const pr = input("price", trade?.unitPrice || ins?.currentPrice || "", { inputmode: "decimal" });
    const fee = input("fee", trade?.fee || "0", { inputmode: "decimal" });
    const time = input("time", localInput(trade?.businessAtMs ?? Date.now()), { type: "datetime-local" });
    const cash = select("cash", [{ value: "", label: t("noCashLink") }, ...cashOpts.map(s => ({ value: s.id, label: `${s.name}${paren(`${t("balance")} ${fmt(s.balance, c)}`)}` }))],
      trade ? (trade.cashLinked ? trade.linkedCashAccountId ?? "" : "") : cashOpts[0]?.id ?? "");
    const asPrice = toggle(t("setCurrentPrice"), "asPrice", false);
    const asPriceNote = el("p", { class: "hint" });
    const summaryBox = el("div", { class: "callout neu full" });
    function update() {
      const n = Number(q.value) || 0, x = Number(pr.value) || 0, f = Number(fee.value) || 0;
      const gross = (n * x).toFixed(digitsOf(c)), net = dir === "BUY" ? -(n * x + f) : n * x - f;
      const s = cash.value ? cashOpts.find(v => String(v.id) === cash.value) : null;
      summaryBox.replaceChildren(sym("info", "sm"), el("div", {}, `${t("grossAmount")} `, el("strong", { class: "num", text: fmt(gross, c) }),
        ` · ${dir === "BUY" ? t("totalPaid") : t("totalReceived")} `, el("strong", { class: "num", text: fmt(Math.abs(net).toFixed(digitsOf(c)), c) }),
        s && !trade ? [` · ${t("newBalanceOf").replace("{name}", s.name)} `, el("strong", { class: `num ${Number(s.balance) + net < 0 ? "loss" : ""}`, text: fmt((Number(s.balance) + net).toFixed(digitsOf(c)), c) })] : ""));
      const older = ins?.priceUpdatedAtMs != null && !(new Date(time.value).getTime() >= ins.priceUpdatedAtMs);
      const box = $("input", asPrice); box.disabled = older; if (older) box.checked = false;
      asPriceNote.textContent = older ? t("setCurrentPriceOlder").replace("{time}", when(ins.priceUpdatedAtMs)) : "";
    }
    [q, pr, fee, time, cash].forEach(n => n.addEventListener("input", update));
    host.replaceChildren(el("form", { class: "form", onsubmit: e => e.preventDefault() },
      el("div", { class: "fld full" }, el("span", { class: "lbl", text: t("direction") }), dirSeg),
      field(t("quantity"), q, { hint: `${t("holdingNow")} ${qty(pos.quantity)}` }), field(t("executionPrice"), pr, { affix: c }), field(t("fee"), fee, { affix: c }), field(t("tradeTime"), time),
      summaryBox, field(t("cashAccount"), cash, { full: true, hint: cashOpts.length ? t("cashLinkHint") : t("noCashOfCurrency").replace("{c}", c) }),
      trade ? null : el("div", { class: "full" }, asPrice, asPriceNote)));
    update();
    const ok = btn(trade ? t("save") : t("recordTrade"), () => save(), "primary");
    const del = trade ? btn(t("delete"), async () => {
      if (!(await confirmDialog({ title: t("confirmDelete"), message: t("deleteTradeMessage"), confirm: t("delete"), danger: true }))) return;
      await submit(d, del, `/api/v1/trades/${trade.id}`, "DELETE", write({ expectedRevision: trade.revision }), { message: t("tradeDeleted") });
    }, "danger ghost", "delete") : null;
    foot.replaceChildren(...[del, fromPicker ? btn(t("chooseAgain"), () => picker(), "ghost", "chevron_left") : null, btn(t("cancel"), () => d.close(), "ghost"), ok].filter(Boolean));
    async function save() {
      if (!validDecimal(q.value, { scale: 8, positive: true })) return markInvalid(q, t("enterQuantity"));
      if (!validDecimal(pr.value, { scale: 8, positive: true })) return markInvalid(pr, t("enterPrice"));
      if (!validDecimal(fee.value, { scale: digitsOf(c) })) return markInvalid(fee, t("enterFee"));
      if (!time.value) return markInvalid(time, t("enterTime"));
      const body = write({ expectedRevision: trade?.revision ?? null, accountId: pos.accountId, instrumentId: pos.instrumentId, direction: dir,
        quantity: q.value.trim(), executionPrice: pr.value.trim(), fee: fee.value.trim() || "0", occurredAtMs: keptTime(time.value, trade?.businessAtMs),
        currencyCode: c, cashLinked: !!cash.value, cashAccountId: cash.value ? Number(cash.value) : null, setAsCurrentPrice: !trade && $("input", asPrice).checked });
      const path = trade ? `/api/v1/trades/${trade.id}` : pos.id ? `/api/v1/positions/${pos.id}/trades` : "/api/v1/trades";
      await submit(d, ok, path, trade ? "PUT" : "POST", body, { message: trade ? t("saved") : t("tradeRecorded") });
    }
  }
}

export function instrumentDialog(i, presetName = "") {
  const name = input("name", i?.name || presetName, { maxlength: 60 });
  const symbol = input("symbol", i?.symbol || "", { maxlength: 20, readOnly: !!i?.symbolLocked });
  const type = select("type", state.assetTypes.map(x => ({ value: x.id, label: typeLabel(x.name, x.isDefault) })), i?.typeId ?? state.assetTypes.find(x => x.isDefault)?.id);
  const cur = input("cur", i?.currencyCode || B() || "CNY", { maxlength: 3, readOnly: !!i?.currencyLocked, class: "upper" });
  const pr = input("price", i ? i.currentPrice : "", { inputmode: "decimal", placeholder: "0.00" });
  const confirmPrice = toggle(t("priceChecked"), "confirmPrice", false, t("priceCheckedHint"));
  const lockedNote = t("lockedByTrades");
  const form = el("form", { class: "form", onsubmit: e => e.preventDefault() },
    field(t("name"), name, { full: true }), field(t("symbol"), symbol, { hint: i?.symbolLocked ? lockedNote : "" }), field(t("assetType"), type),
    field(t("currency"), cur, { hint: i?.currencyLocked ? lockedNote : t("currencyHint") }), field(t("currentPrice"), pr), el("div", { class: "full" }, confirmPrice));
  // Changing an instrument's currency asks to check the price as well, as the phone does.
  const syncConfirm = () => { confirmPrice.hidden = !i || i.currencyLocked || cur.value.trim().toUpperCase() === i.currencyCode; };
  cur.addEventListener("input", syncConfirm); syncConfirm();
  const ok = btn(i ? t("save") : t("add"), async () => {
    const c = cur.value.trim().toUpperCase();
    if (!name.value.trim()) return markInvalid(name, t("enterName"));
    if (!symbol.value.trim()) return markInvalid(symbol, t("enterSymbol"));
    if (!/^[A-Z]{3}$/.test(c)) return markInvalid(cur, t("enterCurrency"));
    if (priceUnits(pr.value) === null) return markInvalid(pr, t("priceInvalid"));
    if (!state.assetTypes.length) { d.fail(t("createTypeFirst")); return; }
    const changed = !!i && c !== i.currencyCode;
    if (changed && !$("input", confirmPrice).checked) { d.fail(t("PRICE_CONFIRMATION")); return; }
    await submit(d, ok, i ? `/api/v1/instruments/${i.id}` : "/api/v1/instruments", i ? "PUT" : "POST", write({ expectedRevision: i?.revision ?? null,
      name: name.value.trim(), symbol: symbol.value.trim(), typeId: Number(type.value), currencyCode: c, currentPrice: pr.value.trim(),
      currencyPriceConfirmed: !!i?.currencyLocked || changed }), { message: i ? t("saved") : t("instrumentAdded") });
  }, "primary");
  const d = dialog({ title: i ? t("editInstrument") : t("newInstrument"), sub: i ? `${i.name} · ${i.symbol}` : t("instrumentShared"), width: 520, body: form,
    foot: [btn(t("cancel"), () => d.close(), "ghost"), ok] });
}

export function assetTypesDialog() {
  const body = el("div", {});
  const d = dialog({ title: t("assetTypes"), sub: t("assetTypesSub"), width: 480, body,
    foot: [btn(t("newAssetType"), () => edit(null), "tonal", "plus"), btn(t("done"), () => d.close(true), "primary")] });
  function draw() {
    body.replaceChildren(...state.assetTypes.map(x => el("div", { class: "mini-row static" },
      el("div", { class: "grow" }, el("strong", { text: typeLabel(x.name, x.isDefault) }),
        el("div", { class: "sub", text: `${t("instrumentCountSub").replace("{n}", state.instruments.filter(i => i.typeId === x.id).length)}${x.isDefault ? ` · ${t("defaultTypeFixed")}` : ""}` })),
      x.isDefault ? null : iconBtn("pen", `${t("rename")} ${x.name}`, () => edit(x)))));
  }
  function edit(x) {
    const name = input("name", x?.name || "", { maxlength: 20, autofocus: true });
    const ok = btn(x ? t("save") : t("add"), async () => {
      const v = name.value.trim(); if (!v) return markInvalid(name, t("enterName"));
      if (state.assetTypes.some(y => y !== x && y.name === v)) return markInvalid(name, t("DUPLICATE_TYPE"));
      if (await submit(n, ok, x ? `/api/v1/asset-types/${x.id}` : "/api/v1/asset-types", x ? "PUT" : "POST", write({ name: v }))) {
        state.assetTypes = (await api("/api/v1/asset-types")).items; draw();
      }
    }, "primary");
    const n = dialog({ title: x ? t("editAssetType") : t("newAssetType"), width: 400, body: field(t("name"), name), foot: [btn(t("cancel"), () => n.close(), "ghost"), ok] });
  }
  draw();
}
