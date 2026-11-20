import { api, state, t } from "./core.js";
import { el, sym, money, fmt, qtyText, paren, btn, dialog, field, input, select, validDecimal, markInvalid, digitsOf, $, when, dayLabel, localInput, keptTime, confirmDialog } from "./ui.js";
import { go } from "./router.js";
import { pageHead, detailPanel, split, emptyState, rowKeys, kv } from "./layout.js";
import { submit, write } from "./forms.js";
import { openTrade } from "./page-investments.js";
import { depositDialog } from "./page-accounts.js";

const PAGE = 50;
const keyOf = r => `${r.kind}-${r.id}`;
const label = a => ({ BUY: t("buy"), SELL: t("sell"), CASH_SET: t("cashChange"), TERM_OPEN: t("depositOpen"), TERM_CLOSE: t("depositClose"),
  OPEN: t("depositOpen"), CLOSE: t("depositClose"), TRADE: t("trade"), TRADE_REMOVED: t("deletedInstrumentTrade"), DIVIDEND: t("dividend"),
  DIVIDEND_REMOVED: t("deletedInstrumentDividend"), BILL: t("bill"), BILL_TRANSFER_IN: t("billTransferIn"), BILL_REMOVED: t("deletedBill") })[a] || a;
const isBill = r => r.kind === "CASH" && ["BILL", "BILL_TRANSFER_IN", "BILL_REMOVED"].includes(r.action);
function iconOf(r) {
  if (isBill(r)) return ["bills", ""];
  if (r.kind === "DEPOSIT" || ["TERM_OPEN", "TERM_CLOSE"].includes(r.action)) return ["deposit", "dep"];
  if (r.action === "BUY") return ["plus", "buy"];
  if (r.action === "SELL") return ["trade", "sell"];
  if (r.action === "DIVIDEND") return ["dividend", "dep"];
  return ["accounts", ""];
}
function titleOf(r) {
  if (r.kind === "CASH" && r.action === "CASH_SET") return r.note || t("cashChange");
  if (r.kind === "DEPOSIT") return `${label(r.action)} · ${r.currencyCode}`;
  if (r.kind === "TRADE") return `${label(r.action)} ${r.objectName}`;
  if (isBill(r)) return r.note || label(r.action);
  return r.objectName && r.objectName !== r.childName ? `${label(r.action)} · ${r.objectName}` : label(r.action);
}
/** What moved: cash as recorded; a buy or a deposit opening takes money out, a sale, dividend or settlement brings it in. */
function signedAmount(r) {
  if (r.kind === "CASH" || r.amount == null) return r.amount;
  const out = r.action === "BUY" || r.action === "OPEN";
  return out && !String(r.amount).startsWith("-") ? `-${r.amount}` : r.amount;
}

function range() {
  const f = state.rec, now = new Date();
  const start = (y, m) => new Date(y, m, 1).getTime();
  if (f.period === "month") return [start(now.getFullYear(), now.getMonth()), null];
  if (f.period === "3m") return [start(now.getFullYear(), now.getMonth() - 2), null];
  if (f.period === "year") return [start(now.getFullYear(), 0), null];
  if (f.period === "custom") return [f.from ? new Date(`${f.from}T00:00:00`).getTime() : null, f.to ? new Date(`${f.to}T23:59:59.999`).getTime() : null];
  return [null, null];
}
const active = () => { const f = state.rec; return !!(f.type || f.account || f.period || f.q); };
function query(cursor) {
  const f = state.rec, p = new URLSearchParams({ pageSize: String(PAGE) }), [from, to] = range();
  if (from != null) p.set("from", String(from)); if (to != null) p.set("to", String(to));
  if (f.account) p.set("accountId", f.account);
  if (f.type) p.set("type", f.type);
  if (f.q.trim()) p.set("query", f.q.trim());
  if (cursor) p.set("cursor", cursor);
  return `/api/v1/records?${p}`;
}

/** The first page with the current filters; a page reached by "load more" stays loaded while only the detail changes. */
export async function recordsPage(r) {
  const signature = query(null);
  if (state.recordsSignature !== signature || !state.recordsRefreshed) {
    const data = await api(signature);
    state.recordItems = data.items; state.recordsCursor = data.nextCursor; state.recordsSignature = signature;
  }
  state.recordsRefreshed = false;
  const key = r.a || null, rec = key ? state.recordItems.find(v => keyOf(v) === key) : null;
  const head = pageHead(t("records"), [t("recordsSub")], [], "Records");
  const main = el("div", { class: "reveal" }, filterBar(), el("div", { id: "rec-list" }, recordList(key)));
  return [head, el("div", { class: "page" }, split(main, rec ? recordDetail(rec) : null))];
}
/** A redraw after a save keeps the loaded pages; a filter change starts again from the first page. */
export function keepRecordPages() { state.recordsRefreshed = true; }

async function reload() {
  state.recordsSignature = null;
  const box = $("#rec-list");
  const data = await api(query(null));
  state.recordItems = data.items; state.recordsCursor = data.nextCursor; state.recordsSignature = query(null);
  box?.replaceChildren(recordList(location.hash.split("/")[2] || null));
}
async function more() {
  if (!state.recordsCursor || state.loadingMore) return;
  state.loadingMore = true;
  try {
    const data = await api(query(state.recordsCursor));
    state.recordItems = [...state.recordItems, ...data.items]; state.recordsCursor = data.nextCursor;
    $("#rec-list")?.replaceChildren(recordList(location.hash.split("/")[2] || null));
  } finally { state.loadingMore = false; }
}

function filterBar() {
  const f = state.rec;
  const set = patch => { Object.assign(f, patch); const bar = $(".filters"); bar?.replaceWith(filterBar()); reload(); };
  const typeChip = (v, text) => el("button", { type: "button", class: "chip", "aria-pressed": String(f.type === v), onclick: () => set({ type: v }) }, text);
  const acc = select("account", [{ value: "", label: t("allAccounts") }, ...state.accounts.map(a => ({ value: a.id, label: a.name }))], f.account);
  acc.setAttribute("aria-label", t("filterAccount")); acc.addEventListener("change", () => set({ account: acc.value }));
  const period = select("period", [{ value: "", label: t("allTime") }, { value: "month", label: t("thisMonth") }, { value: "3m", label: t("last3Months") },
    { value: "year", label: t("thisYear") }, { value: "custom", label: t("customRange") }], f.period);
  period.setAttribute("aria-label", t("timeRange")); period.addEventListener("change", () => set({ period: period.value }));
  const dateChip = (k, text) => el("label", { class: "chip field-chip" }, text, el("input", { type: "date", value: f[k], "aria-label": text, onchange: e => set({ [k]: e.target.value }) }));
  let timer;
  const search = el("label", { class: "search push" }, sym("search", "sm"),
    el("input", { type: "search", placeholder: t("searchRecords"), value: f.q, "aria-label": t("searchRecords"),
      oninput: e => { f.q = e.target.value; clearTimeout(timer); timer = setTimeout(reload, 250); } }));
  const clear = btn(t("clearFilters"), () => set({ type: "", account: "", period: "", from: "", to: "", q: "" }), "ghost sm", "filter_x");
  clear.hidden = !active();
  return el("div", { class: "filters", role: "search" },
    typeChip("", t("all")), typeChip("CASH", t("cashEntriesChip")), typeChip("DEPOSIT", t("deposits")), typeChip("TRADE", t("trades")),
    el("span", { class: "gap6" }),
    el("label", { class: "chip field-chip" }, sym("accounts", "sm"), acc),
    el("label", { class: "chip field-chip" }, sym("calendar", "sm"), period),
    f.period === "custom" ? [dateChip("from", t("fromDate")), dateChip("to", t("toDate"))] : null,
    clear, search);
}

function recordList(selected) {
  const items = state.recordItems;
  const list = el("div", { class: "list", role: "listbox", "aria-label": t("records") });
  if (!items.length) { list.append(emptyState("search", active() ? t("noRecordMatch") : t("noRecords"))); return el("div", {}, list); }
  let day = "";
  for (const r of items) {
    const d = new Date(r.businessAtMs).toDateString();
    if (d !== day) { day = d; list.append(el("div", { class: "day-head" }, el("span", { text: dayLabel(r.businessAtMs) }))); }
    list.append(recordRow(r, selected));
  }
  const out = el("div", {}, list);
  if (state.recordsCursor) {
    const box = el("div", { class: "load-more" }, btn(t("loadMore"), () => more(), "tonal"));
    out.append(box);
    // Scrolling near the end loads the next page by itself; the button stays for keyboard users.
    const io = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) { io.disconnect(); more(); } }, { rootMargin: "200px" });
    requestAnimationFrame(() => io.observe(box));
  }
  return out;
}
function recordRow(r, selected) {
  const [ic, cls] = iconOf(r), amount = signedAmount(r), key = keyOf(r);
  return rowKeys(el("div", { class: "row cols-records", role: "option", "data-key": key, "aria-selected": String(key === selected) },
    el("span", { class: `kind-icon ${cls}` }, sym(ic, "sm")),
    el("div", { class: "min0" },
      el("div", { class: "rec-title" }, el("strong", { text: titleOf(r) }), r.action === "DIVIDEND" || isBill(r) ? el("span", { class: "tag", text: t("phoneOnly") }) : null),
      el("div", { class: "rec-sub", text: [r.accountName, r.kind === "TRADE" ? null : r.childName, when(r.businessAtMs, "time")].filter(Boolean).join(" · ") })),
    el("div", { class: "rec-amt" }, el("strong", {}, money(amount, r.currencyCode, { sign: true, tone: true })),
      r.quantity && r.action !== "DIVIDEND" ? el("div", { class: "sub num", text: `${qtyText(r.quantity)} × ${fmt(r.unitPrice, r.currencyCode, { digits: 8, min: 2 })}` }) : null)),
  () => go(selected === key ? "records" : `records/${key}`));
}

function recordDetail(r) {
  const [ic, cls] = iconOf(r);
  const rows = [[t("time"), when(r.businessAtMs)], [t("account"), el("a", { href: `#/accounts/${r.accountId}`, class: "link-btn", text: r.accountName })]];
  if (r.kind !== "TRADE") rows.push([t("subaccount"), r.childName || "—"]);
  rows.push([t("currency"), r.currencyCode]);
  const actions = [], notes = [];
  if (r.kind === "CASH" && r.action === "CASH_SET") {
    rows.push([t("note"), r.note || "—", true], [t("updated"), when(r.updatedAtMs)]);
    actions.push(btn(t("edit"), () => cashDialog(r), "primary", "pen"), btn(t("delete"), () => deleteCash(r), "danger tonal", "delete"));
  } else if (r.kind === "TRADE" || (r.kind === "CASH" && (r.action === "TRADE" || r.action === "DIVIDEND"))) {
    const positionId = r.kind === "TRADE" ? r.childId : r.sourceParentId, tradeId = r.kind === "TRADE" ? r.id : r.sourceId;
    if (r.kind === "TRADE") rows.push([t("instrument"), `${r.objectName}${r.note ? paren(r.note) : ""}`]);
    if (r.kind === "TRADE" && r.action !== "DIVIDEND") rows.push([t("quantity"), qtyText(r.quantity)], [t("executionPrice"), fmt(r.unitPrice, r.currencyCode, { digits: 8, min: 2 })], [t("fee"), fmt(r.fee, r.currencyCode)]);
    if (r.action === "DIVIDEND") notes.push(t("dividendOnPhone"));
    else if (r.kind === "TRADE") actions.push(btn(t("editTrade"), () => openTrade(positionId, tradeId), "primary", "pen"));
    else notes.push(t("linkedCashNote"));
    if (positionId) actions.push(btn(t("viewPosition"), () => go(`investments/positions/${positionId}`), "ghost", "arrow_right"));
  } else if (r.kind === "DEPOSIT" || r.action === "TERM_OPEN" || r.action === "TERM_CLOSE") {
    const depositId = r.kind === "DEPOSIT" ? r.id : r.sourceId;
    if (r.kind === "DEPOSIT") rows.push([t("principal"), money(r.amount, r.currencyCode)]);
    actions.push(btn(t("viewDeposit"), async () => {
      const detail = await api(`/api/v1/accounts/${r.accountId}`);
      const dep = detail.deposits.find(v => v.id === depositId);
      if (dep) depositDialog(detail, dep);
    }, "primary", "deposit"));
  } else if (isBill(r)) {
    if (r.action !== "BILL_REMOVED") notes.push(t("billOnPhone"));
  }
  return detailPanel({ back: "records", avatar: el("span", { class: `kind-icon lg ${cls}` }, sym(ic)),
    title: titleOf(r), sub: { CASH: isBill(r) ? t("billEntry") : t("cashEntry"), TRADE: t("tradeRecord"), DEPOSIT: t("depositRecord") }[r.kind],
    total: el("div", {}, el("div", { class: "big" }, money(signedAmount(r), r.currencyCode, { sign: true, tone: true }))),
    body: el("div", {}, kv(rows), actions.length ? el("div", { class: "actions-row" }, actions) : null,
      notes.map(n => el("p", { class: "phone-note" }, sym("phone", "sm"), n))) });
}

function cashDialog(r) {
  const amount = input("amount", r.amount, { inputmode: "decimal" });
  const time = input("time", localInput(r.businessAtMs), { type: "datetime-local" });
  const note = input("note", r.note || "", { maxlength: 2000 });
  const ok = btn(t("save"), async () => {
    if (!validDecimal(amount.value, { signed: true, scale: digitsOf(r.currencyCode) })) return markInvalid(amount, t("enterSignedAmount"));
    if (!time.value) return markInvalid(time, t("enterTime"));
    await submit(d, ok, `/api/v1/cash-entries/${r.id}`, "PUT", write({ expectedRevision: r.revision, currencyCode: r.currencyCode,
      amount: amount.value.trim(), occurredAtMs: keptTime(time.value, r.businessAtMs), note: note.value.trim() }));
  }, "primary");
  const d = dialog({ title: t("editCashRecord"), sub: `${r.accountName} · ${r.childName}`, width: 480,
    body: el("form", { class: "form", onsubmit: e => e.preventDefault() }, field(t("amount"), amount, { affix: r.currencyCode, hint: t("signedAmountHint") }),
      field(t("time"), time), field(t("note"), note, { full: true })),
    foot: [btn(t("cancel"), () => d.close(), "ghost"), ok] });
}

/** Deleting a hand-made balance adjustment moves the balance back by its amount, as on the phone. */
async function deleteCash(r) {
  const negative = String(r.amount).startsWith("-"), zero = !/[1-9]/.test(String(r.amount));
  const abs = negative ? String(r.amount).slice(1) : r.amount;
  const message = zero ? t("deleteCashUnchanged") : t(negative ? "deleteCashRaises" : "deleteCashLowers").replace("{amount}", fmt(abs, r.currencyCode));
  if (!(await confirmDialog({ title: t("deleteCashTitle"), message, confirm: t("delete"), danger: true }))) return;
  // A tiny dialog of its own carries the save, so a lost answer is reconciled like any other.
  const ok = btn(t("delete"), () => {}, "danger tonal");
  const d = dialog({ title: t("deleteCashTitle"), body: el("p", { class: "muted", text: t("deleting") }), width: 380, foot: [ok] });
  const done = await submit(d, ok, `/api/v1/cash-entries/${r.id}`, "DELETE", write({ expectedRevision: r.revision }), { message: t("deleted") });
  if (done) go("records");
  else ok.onclick = () => submit(d, ok, `/api/v1/cash-entries/${r.id}`, "DELETE", {}).then(x => x && go("records"));
}
