// Bills, read-only: they are recorded and changed on the phone. Totals and per-day sums come counted from the phone.
import { api, state, t } from "./core.js";
import { el, sym, money, fmt, btn, iconBtn, menu, select, seg, $, when, dayLabel, qtyText, digitsOf } from "./ui.js";
import { go } from "./router.js";
import { pageHead, detailPanel, split, emptyState, rowKeys, kv } from "./layout.js";

const B = () => state.session?.baseCurrency;

/* ---------- Shared with the statistics page ---------- */
export const category = id => state.billCategories?.find(c => c.id === id);
export const categoryName = c => (!c ? "" : c.customName || (state.locale.startsWith("zh") ? c.nameZh : c.nameEn) || c.nameZh || "");
export function catTitle(b) {
  const top = categoryName(category(b.categoryId)), sub = categoryName(category(b.subCategoryId));
  return top && sub ? `${top}-${sub}` : sub || top || "";
}
export function catIcon(icon, color, size = "") {
  return el("span", { class: `cat-icon c-${color || "grey"} ${size}` }, sym(icon || "circle-question", size === "lg" ? "" : "sm"));
}
export const ymOf = ms => { const d = new Date(ms); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`; };
export const ymNow = () => ymOf(Date.now());
export function addMonths(ym, n) { const [y, m] = ym.split("-").map(Number); return ymOf(new Date(y, m - 1 + n, 1).getTime()); }
export function ymLabel(ym) { const [y, m] = ym.split("-").map(Number); return new Date(y, m - 1, 1).toLocaleDateString(state.locale, { year: "numeric", month: "long" }); }
/** ‹ November 2026 › : the label opens the months there are bills in; [range] is the first and last of them. */
export function monthSwitcher(ym, path, range) {
  const now = ymNow(), min = range?.first || ym, max = range?.last && range.last > now ? range.last : now;
  const months = []; for (let m = max; m >= min && months.length < 240; m = addMonths(m, -1)) months.push(m);
  const to = (m, motion) => go(path(m), { motion });
  const prev = iconBtn("chevron_left", t("previousMonth"), () => to(addMonths(ym, -1), "prev"));
  const next = iconBtn("chevron_right", t("nextMonth"), () => to(addMonths(ym, 1), "next"));
  prev.disabled = ym <= min; next.disabled = ym >= max;
  const label = el("button", { type: "button", class: "month-label", "aria-label": t("chooseMonth").replace("{month}", ymLabel(ym)),
    onclick: e => menu(e.currentTarget, months.map(m => ({ label: ymLabel(m), icon: m === ym ? "check" : null, run: () => m !== ym && to(m, m < ym ? "prev" : "next") }))) },
    el("span", { class: "num", text: ymLabel(ym) }), sym("chevron_down", "sm faint"));
  return el("div", { class: "month-switch", role: "group", "aria-label": t("month") }, prev, label, next);
}
/** A headline amount that rolls up when the page opens and moves from its last value when the month changes. */
export const keyed = (key, value, opts = {}) => money(value, B(), { count: true, key, ...opts });
export function compareTag(now, before, expense) {
  if (before == null) return null;
  const diff = Number(now) - Number(before);
  if (Math.abs(diff) < 0.005) return el("span", { class: "tag", text: t("sameAsLastMonth") });
  const pctPart = Number(before) > 0 ? `${state.locale.startsWith("zh") ? "（" : " ("}${new Intl.NumberFormat(state.locale, { maximumFractionDigits: 1, minimumFractionDigits: 1 }).format(Math.abs(diff) / Number(before) * 100)}%${state.locale.startsWith("zh") ? "）" : ")"}` : "";
  const good = expense ? diff < 0 : diff > 0;
  return el("span", { class: `tag ${good ? "pos" : "neg"}` }, el("span", { class: "mask", text: `${t(diff > 0 ? "moreThanLastMonth" : "lessThanLastMonth").replace("{amount}", fmt(Math.abs(diff).toFixed(2), B()))}${pctPart}` }));
}

/* ---------- Naming, as the app's bill list ---------- */
export const TYPE = () => ({ EXPENSE: t("expense"), INCOME: t("income"), TRANSFER: t("transfer"), REFUND: t("refund"), REIMBURSEMENT: t("reimbursement") });
const isSettlement = b => b.type === "REFUND" || b.type === "REIMBURSEMENT";
export function billTitle(b) {
  if (isSettlement(b)) return `${TYPE()[b.type]} · ${b.parentNote || catTitle(b) || t("deletedBill")}`;
  if (b.type === "TRANSFER") return b.note || t("transfer");
  return b.note || catTitle(b) || t("uncategorized");
}
export function billIcon(b) {
  if (b.type === "TRANSFER") return { icon: b.transferKind === "INTERNAL" ? "repeat" : "globe", color: "grey" };
  const c = category(b.subCategoryId) || category(b.categoryId);
  return { icon: c?.icon || "circle-question", color: category(b.categoryId)?.color || "grey" };
}
const outflow = b => b.type === "EXPENSE" || (b.type === "TRANSFER" && b.transferKind === "TO_EXTERNAL");
/** What a row shows: what moved in the account, in its currency; a transfer shows its sending side. */
function shown(b) {
  if (b.type === "TRANSFER") return b.transferKind === "FROM_EXTERNAL" ? { value: b.toAmount, currency: b.toCurrencyCode } : { value: b.accountAmount, currency: b.accountCurrencyCode };
  return b.cashAccountId || b.accountDeleted ? { value: b.accountAmount, currency: b.accountCurrencyCode } : { value: b.paid, currency: b.currencyCode };
}
function signed(b) {
  const s = shown(b), internal = b.type === "TRANSFER" && b.transferKind === "INTERNAL";
  return fmt(outflow(b) && !internal ? `-${s.value}` : s.value, s.currency, { sign: !internal && !outflow(b) });
}
function amountTone(b) {
  if ((b.type === "EXPENSE" || b.type === "INCOME") && !b.counted) return "faint";
  if (b.type === "TRANSFER" || isSettlement(b)) return "";
  return outflow(b) ? "loss" : "gain";
}
function tags(b) {
  return [(b.type === "EXPENSE" || b.type === "INCOME") && !b.counted ? [t("notCounted"), null] : null,
    Number(b.refunded) > 0 ? [t("refundedShort"), b.refunded] : null, Number(b.reimbursed) > 0 ? [t("reimbursedShort"), b.reimbursed] : null,
    b.reimburseState === "PENDING" || b.reimburseState === "PARTIAL" ? [t("pendingShort"), b.remaining] : null,
    b.reimburseState === "WAIVED" ? [t("waived"), null] : null, b.occurredAtMs > Date.now() ? [t("notYetPosted"), null] : null].filter(Boolean);
}
/** A label with its amount; only the amount blurs when amounts are hidden. */
const plain = (b, v) => fmt(v, undefined, { digits: digitsOf(b.currencyCode) });
const tagText = (b, [label, v]) => (v == null ? [label] : [`${label} `, el("span", { class: "mask", text: plain(b, v) })]);
const joined = (b, list) => list.flatMap((x, i) => (i ? [" · ", ...tagText(b, x)] : tagText(b, x)));
const subLabel = (accounts, id, deleted) => { const c = accounts.get(id); return c ? `${c.accountName} · ${c.name}` : deleted ? t("deletedAccount") : t("noAccount"); };
function where(b, accounts) {
  const side = (id, external) => (external ? b.counterparty || t("externalAccount") : accounts.get(id)?.accountName || t("deletedAccount"));
  if (b.type !== "TRANSFER") return b.cashAccountId ? accounts.get(b.cashAccountId)?.accountName || t("deletedAccount") : b.accountDeleted ? t("deletedAccount") : t("noAccount");
  return `${side(b.cashAccountId, b.transferKind === "FROM_EXTERNAL")} → ${side(b.toCashAccountId, b.transferKind === "TO_EXTERNAL")}`;
}

/* ---------- Page ---------- */
const PAGE = 80;
const active = () => { const f = state.bills; return !!(f.type || f.cat || f.sub || f.counted || f.q); };
function query(ym) {
  const f = state.bills, p = new URLSearchParams({ month: ym });
  if (f.type) p.set("type", f.type); if (f.cat) p.set("categoryId", f.cat); if (f.sub) p.set("cashAccountId", f.sub);
  if (f.counted) p.set("counted", f.counted); if (f.q.trim()) { p.set("query", f.q.trim()); if (f.scope === "all") p.set("scope", "all"); }
  return `/api/v1/bills?${p}`;
}

/** Routes: #/bills, #/bills/<month>, #/bills/<month>/<id>, and #/bills/<id>, which opens a bill in its own month. */
export async function billsPage(r) {
  let ym = /^\d{4}-\d{2}$/.test(r.a || "") ? r.a : state.bills.month || ymNow(), id = r.b ? Number(r.b) : null, detail = null;
  if (/^\d+$/.test(r.a || "") && !r.b) {
    id = Number(r.a);
    detail = await api(`/api/v1/bills/${id}`).catch(e => (e.code === "NOT_FOUND" ? null : Promise.reject(e)));
    if (detail) ym = ymOf(detail.bill.occurredAtMs);
  }
  state.bills.month = ym;
  const [data, open] = await Promise.all([api(query(ym)), id && !detail ? api(`/api/v1/bills/${id}`).catch(e => (e.code === "NOT_FOUND" ? null : Promise.reject(e))) : detail]);
  state.billsData = data;
  const accounts = new Map(data.cashAccounts.map(c => [c.id, c]));
  const head = pageHead(t("bills"), [`${ymLabel(ym)} · ${t("billCount").replace("{n}", data.monthCount)} · ${t("viewOnly")}`],
    [monthSwitcher(ym, m => `bills/${m}`, { first: data.firstMonth, last: data.lastMonth })], "Bills");
  head.querySelector(".head-actions").classList.add("with-month");
  const totals = data.totals, previous = data.previous;
  const tileLink = (label, value, foot, href) => el("a", { class: "tile kpi kpi-link", href }, el("div", { class: "k" }, label, sym("chevron_right", "sm")),
    el("div", { class: "v" }, value), foot ? el("div", { class: "d" }, foot) : null);
  const kpis = el("div", { class: "kpis slide" },
    tileLink(t("expense"), keyed("bills-expense", totals.expense), compareTag(totals.expense, previous?.expense, true), `#/statistics/expense/${ym}`),
    tileLink(t("income"), keyed("bills-income", totals.income), compareTag(totals.income, previous?.income, false), `#/statistics/income/${ym}`),
    el("div", { class: "tile kpi" }, el("div", { class: "k", text: t("balanceShort") }), el("div", { class: "v" }, keyed("bills-balance", totals.balance, { tone: true, sign: true })),
      Number(data.savings) > 0 ? el("div", { class: "d faint" }, `${t("savedByDiscounts")} `, el("span", { class: "mask", text: fmt(data.savings, B()) })) : null),
    el("div", { class: "tile kpi" }, el("div", { class: "k", text: t("pendingReimbursement") }), el("div", { class: "v" }, keyed("bills-pending", data.pending.total)),
      el("div", { class: "d faint", text: data.pending.count ? t("pendingCount").replace("{n}", data.pending.count) : t("nonePending") })));
  const main = el("div", {}, kpis, totals.partial ? el("p", { class: "incomplete-note", text: t("ratesMissingLeftOut") }) : null,
    el("div", { class: "reveal spaced-l" }, filterBar(ym, data), el("div", { id: "bill-list", class: "slide" }, billList(ym, data, id, accounts))));
  return [head, el("div", { class: "page" }, split(main, open ? billDetail(open, accounts) : null))];
}

async function redraw() {
  const ym = state.bills.month, id = Number(location.hash.split("/")[3]) || null;
  const data = await api(query(ym)); state.billsData = data;
  const accounts = new Map(data.cashAccounts.map(c => [c.id, c]));
  $("#bill-list")?.replaceChildren(billList(ym, data, id, accounts));
  const bar = $(".filters"), focused = document.activeElement, label = focused?.getAttribute("aria-label");
  if (bar) { const next = filterBar(ym, data); bar.replaceWith(next); if (bar.contains(focused) && label) $(`[aria-label="${label}"]`, next)?.focus(); }
}

function filterBar(ym, data) {
  const f = state.bills;
  const set = patch => { Object.assign(f, patch, { shown: PAGE, day: "" }); redraw(); };
  const chip = (v, text) => el("button", { type: "button", class: "chip", "aria-pressed": String(f.type === v), onclick: () => set({ type: v }) }, text);
  const catOpts = [{ value: "", label: t("allCategories") }];
  for (const kind of ["EXPENSE", "INCOME"]) for (const c of (state.billCategories || []).filter(x => x.kind === kind && !x.parentId && !x.hidden)) {
    catOpts.push({ value: c.id, label: `${kind === "EXPENSE" ? t("expense") : t("income")} · ${categoryName(c)}` });
    for (const s of state.billCategories.filter(x => x.parentId === c.id && !x.hidden)) catOpts.push({ value: s.id, label: `　${categoryName(s)}` });
  }
  const cat = select("cat", catOpts, f.cat); cat.setAttribute("aria-label", t("filterCategory")); cat.addEventListener("change", () => set({ cat: cat.value }));
  const acc = select("sub", [{ value: "", label: t("allAccounts") }, ...data.cashAccounts.map(c => ({ value: c.id, label: `${c.accountName} · ${c.name}` }))], f.sub);
  acc.setAttribute("aria-label", t("filterAccount")); acc.addEventListener("change", () => set({ sub: acc.value }));
  const counted = select("counted", [{ value: "", label: t("countedAll") }, { value: "true", label: t("countedYes") }, { value: "false", label: t("countedNo") }], f.counted);
  counted.setAttribute("aria-label", t("filterCounted")); counted.addEventListener("change", () => set({ counted: counted.value }));
  const scope = seg([{ value: "month", label: t("thisMonthShort") }, { value: "all", label: t("allMonths") }], f.scope, v => set({ scope: v }), "sm");
  scope.setAttribute("aria-label", t("searchScope")); scope.hidden = !f.q.trim();
  let timer;
  const search = el("label", { class: "search" }, sym("search", "sm"),
    el("input", { type: "search", placeholder: t("searchNotes"), value: f.q, "aria-label": t("searchNotes"),
      oninput: e => { f.q = e.target.value; scope.hidden = !f.q.trim(); clear.hidden = !active(); clearTimeout(timer); timer = setTimeout(redraw, 250); } }));
  const clear = btn(t("clearFilters"), () => set({ type: "", cat: "", sub: "", counted: "", q: "", scope: "month" }), "ghost sm", "filter_x");
  clear.hidden = !active();
  return el("div", { class: "filters", role: "search" },
    chip("", t("all")), chip("EXPENSE", t("expense")), chip("INCOME", t("income")), chip("TRANSFER", t("transfer")), el("span", { class: "gap6" }),
    el("label", { class: "chip field-chip" }, sym("shapes", "sm"), cat), el("label", { class: "chip field-chip" }, sym("accounts", "sm"), acc),
    el("label", { class: "chip field-chip" }, counted), clear, el("span", { class: "grow" }), scope, search);
}

function billList(ym, data, selected, accounts) {
  const f = state.bills, items = data.items;
  const list = el("div", { class: "list", role: "listbox", "aria-label": t("bills") });
  if (!items.length) {
    list.append(emptyState(f.q ? "search" : "bills", f.q ? t("noNoteMatch") : active() ? t("noBillMatch") : t("noBillsThisMonth")));
    return el("div", {}, list);
  }
  if (f.day) {
    // A day opened from the statistics page may lie past the first page: show down to it.
    const at = items.findIndex(b => new Date(b.occurredAtMs).toDateString() === new Date(`${f.day}T00:00:00`).toDateString());
    if (at >= 0) f.shown = Math.max(f.shown, at + PAGE / 2);
  }
  const days = new Map(data.days.map(d => [d.date, d.totals]));
  let shownCount = 0, day = "";
  for (const b of items) {
    if (shownCount >= f.shown) break;
    const d = new Date(b.occurredAtMs), iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    if (iso !== day) {
      day = iso; const tot = days.get(iso);
      list.append(el("div", { class: `day-head ${f.day === iso ? "flash" : ""}`, id: `day-${iso}` }, el("span", { text: dayLabel(b.occurredAtMs) }),
        el("span", { class: "day-tot" }, tot && Number(tot.expense) > 0 ? el("span", {}, `${t("expense")} `, el("span", { class: "mask loss", text: fmt(tot.expense, B()) })) : null,
          tot && Number(tot.income) > 0 ? el("span", {}, `${t("income")} `, el("span", { class: "mask gain", text: fmt(tot.income, B()) })) : null)));
    }
    list.append(billRow(b, selected, ym, accounts)); shownCount++;
  }
  const count = el("p", { class: "faint small centered", text: active() ? t(f.q && f.scope === "all" ? "foundAllMonths" : "foundCount").replace("{n}", items.length) : t("billCount").replace("{n}", items.length) });
  const out = el("div", {}, list, count);
  if (items.length > shownCount) {
    const more = el("div", { class: "load-more" }, btn(t("loadMoreLeft").replace("{n}", items.length - shownCount), () => { f.shown += PAGE; redraw(); }, "tonal"));
    out.insertBefore(more, count);
    const io = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) { io.disconnect(); f.shown += PAGE; redraw(); } }, { rootMargin: "200px" });
    requestAnimationFrame(() => io.observe(more));
  }
  if (f.day) requestAnimationFrame(() => { $(`#day-${f.day}`)?.scrollIntoView({ block: "center", behavior: "smooth" }); f.day = ""; });
  return out;
}
function billRow(b, selected, ym, accounts) {
  const ic = billIcon(b), tg = tags(b);
  const spent = b.type === "EXPENSE" && Number(b.refunded) + Number(b.reimbursed) > 0 && Number(b.remaining) <= 0;
  const details = [b.note && b.type !== "TRANSFER" && !isSettlement(b) ? catTitle(b) : null, where(b, accounts), when(b.occurredAtMs, "time")].filter(Boolean);
  const month = ymOf(b.occurredAtMs);
  return rowKeys(el("div", { class: `row cols-records ${spent ? "spent" : ""}`, role: "option", "data-key": String(b.id), "aria-selected": String(b.id === selected) },
    catIcon(ic.icon, ic.color),
    el("div", { class: "min0" }, el("div", { class: "rec-title" }, el("strong", { text: billTitle(b) }),
      b.imageCount ? el("span", { class: "clip", title: t("imageCount").replace("{n}", b.imageCount) }, sym("paperclip", "sm"), String(b.imageCount)) : null),
      el("div", { class: "rec-sub", text: details.join(" · ") })),
    el("div", { class: "rec-amt" }, el("strong", { class: `mask ${amountTone(b)}`, text: signed(b) }),
      Number(b.discount) > 0 ? el("div", { class: "sub gain" }, `${t("discountShort")} `, el("span", { class: "mask", text: plain(b, b.discount) })) : null,
      tg.length ? el("div", { class: "sub" }, joined(b, tg)) : null)),
  () => go(selected === b.id ? `bills/${ym}` : `bills/${month}/${b.id}`));
}

/* ---------- Detail ---------- */
function billDetail(data, accounts) {
  const b = data.bill, ic = billIcon(b), ym = ymOf(b.occurredAtMs), tg = tags(b);
  const link = (id, deleted) => (accounts.get(id) ? el("a", { href: `#/accounts/${accounts.get(id).accountId}`, class: "link-btn", text: subLabel(accounts, id) }) : subLabel(accounts, id, deleted));
  const rows = [[t("time"), when(b.occurredAtMs)]];
  if (b.type === "TRANSFER") {
    rows.push([t("transferOut"), b.transferKind === "FROM_EXTERNAL" ? `${t("externalAccount")} · ${b.counterparty || t("notFilled")}` : link(b.cashAccountId, b.accountDeleted)]);
    rows.push([t("transferIn"), b.transferKind === "TO_EXTERNAL" ? `${t("externalAccount")} · ${b.counterparty || t("notFilled")}` : link(b.toCashAccountId, false)]);
    if (b.transferKind === "INTERNAL" && b.toCurrencyCode && b.toCurrencyCode !== b.accountCurrencyCode)
      rows.push([t("amountOut"), money(b.accountAmount, b.accountCurrencyCode)], [t("amountIn"), money(b.toAmount, b.toCurrencyCode)]);
    if (Number(b.fee) > 0) rows.push([t("fee"), money(b.fee, b.accountCurrencyCode)]);
  } else {
    rows.push([isSettlement(b) ? t("receivedIn") : t("account"), b.cashAccountId || b.accountDeleted ? link(b.cashAccountId, b.accountDeleted) : t("noAccount")]);
    rows.push([t("category"), catTitle(b) || t("uncategorized")]);
    if (Number(b.discount) > 0) rows.push([t("originalAmount"), money(b.amount, b.currencyCode)], [t("discount"), el("span", { class: "gain" }, money(b.discount, b.currencyCode))], [t("paid"), money(b.paid, b.currencyCode)]);
    else if (b.currencyCode !== b.accountCurrencyCode) rows.push([t("amount"), money(b.paid, b.currencyCode)]);
    if (b.currencyCode !== b.accountCurrencyCode) {
      const src = { AUTO: t("rateAuto"), MANUAL: t("rateManual"), ONE_TIME: t("rateOneTime"), DERIVED: t("rateDerived") }[b.rateSource] || "";
      rows.push([t("rate"), `1 ${b.currencyCode} = ${qtyText(b.rate)} ${b.accountCurrencyCode}${src ? ` · ${src}` : ""}`], [t("accountAmount"), money(b.accountAmount, b.accountCurrencyCode)]);
    }
    if (b.type === "EXPENSE" || b.type === "INCOME") rows.push([t("countedInStats"), b.counted ? (b.reimburseState === "WAIVED" ? t("countedWaived") : t("countedYesShort")) : t("countedNoShort")]);
    if (b.reimbursable) rows.push([t("reimbursementLabel"), el("span", {}, t(`reimburse_${b.reimburseState}`),
      b.reimburseState === "PENDING" || b.reimburseState === "PARTIAL" ? [` · ${t("leftToReceive")} `, money(b.remaining, b.currencyCode)] : null)], [t("payer"), b.counterparty || t("notFilled")]);
  }
  if (b.imageCount) rows.push([t("images"), t("imagesOnPhone").replace("{n}", b.imageCount)]);
  if (b.note && billTitle(b) !== b.note) rows.push([t("note"), b.note, true]);
  const mini = (x, title) => { const i = billIcon(x), m = ymOf(x.occurredAtMs);
    return el("div", { class: "mini-row", tabindex: "0", role: "link", onclick: () => go(`bills/${m}/${x.id}`), onkeydown: e => { if (e.key === "Enter") go(`bills/${m}/${x.id}`); } },
      catIcon(i.icon, i.color, "sm"), el("div", { class: "grow" }, el("strong", { text: title }), el("div", { class: "sub", text: `${when(x.occurredAtMs)} · ${where(x, accounts)}` })),
      el("strong", { class: `mask ${amountTone(x)}`, text: signed(x) })); };
  const sections = [];
  if (data.children.length) sections.push(el("div", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("refundsAndReimbursements") }),
    el("span", { class: "faint small" }, Number(b.remaining) > 0 ? [`${t("netAmount")} `, money(b.remaining, b.currencyCode)] : t("allReturned"))),
    el("div", {}, data.children.map(k => mini(k, `${TYPE()[k.type]}${k.note ? ` · ${k.note}` : ""}`)))));
  if (isSettlement(b) && data.parent) sections.push(el("div", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("originalBill") })), mini(data.parent, billTitle(data.parent))));
  const actions = b.type === "EXPENSE" || b.type === "INCOME" ? [btn(t("viewCategoryStats"), () => { state.statsOpen = new Set([b.categoryId]); go(`statistics/${b.type === "EXPENSE" ? "expense" : "income"}/${ym}`); }, "tonal", "statistics")] : [];
  return detailPanel({ back: `bills/${state.bills.month}`, avatar: catIcon(ic.icon, ic.color, "lg"), title: billTitle(b),
    sub: isSettlement(b) ? catTitle(b) : [TYPE()[b.type], b.type === "TRANSFER" ? t(b.transferKind === "INTERNAL" ? "betweenAccounts" : "externalAccount") : null].filter(Boolean).join(" · "),
    total: el("div", {}, el("div", { class: `big mask ${amountTone(b)}`, text: signed(b) }), tg.length ? el("div", { class: "tag-row" }, tg.map(x => el("span", { class: "tag" }, tagText(b, x)))) : null),
    body: el("div", {}, kv(rows), ...sections, actions.length ? el("div", { class: "actions-row" }, actions) : null,
      el("p", { class: "phone-note" }, sym("phone", "sm"), t("billOnPhone"))) });
}
