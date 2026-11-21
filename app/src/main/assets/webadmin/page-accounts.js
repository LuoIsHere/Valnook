import { api, state, t } from "./core.js";
import { decAdd, decNeg } from "./decimal.js";
import { el, sym, money, fmt, gradient, btn, iconBtn, dialog, field, input, textarea, select, toggle, seg, menu, validDecimal, markInvalid,
  digitsOf, pct, $, $$, relative, toast, epochDay, localDay, confirmDialog } from "./ui.js";
import { go } from "./router.js";
import { pageHead, detailPanel, split, sparkline, emptyState, rowKeys, tabBar } from "./layout.js";
import { submit, write } from "./forms.js";
import { avatar, iconPicker } from "./icons.js";

const B = () => state.session?.baseCurrency;

/** Loads what the accounts page shows: the list with groups, the summary, ninety days of net worth and an open account. */
export async function accountsPage(r) {
  const id = r.a ? Number(r.a) : null;
  const today = new Date();
  const months = [2, 1, 0].map(back => new Date(today.getFullYear(), today.getMonth() - back, 1));
  const [data, summary, detail, ...history] = await Promise.all([api("/api/v1/accounts"), api("/api/v1/statistics/summary"),
    id ? api(`/api/v1/accounts/${id}`).catch(e => (e.code === "NOT_FOUND" ? null : Promise.reject(e))) : null,
    ...months.map(m => api(`/api/v1/statistics/history?granularity=DAILY&metric=TOTAL_ASSETS&year=${m.getFullYear()}&month=${m.getMonth() + 1}`))]);
  state.accounts = data.items; state.accountGroups = data.groups;
  const points = history.flatMap(h => h.items).filter(p => p.reliable && p.value != null && !p.future).map(p => Number(p.value));
  const head = pageHead(t("accounts"), [`${t("accountCount").replace("{n}", data.items.length)} · ${t("syncedAt").replace("{when}", relative(state.lastSync))}`],
    [btn(t("accountGroups"), () => groupsDialog(), "tonal", "layers"), btn(t("newAccount"), () => accountDialog(null), "primary", "plus")], "Accounts");
  const search = el("label", { class: "search" }, sym("search", "sm"),
    el("input", { type: "search", placeholder: t("searchAccounts"), value: state.accountQuery, "aria-label": t("searchAccounts"),
      oninput: e => { state.accountQuery = e.target.value; $(".split-main .acc-list").replaceWith(accountList(data, id)); } }));
  const list = el("div", {}, hero(data, summary, points), el("div", { class: "toolbar reveal" }, search),
    el("div", { class: "reveal" }, accountList(data, id)));
  return [head, el("div", { class: "page" }, split(list, detail ? accountDetail(detail, data) : null))];
}

/** One large net-worth tile with its trend, and four tiles for what it is made of. */
function hero(data, s, points) {
  const sum = key => decAdd(...data.items.map(v => v[key] ?? "0"));
  const cash = s.availableCash ?? sum("availableCash"), deposits = sum("deposits"), investments = s.investmentValue ?? sum("investments");
  const credit = sum("creditBalance");
  const gross = Math.max(1, Number(cash) + Number(deposits) + Number(investments));
  const tile = (label, value, color, share, foot, negative = false) => el("section", { class: "tile" },
    el("div", { class: "tile-k" }, el("span", { class: "dot", css: { background: color } }), label),
    el("div", { class: "tile-v" }, money(value, B(), { count: true, tone: negative })),
    el("div", { class: "tile-foot" }, el("div", { class: "meter" }, el("i", { css: { width: `${Math.min(100, Math.max(0, share))}%`, background: color } })), el("span", { text: foot })));
  const share = v => `${t("shareOf")} ${Math.round(Number(v) / gross * 100)}%`;
  const monthly = s.monthlyChange;
  return el("div", { class: "bento" },
    el("section", { class: "tile tile-hero" },
      el("div", { class: "tile-k" }, t("totalAssets"), s.complete === false ? el("span", { class: "tag att", text: t("missingRates") }) : null),
      gradient(el("div", { class: "hero-num" }, money(s.totalAssets, B(), { count: true }))),
      el("div", { class: "hero-delta" }, monthly == null ? null : el("span", { class: `tag ${Number(monthly) >= 0 ? "pos" : "neg"}` },
        el("span", { class: "mask", text: `${t("thisMonth")} ${fmt(monthly, B(), { sign: true })}` })), t("last90Days")),
      el("div", { class: "hero-chart" }, sparkline(points))),
    tile(t("availableCash"), cash, "var(--cat-2)", Number(cash) / gross * 100, share(cash)),
    tile(t("deposits"), deposits, "var(--cat-1)", Number(deposits) / gross * 100, share(deposits)),
    tile(t("investments"), investments, "var(--cat-3)", Number(investments) / gross * 100, share(investments)),
    tile(t("creditDebt"), credit, "var(--loss)", 0, Number(credit) < 0 ? t("owed") : t("noDebt"), true));
}

function accountList(data, selected) {
  const q = state.accountQuery.trim().toLowerCase();
  const match = a => !q || `${a.name} ${a.note}`.toLowerCase().includes(q);
  const list = el("div", { class: "list acc-list", role: "listbox", "aria-label": t("accounts") },
    el("div", { class: "list-head cols-accounts" }, el("span", { text: t("account") }), el("span", { class: "r opt", text: t("cashAndCredit") }),
      el("span", { class: "r opt", text: t("deposits") }), el("span", { class: "r opt", text: t("investments") }), el("span", { class: "r", text: t("total") })));
  // As in the app: group headers appear once a custom group exists, and empty groups are left out.
  const grouped = data.groups.some(g => !g.isDefault);
  const byId = new Map(data.items.map(a => [a.id, a]));
  let any = false;
  for (const g of data.groups) {
    const accs = g.accountIds.map(id => byId.get(id)).filter(Boolean).filter(match);
    if (!accs.length) continue;
    any = true;
    // Collapsing is this browser's view only; the phone keeps its own.
    const collapsed = !q && (state.collapsedGroups.get(g.id) ?? g.collapsed);
    if (grouped) list.append(el("button", { type: "button", class: "group-head", "aria-expanded": String(!collapsed),
      onclick: () => { state.collapsedGroups.set(g.id, !collapsed); $(".split-main .acc-list").replaceWith(accountList(data, selected)); } },
      sym("chevron_down", "sm chev"), groupName(g), el("span", { class: "count", text: `${accs.length}` }),
      el("span", { class: "total" }, money(g.total, B()), g.complete ? null : el("span", { class: "tag att", text: "!" }))));
    if (!collapsed) accs.forEach(a => list.append(accountRow(a, selected)));
  }
  if (!any) list.append(emptyState("search", q ? t("noAccountMatch").replace("{q}", state.accountQuery) : t("noAccounts")));
  return list;
}
const groupName = g => (g.isDefault ? t("defaultGroup") : g.name);
const cell = v => (v == null || Number(v) === 0 ? el("span", { class: "faint", text: "—" }) : money(v, B(), { tone: Number(v) < 0 }));
function accountRow(a, selected) {
  return rowKeys(el("div", { class: "row cols-accounts", role: "option", "data-key": String(a.id), "aria-selected": String(a.id === selected) },
    el("div", { class: "ident" }, avatar(a.icon), el("div", {}, el("strong", { text: a.name }), a.note ? el("div", { class: "sub", text: a.note }) : null)),
    el("span", { class: "r opt" }, cell(decAdd(a.availableCash ?? a.cash, a.creditBalance ?? "0"))), el("span", { class: "r opt" }, cell(a.deposits)),
    el("span", { class: "r opt" }, cell(a.investments)),
    el("span", { class: "r" }, el("strong", {}, money(a.total, B(), { tone: Number(a.total) < 0 })), a.complete ? null : el("div", {}, el("span", { class: "tag att", text: t("missingRate") })))),
  () => go(selected === a.id ? "accounts" : `accounts/${a.id}`));
}

/* ---------- Detail ---------- */
function accountDetail(detail, data) {
  const a = detail.account, row = data.items.find(v => v.id === a.id);
  const held = detail.positions.filter(p => Number(p.quantity) > 0);
  // Switching tabs swaps only the panel's content: no reload, and the panel itself stays still.
  const content = tab => tab === "subs" ? subsTab(detail) : tab === "deposits" ? depositsTab(detail) : positionsTab(held);
  const bar = tab => tabBar([["subs", t("subaccounts"), detail.cash.length], ["deposits", t("deposits"), detail.deposits.filter(d => !d.closed).length],
    ["positions", t("positions"), held.length]], tab, v => {
    if (v === state.accountTab) return;
    state.accountTab = v;
    const next = bar(v); tabs.replaceWith(next); tabs = next;
    pane.replaceChildren(content(v)); pane.classList.remove("tab-swap"); void pane.offsetWidth; pane.classList.add("tab-swap");
  });
  let tabs = bar(state.accountTab);
  const pane = el("div", {}, content(state.accountTab));
  const body = el("div", {}, tabs, pane);
  const more = iconBtn("more", t("moreActions"), e => menu(e.currentTarget, [
    { label: t("editAccount"), icon: "pen", run: () => accountDialog(detail) },
    { label: t("addSubaccount"), icon: "plus", run: () => subDialog(detail, null) },
    { label: t("openDeposit"), icon: "deposit", run: () => depositDialog(detail, null) }, "-",
    { label: t("deleteAccountMenu"), icon: "delete", danger: true, run: () => deleteDialog(a.id, null) }]));
  return detailPanel({ back: "accounts", avatar: avatar(a.icon, "lg"), title: a.name, sub: a.note || t("subaccountCount").replace("{n}", detail.cash.length),
    actions: [iconBtn("pen", t("editAccount"), () => accountDialog(detail)), more],
    total: el("div", {}, el("div", { class: "muted small", text: t("accountTotalBase") }),
      el("div", { class: "big" }, row ? money(row.total, B(), { tone: Number(row.total) < 0 }) : "—")),
    body });
}
function subsTab(detail) {
  return el("div", { class: "sub-grid" }, detail.cash.map(x => subCard(detail, x)),
    el("button", { type: "button", class: "add-tile", onclick: () => subDialog(detail, null) }, sym("plus", "sm"), t("addSubaccount")));
}
function subCard(detail, x) {
  return el("article", { class: "sub-card" },
    el("div", { class: "top" }, el("strong", { text: x.name }), el("span", { class: "tag", text: x.type === "CREDIT" ? t("credit") : t("savings") }),
      el("span", { class: "faint small", text: x.currencyCode }), x.showOnAccountsPage === false ? el("span", { class: "tag", text: t("hiddenOnAccounts") }) : null,
      x.type === "SAVINGS" && x.includeInAvailableCash === false ? el("span", { class: "tag", text: t("notAvailableCash") }) : null,
      el("div", { class: "acts" }, iconBtn("pen", `${t("edit")} ${x.name}`, () => subDialog(detail, x)),
        iconBtn("delete", `${t("delete")} ${x.name}`, () => deleteDialog(detail.account.id, x.id), "danger"))),
    el("div", { class: "amt" }, money(x.balance, x.currencyCode, { tone: x.type === "CREDIT" })),
    x.type === "CREDIT" && x.credit ? creditBlock(x, detail) : null,
    x.note ? el("p", { class: "note", text: x.note }) : null);
}
function creditBlock(x, detail) {
  const c = x.credit, used = c.used ?? "0", total = c.totalLimit ?? c.creditLimit ?? "0", usedPct = Math.min(100, pct(used, total));
  const due = c.dueRule?.type === "AFTER_STATEMENT_DAYS" ? t("dueAfterDays").replace("{n}", c.dueRule.value) : t("dueOnDay").replace("{n}", c.dueRule?.value);
  const source = c.limitSourceAccountId ? detail.cash.find(v => v.id === c.limitSourceAccountId) : null;
  const sharing = detail.cash.filter(v => v.credit?.limitSourceAccountId === x.id).length;
  return el("div", { class: "limit" },
    el("div", { class: "bar", role: "img", "aria-label": `${t("limitUsed")} ${Math.round(usedPct)}%` }, el("i", { class: usedPct > 80 ? "hot" : "", css: { width: `${usedPct}%` } })),
    el("div", { class: "meta" }, el("span", {}, `${t("used")} `, money(used, x.currencyCode), " / ", money(total, x.currencyCode)),
      el("span", {}, `${t("availableLimit")} `, money(c.available ?? "0", x.currencyCode))),
    el("div", { class: "meta" }, el("span", { text: `${t("statementOn").replace("{n}", c.statementDay)} · ${due}` }),
      source ? el("span", { text: t("sharesLimitOf").replace("{name}", source.name) }) : sharing ? el("span", { text: t("sharedWith").replace("{n}", sharing) }) : null));
}
function depositStatus(d) {
  if (d.closed) return el("span", { class: "tag", text: t("settled") });
  const left = Math.ceil((new Date(`${d.endDate}T00:00:00`) - Date.now()) / 86400000);
  if (left <= 0) return el("span", { class: "tag att", text: t("maturedUnsettled") });
  if (left <= 90) return el("span", { class: "tag att", text: t("maturesIn").replace("{n}", left) });
  return el("span", { class: "tag pos", text: t("depositActive") });
}
function depositsTab(detail) {
  const sorted = [...detail.deposits].sort((x, y) => (x.closed - y.closed) || x.endDate.localeCompare(y.endDate));
  return el("div", {},
    sorted.length ? sorted.map(d => {
      const s = new Date(`${d.startDate}T00:00:00`), e = new Date(`${d.endDate}T00:00:00`);
      const progress = Math.max(0, Math.min(1, (Date.now() - s) / (e - s || 1)));
      return rowKeys(el("div", { class: "mini-row" }, el("span", { class: "kind-icon dep" }, sym("deposit", "sm")),
        el("div", { class: "grow" }, el("strong", {}, money(d.principal, d.currencyCode), ` · ${t("annualRate")} ${d.annualRatePercent}%`),
          el("div", { class: "sub", text: `${d.startDate} – ${d.endDate}` }), d.closed ? null : el("div", { class: "term" }, el("i", { css: { width: `${progress * 100}%` } }))),
        el("div", { class: "r" }, depositStatus(d), el("div", { class: "small faint" }, `${t("interest")} `, money(d.expectedInterest, d.currencyCode)))),
      () => depositDialog(detail, d));
    }) : emptyState("deposit", t("noDeposits")),
    el("button", { type: "button", class: "add-tile spaced", onclick: () => depositDialog(detail, null) }, sym("plus", "sm"), t("openDeposit")));
}
function positionsTab(held) {
  if (!held.length) return emptyState("investments", t("noPositionsHere"));
  return el("div", {}, held.map(p => rowKeys(el("div", { class: "mini-row" },
    el("span", { class: "kind-icon" }, sym("investments", "sm")),
    el("div", { class: "grow" }, el("strong", { text: p.name }), el("div", { class: "sub", text: `${p.symbol} · ${p.quantity}` })),
    el("div", { class: "r" }, el("strong", {}, money(p.marketValue, p.currencyCode)), el("div", { class: "small" }, money(p.unrealized, p.currencyCode, { sign: true, tone: true }))),
    sym("chevron_right", "sm faint")), () => go(`investments/positions/${p.id}`))));
}

/* ---------- Dialogs ---------- */
const currencyOk = c => /^[A-Z]{3}$/.test(c);
/** What the account save sends for a subaccount left as it is. */
const unchanged = v => ({ cashAccountId: v.id, expectedRevision: v.revision, currencyCode: v.currencyCode, balance: v.balance, name: v.name,
  note: v.note || "", type: v.type, showOnAccountsPage: v.showOnAccountsPage, includeInAvailableCash: v.includeInAvailableCash,
  credit: v.type === "CREDIT" && v.credit ? { limitSourceAccountId: v.credit.limitSourceAccountId ?? null, creditLimit: v.credit.creditLimit ?? "",
    statementDay: v.credit.statementDay, dueRule: v.credit.dueRule } : null });

export function accountDialog(detail) {
  const a = detail?.account, isNew = !a;
  const picker = iconPicker(a?.icon);
  const name = input("name", a?.name || "", { maxlength: 40 });
  const note = textarea("note", a?.note || "");
  const showDeposits = toggle(t("showDeposits"), "showDeposits", a?.showDepositSummary ?? true);
  const showInvest = toggle(t("showInvestments"), "showInvestments", a?.showInvestmentSummary ?? true);
  const form = el("form", { class: "form", onsubmit: e => e.preventDefault() },
    field(t("accountName"), name, { full: true }), el("div", { class: "full" }, picker.element),
    field(t("note"), note, { full: true }), el("div", { class: "full" }, showDeposits, showInvest));
  let first = null;
  if (isNew) {
    first = { name: input("subName", t("defaultSubName")), cur: input("cur", B() || "CNY", { maxlength: 3, class: "upper" }), bal: input("bal", "0", { inputmode: "decimal" }) };
    form.append(el("p", { class: "form-sec", text: t("firstSubaccount") }), field(t("subaccountName"), first.name), field(t("currency"), first.cur, { hint: t("currencyHint") }),
      field(t("openingBalance"), first.bal, { full: true }));
  }
  const ok = btn(isNew ? t("createAccount") : t("save"), () => save(), "primary");
  const d = dialog({ title: isNew ? t("newAccount") : t("editAccount"), sub: isNew ? t("newAccountSub") : a.name, width: 620, body: form,
    foot: [btn(t("cancel"), () => d.close(), "ghost"), ok] });
  async function save() {
    if (!name.value.trim()) return markInvalid(name, t("enterName"));
    const body = write({ expectedRevision: a?.revision ?? null, name: name.value.trim(), note: note.value.trim(),
      showDepositSummary: $("input", showDeposits).checked, showInvestmentSummary: $("input", showInvest).checked,
      cashChanges: (detail?.cash || []).map(unchanged) });
    if (first) {
      const c = first.cur.value.trim().toUpperCase();
      if (!first.name.value.trim()) return markInvalid(first.name, t("enterName"));
      if (!currencyOk(c)) return markInvalid(first.cur, t("enterCurrency"));
      if (!validDecimal(first.bal.value, { signed: true, scale: digitsOf(c) })) return markInvalid(first.bal, t("enterAmountDigits").replace("{n}", digitsOf(c)));
      body.cashChanges.push({ cashAccountId: null, expectedRevision: null, currencyCode: c, balance: first.bal.value.trim(), name: first.name.value.trim(), note: "", type: "SAVINGS", credit: null });
    }
    try { body.iconChange = await picker.value(); } catch (e) { d.fail(e.message); return; }
    await submit(d, ok, isNew ? "/api/v1/accounts" : `/api/v1/accounts/${a.id}`, isNew ? "POST" : "PUT", body, { message: isNew ? t("accountCreated") : t("saved") });
  }
}

export function subDialog(detail, x) {
  const a = detail.account, isNew = !x;
  let type = x?.type || "SAVINGS";
  const typeSeg = seg([{ value: "SAVINGS", label: t("savings") }, { value: "CREDIT", label: t("credit") }], type, v => { type = v; sync(); }, "lg");
  const name = input("name", x?.name || "", { maxlength: 40 });
  const cur = input("cur", x?.currencyCode || B() || "CNY", { maxlength: 3, readOnly: !isNew && x.currencyLocked, class: "upper" });
  const bal = input("bal", x ? x.balance : "0", { inputmode: "decimal" });
  const note = textarea("note", x?.note || "", { rows: 2 });
  const visible = toggle(t("showOnAccounts"), "visible", x?.showOnAccountsPage ?? true, t("showOnAccountsHint"));
  const available = toggle(t("includeAvailable"), "available", x?.includeInAvailableCash ?? true, t("includeAvailableHint"));
  const candidates = () => (detail.creditSourceCandidates || []).filter(v => v.id !== x?.id && v.currencyCode === cur.value.trim().toUpperCase());
  const source = select("source", [{ value: "", label: t("independentLimit") }, ...candidates().map(v => ({ value: v.id, label: t("shareLimitOf").replace("{name}", v.name) }))],
    x?.credit?.limitSourceAccountId ?? "");
  const limit = input("limit", x?.credit?.creditLimit ?? "10000", { inputmode: "decimal" });
  const stmt = input("stmt", String(x?.credit?.statementDay ?? 12), { inputmode: "numeric", maxlength: 2 });
  let dueType = x?.credit?.dueRule?.type || "AFTER_STATEMENT_DAYS";
  const dueValue = input("due", String(x?.credit?.dueRule?.value ?? 20), { inputmode: "numeric", maxlength: 3 });
  const dueField = field(t("dueDaysLabel"), dueValue);
  const dueSeg = seg([{ value: "AFTER_STATEMENT_DAYS", label: t("dueAfter") }, { value: "FIXED_DAY_OF_MONTH", label: t("dueFixed") }], dueType, v => { dueType = v; sync(); });
  const balField = field(t("balance"), bal);
  const savingsBox = el("div", { class: "full" }, visible, available);
  const creditBox = el("div", { class: "form full" },
    el("p", { class: "form-sec", text: t("creditSettings") }), field(t("limitSource"), source), field(t("creditLimit"), limit),
    field(t("statementDay"), stmt), el("div", { class: "fld" }, el("span", { class: "lbl", text: t("dueRule") }), dueSeg), dueField,
    el("div", { class: "full" }, toggle(t("showOnAccounts"), "visible2", x?.showOnAccountsPage ?? true)));
  const form = el("form", { class: "form", onsubmit: e => e.preventDefault() },
    isNew ? el("div", { class: "fld full" }, el("span", { class: "lbl", text: t("type") }), typeSeg) : null,
    field(t("name"), name), field(t("currency"), cur, { hint: isNew ? t("currencyHint") : x.currencyLocked ? t("currencyLockedHint") : "" }),
    balField, field(t("note"), note, { full: true }), savingsBox, creditBox);
  function sync() {
    savingsBox.hidden = type !== "SAVINGS"; creditBox.hidden = type !== "CREDIT";
    $("label", balField).textContent = type === "CREDIT" ? t("creditBalanceLabel") : t("balance");
    limit.readOnly = !!source.value;
    $("label", dueField).textContent = dueType === "AFTER_STATEMENT_DAYS" ? t("dueDaysLabel") : t("dueDayLabel");
  }
  source.addEventListener("change", sync); sync();
  const ok = btn(isNew ? t("add") : t("save"), () => save(), "primary");
  const d = dialog({ title: isNew ? t("addSubaccount") : t("editSubaccount"), sub: isNew ? t("addTo").replace("{name}", a.name) : `${a.name} · ${x.name}`,
    width: 560, body: form, foot: [btn(t("cancel"), () => d.close(), "ghost"), ok] });
  async function save() {
    const c = cur.value.trim().toUpperCase();
    if (!name.value.trim()) return markInvalid(name, t("enterName"));
    if (!currencyOk(c)) return markInvalid(cur, t("enterCurrency"));
    if (!validDecimal(bal.value, { signed: true, scale: digitsOf(c) })) return markInvalid(bal, t("enterAmountDigits").replace("{n}", digitsOf(c)));
    if (type === "CREDIT") {
      if (!source.value && !validDecimal(limit.value, { scale: digitsOf(c), positive: true })) return markInvalid(limit, t("enterLimit"));
      if (!/^(?:[1-9]|[12]\d|3[01])$/.test(stmt.value)) return markInvalid(stmt, t("statementRange"));
      const max = dueType === "FIXED_DAY_OF_MONTH" ? 31 : 365, n = Number(dueValue.value);
      if (!Number.isInteger(n) || n < 1 || n > max) return markInvalid(dueValue, t("rangeOf").replace("{max}", max));
    }
    const shown = $("input", type === "CREDIT" ? $$(".switch-row", creditBox).at(-1) : visible).checked;
    const edited = { cashAccountId: x?.id ?? null, expectedRevision: x?.revision ?? null, currencyCode: c, balance: bal.value.trim(), name: name.value.trim(),
      note: note.value.trim(), type, showOnAccountsPage: shown, includeInAvailableCash: type === "SAVINGS" ? $("input", available).checked : false,
      credit: type === "CREDIT" ? { limitSourceAccountId: source.value ? Number(source.value) : null, creditLimit: source.value ? "" : limit.value.trim(),
        statementDay: Number(stmt.value), dueRule: { type: dueType, value: Number(dueValue.value) } } : null };
    const cashChanges = detail.cash.map(v => (v.id === x?.id ? edited : unchanged(v)));
    if (isNew) cashChanges.push(edited);
    await submit(d, ok, `/api/v1/accounts/${a.id}`, "PUT", write({ expectedRevision: a.revision, name: a.name, note: a.note || "",
      showDepositSummary: a.showDepositSummary, showInvestmentSummary: a.showInvestmentSummary, cashChanges }),
      { message: isNew ? t("subaccountAdded") : t("saved") });
    state.accountTab = "subs";
  }
}

/** Deleting asks the phone for the scope and a confirmation code; closing without deleting gives the code back. */
export async function deleteDialog(accountId, balanceAccountId) {
  const p = await api("/api/v1/account-deletion-preview", { method: "POST", body: JSON.stringify({ accountId, balanceAccountId }) });
  let deleted = false;
  const codeInput = input("code", "", { inputmode: "numeric", maxlength: 6, placeholder: t("enterCodeAbove"), autofocus: true });
  const body = el("div", { class: "stack" },
    el("div", { class: "impact" }, [[t("subaccounts"), p.cashCount], [t("cashEntries"), p.entryCount], [t("deposits"), p.depositCount], [t("trades"), p.tradeCount]]
      .map(([k, v]) => el("div", {}, el("b", { text: String(v) }), el("span", { text: k })))),
    p.billCount > 0 ? el("div", { class: "callout neu" }, sym("bills", "sm"), el("div", { text: `${p.billCount} ${t("billsKept")}` })) : null,
    el("div", { class: "callout neg" }, sym("alert", "sm"), el("div", { text: t(balanceAccountId == null ? "deleteParentScope" : "deleteChildScope") })),
    p.transfers?.length ? el("div", { class: "callout" }, sym("info", "sm"), el("div", { text: t("deleteLimitNotice") })) : null,
    el("div", {}, el("p", { class: "small muted spaced-b", text: t("typeCodeToConfirm") }), el("div", { class: "code-box", text: p.code })),
    field(t("deletionCode"), codeInput));
  const del = btn(t("deleteForever"), async () => {
    const done = await submit(d, del, balanceAccountId == null ? `/api/v1/accounts/${accountId}` : `/api/v1/balance-accounts/${balanceAccountId}`, "DELETE",
      write({ dataGeneration: p.dataGeneration, accountId, expectedRevision: p.revision, ticket: p.ticket, code: codeInput.value.trim() }),
      { message: t("deletedName").replace("{name}", p.name) });
    if (done) { deleted = true; if (balanceAccountId == null) go("accounts"); }
  }, "danger tonal", "delete");
  del.disabled = true;
  codeInput.addEventListener("input", () => { del.disabled = codeInput.value.trim() !== p.code; });
  const d = dialog({ title: balanceAccountId == null ? t("deleteAccountTitle").replace("{name}", p.name) : t("deleteSubaccountTitle").replace("{name}", p.name),
    sub: t("syncsToPhone"), width: 500, body, foot: [btn(t("cancel"), () => d.close(true), "ghost"), del],
    onClose: () => { if (!deleted) api("/api/v1/account-deletion-cancel", { method: "POST", body: JSON.stringify({ ticket: p.ticket }) }).catch(() => {}); } });
}

export function depositDialog(detail, dep) {
  const a = detail.account;
  const savings = detail.cash.filter(v => v.type === "SAVINGS" && (!dep || v.currencyCode === dep.currencyCode));
  const cur = input("cur", dep?.currencyCode || B() || "CNY", { maxlength: 3, readOnly: !!dep, class: "upper" });
  const principal = input("principal", dep?.principal || "", { inputmode: "decimal", readOnly: dep?.closed });
  const rate = input("rate", dep?.annualRatePercent || "", { inputmode: "decimal", readOnly: dep?.closed });
  const start = input("start", dep?.startDate || localDay(new Date()), { type: "date", readOnly: dep?.closed });
  const end = input("end", dep?.endDate || localDay(new Date(new Date().setFullYear(new Date().getFullYear() + 1))), { type: "date", readOnly: dep?.closed });
  const cashOptions = [{ value: "", label: t("noCashLink") }, ...savings.map(v => ({ value: v.id, label: `${v.name} · ${v.currencyCode}` }))];
  const openSub = select("openSub", cashOptions, dep ? (dep.openCashLinked ? dep.openCashAccountId : "") : savings[0]?.id ?? "");
  const closeSub = select("closeSub", cashOptions, dep ? (dep.closeCashLinked ? dep.closeCashAccountId : "") : "");
  const form = el("form", { class: "form", onsubmit: e => e.preventDefault() },
    field(t("currency"), cur), field(t("principal"), principal), field(t("annualRatePct"), rate), el("span"),
    field(t("startDate"), start), field(t("maturityDate"), end),
    field(t("openFrom"), openSub, { hint: t("openFromHint") }), dep && !dep.closed ? field(t("settleTo"), closeSub, { hint: t("settleToHint") }) : null);
  const ok = btn(dep ? t("save") : t("openDeposit"), () => save(), "primary");
  const settle = dep && !dep.closed ? btn(t("settle"), () => close(), "tonal") : null;
  const d = dialog({ title: dep ? (dep.closed ? t("viewDeposit") : t("editDeposit")) : t("openDeposit"), sub: a.name, width: 560, body: form,
    foot: [btn(t("cancel"), () => d.close(), "ghost"), settle, dep?.closed ? null : ok] });
  function valid() {
    const c = cur.value.trim().toUpperCase();
    if (!currencyOk(c)) return markInvalid(cur, t("enterCurrency"));
    if (!validDecimal(principal.value, { scale: digitsOf(c), positive: true })) return markInvalid(principal, t("enterAmountDigits").replace("{n}", digitsOf(c)));
    if (!validDecimal(rate.value, { scale: 8 })) return markInvalid(rate, t("enterRate"));
    if (!(end.value > start.value)) return markInvalid(end, t("endAfterStart"));
    return true;
  }
  async function save() {
    if (!valid()) return;
    const base = { currencyCode: cur.value.trim().toUpperCase(), principal: principal.value.trim(), annualRatePercent: rate.value.trim(),
      startEpochDay: epochDay(start.value), endEpochDay: epochDay(end.value) };
    if (dep) await submit(d, ok, `/api/v1/deposits/${dep.id}`, "PUT", write({ ...base, expectedRevision: dep.revision, openCashLinked: !!openSub.value,
      openCashAccountId: openSub.value ? Number(openSub.value) : null, closeCashLinked: !!closeSub.value, closeCashAccountId: closeSub.value ? Number(closeSub.value) : null }));
    else await submit(d, ok, "/api/v1/deposits", "POST", write({ ...base, accountId: a.id, cashLinked: !!openSub.value, cashAccountId: openSub.value ? Number(openSub.value) : null }),
      { message: t("depositOpened") });
  }
  async function close() {
    const target = closeSub.value ? savings.find(v => String(v.id) === closeSub.value)?.name : null;
    if (!(await confirmDialog({ title: t("settleTitle"), message: target ? t("settleIntoMessage").replace("{name}", target) : t("settleOnlyMessage"), confirm: t("settle") }))) return;
    await submit(d, settle, `/api/v1/deposits/${dep.id}/close`, "POST", write({ cashLinked: !!closeSub.value, cashAccountId: closeSub.value ? Number(closeSub.value) : null }),
      { message: t("depositSettled") });
  }
}

/** Group management saves each change at once and redraws itself; a lost answer is looked up, never resent. */
export async function groupsDialog() {
  let data = await api("/api/v1/accounts");
  const body = el("div", { class: "stack", "data-nodirty": "" });
  const d = dialog({ title: t("accountGroups"), sub: t("accountGroupsHint"), width: 560, body, foot: [btn(t("done"), () => d.close(true), "primary")] });
  const byId = () => new Map(data.items.map(a => [a.id, a]));
  async function apply(path, method, payload, focusKey) {
    const ok = await submit(d, d.footer.querySelector(".btn.primary"), path, method, write(payload), { keepOpen: true, message: "" });
    if (!ok) return;
    data = await api("/api/v1/accounts"); draw();
    if (focusKey) $(`[data-move="${focusKey}"]`, body)?.focus();
  }
  const moves = (ids, index, key, path, field) => [[-1, "moveUp", "up"], [1, "moveDown", "down"]].map(([step, label, icon]) => {
    const target = index + step, b = iconBtn(icon === "up" ? "chevron_left" : "chevron_right", t(label), () => {
      const next = ids.slice(); [next[index], next[target]] = [next[target], next[index]];
      return apply(path, "PUT", { [field]: next }, `${key}:${step}`);
    }, "rot90");
    b.dataset.move = `${key}:${step}`; b.disabled = target < 0 || target >= ids.length; return b;
  });
  function draw() {
    const accounts = byId(), groupIds = data.groups.map(g => g.id);
    body.replaceChildren(
      ...data.groups.map((g, i) => el("section", { class: "group-box" },
        el("div", { class: "group-box-head" }, el("strong", { text: groupName(g) }), el("span", { class: "faint small", text: `${g.accountIds.length}` }), el("span", { class: "grow" }),
          ...moves(groupIds, i, `g${g.id}`, "/api/v1/account-groups/order", "groupIds"),
          iconBtn("plus", t("addAccounts"), () => addAccounts(g)),
          g.isDefault ? null : iconBtn("pen", t("rename"), () => rename(g)),
          g.isDefault ? null : iconBtn("delete", t("deleteAccountGroup"), async () => {
            if (!(await confirmDialog({ title: t("deleteGroupTitle").replace("{name}", g.name), message: t("deleteGroupMessage").replace("{name}", g.name).replace("{n}", g.accountIds.length), confirm: t("delete"), danger: true }))) return;
            await apply(`/api/v1/account-groups/${g.id}`, "DELETE", {});
          }, "danger")),
        g.accountIds.length ? g.accountIds.map((id, j) => el("div", { class: "group-item" }, avatar(accounts.get(id)?.icon), el("span", { class: "grow", text: accounts.get(id)?.name || `#${id}` }),
          ...moves(g.accountIds, j, `a${id}`, `/api/v1/account-groups/${g.id}/accounts/order`, "accountIds")))
          : el("p", { class: "faint small", text: t("groupEmpty") }))),
      el("button", { type: "button", class: "add-tile", onclick: () => rename(null) }, sym("plus", "sm"), t("newAccountGroup")));
  }
  function rename(g) {
    const name = input("name", g?.name || "", { maxlength: 20 });
    const ok = btn(t("save"), async () => {
      const length = [...name.value.trim()].length;
      if (length < 1 || length > 20) return markInvalid(name, t("groupNameInvalid"));
      await submit(inner, ok, g ? `/api/v1/account-groups/${g.id}` : "/api/v1/account-groups", g ? "PUT" : "POST", write({ name: name.value.trim() }));
      data = await api("/api/v1/accounts"); draw();
    }, "primary");
    const inner = dialog({ title: g ? t("renameAccountGroup") : t("newAccountGroup"), width: 420, body: field(t("groupName"), name), foot: [btn(t("cancel"), () => inner.close(), "ghost"), ok] });
  }
  function addAccounts(g) {
    const names = new Map(data.groups.map(v => [v.id, groupName(v)]));
    const candidates = data.items.filter(a => a.groupId !== g.id);
    const boxes = candidates.map(a => el("input", { type: "checkbox", value: a.id }));
    const ok = btn(t("add"), async () => {
      const ids = boxes.filter(b => b.checked).map(b => Number(b.value));
      if (!ids.length) { inner.fail(t("addAccountsHint")); return; }
      await submit(inner, ok, `/api/v1/account-groups/${g.id}/accounts`, "POST", write({ accountIds: ids }));
      data = await api("/api/v1/accounts"); draw();
    }, "primary");
    ok.disabled = !candidates.length;
    const inner = dialog({ title: t("addAccountsTo").replace("{name}", groupName(g)), sub: t(candidates.length ? "addAccountsHint" : "noOtherAccounts"), width: 460,
      body: el("div", { class: "stack" }, candidates.map((a, i) => el("label", { class: "check-row" }, boxes[i], el("span", { text: t("accountOrigin").replace("{name}", a.name).replace("{group}", names.get(a.groupId)) })))),
      foot: [btn(t("cancel"), () => inner.close(), "ghost"), ok] });
  }
  draw();
}
