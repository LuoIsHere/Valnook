import { el, clear, fmt, when, epochDay, gainClass, accountName, uuid, restoreSessionCredentials, rememberSessionCredentials, clearSessionCredentials, api, $, state, hooks, t } from "./core.js";
import { symbol, avatar, iconPicker } from "./icons.js";
async function preload() {
  const [accounts, instruments, types, positions] = await Promise.all([
    api("/api/v1/accounts"), api("/api/v1/instruments"), api("/api/v1/asset-types"), api("/api/v1/positions")
  ]);
  state.accounts = accounts.items; state.instruments = instruments.items; state.assetTypes = types.items; state.positions = positions.items;
}
function renderNav() {
  const nav = $("#nav"); clear(nav);
  [["accounts", "account_balance_wallet"], ["records", "payments"], ["investments", "trending_up"], ["statistics", "monitoring"]].forEach(([key, icon]) => {
    nav.append(el("button", { class: `nav-item ${state.page === key ? "active" : ""}`, type: "button", onclick: () => navigate(key), "aria-label": t(key), "aria-current": state.page === key ? "page" : null },
      symbol(icon), el("span", { class: "nav-label", text: t(key) })));
  });
}
async function navigate(page) {
  if (!closeDrawer()) return;
  state.viewEpoch++; state.page = page; renderNav();
  const epoch = state.viewEpoch; state.pageLoading = true;
  $("#page-kicker").textContent = state.session?.dataMode === "DEMO" ? `${t("demoMode")} · WEB ADMIN` : "WEB ADMIN";
  $("#page-title").textContent = t(page); showLoading();
  try {
    if (page === "accounts") await renderAccounts();
    else if (page === "records") await renderRecords(true);
    else if (page === "investments") await renderInvestments();
    else await renderStatistics();
  } catch (error) { showError(error); }
  finally { if (epoch === state.viewEpoch) state.pageLoading = false; }
}
let refreshTimer, refreshTask;
function scheduleRefresh() {
  state.pendingRefresh = true;
  if (state.saving || !$("#drawer").hidden) { if (!state.saving) notify(t("changed")); return; }
  clearTimeout(refreshTimer); refreshTimer = setTimeout(() => refreshCurrent(), 150);
}
function refreshCurrent() {
  if (state.saving || !$("#drawer").hidden) { state.pendingRefresh = true; return Promise.resolve(); }
  if (refreshTask) return refreshTask;
  state.pendingRefresh = false; state.refreshing = true;
  refreshTask = (async () => {
    const scroll = window.scrollY;
    try {
      await preload();
      if (state.saving || !$("#drawer").hidden) { state.pendingRefresh = true; return; }
      await navigate(state.page); window.scrollTo(0, scroll);
    } catch (error) { if (error.name !== "AbortError") notify(t("loadFailed")); }
  })().finally(() => {
    refreshTask = null; state.refreshing = false;
    if (state.pendingRefresh && $("#drawer").hidden && !state.saving) scheduleRefresh();
  });
  return refreshTask;
}
function showLoading() { const root = $("#content"); clear(root); root.append(el("p", { class: "muted", text: t("loading") })); }
function showError(error) { if (error.name === "AbortError") return; const root = $("#content"); clear(root); root.append(el("div", { class: "empty", text: `${t("loadFailed")}: ${error.code || error.message}` })); }
function emptyRow(cols) { return el("tr", {}, el("td", { colspan: cols, class: "empty", text: t("empty") })); }
function table(headers, rows) {
  rows.forEach(row => { if (row.dataset.clickable) { row.tabIndex = 0; row.addEventListener("keydown", event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); row.click(); } }); } });
  const body = el("tbody"); if (!rows.length) body.append(emptyRow(headers.length)); else rows.forEach(row => body.append(row));
  return el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, el("tr", {}, ...headers.map((h, i) => el("th", { text: h, scope: "col", class: rows[0]?.children[i]?.classList.contains("numeric") ? "numeric" : "" })))), body));
}
function toolbar(...nodes) { return el("div", { class: "toolbar" }, ...nodes); }
function button(label, click, kind = "secondary") { return el("button", { class: kind, type: "button", text: label, onclick: async event => {
  try { await click(event); } catch (error) { if (error.name !== "AbortError") notify(errorMessage(error)); }
} }); }
function metric(label, value, cls = "") { return el("div", { class: "metric" }, el("div", { class: "subtle", text: label }), el("div", { class: `value ${cls}`, text: value })); }

async function renderAccounts() {
  const [data, summary] = await Promise.all([api("/api/v1/accounts"), api("/api/v1/statistics/summary")]); state.accounts = data.items;
  const root = $("#content"); clear(root);
  root.append(hero(summary, "totalAssets"));
  root.append(toolbar(el("div", { class: "spacer" }), button(t("newAccount"), () => accountForm(null), "primary")));
  const rows = data.items.map(item => el("tr", { "data-clickable": "true", onclick: () => accountDetail(item.id) },
    el("td", {}, el("div", { class: "account-cell" }, avatar(item.icon), el("div", {}, el("strong", { text: item.name }), el("div", { class: "subtle", text: item.note || "—" })) )),
    el("td", { class: "numeric", text: fmt(item.cash, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.creditBalance, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.deposits, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.investments, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.total, data.baseCurrency) }),
    el("td", { text: item.complete ? "—" : "!", title: item.complete ? "" : t("incomplete") })));
  const accountTable = table([t("account"), t("cash"), t("creditBalance"), t("deposits"), t("investmentValue"), t("totalAssets"), t("complete")], rows);
  accountTable.classList.add("accounts-table"); root.append(accountTable);
  if (data.items.some(item => !item.complete)) root.append(el("p", { class: "subtle", text: t("incomplete") }));
}
async function accountDetail(id) {
  state.viewEpoch++;
  const data = await api(`/api/v1/accounts/${id}`); const a = data.account;
  const cashRows = data.cash.map(v => el("tr", {},
    el("td", { text: v.type === "CREDIT" ? t("creditAccount") : t("savingsAccount") }),
    el("td", { text: v.name }), el("td", { text: v.note || "—" }),
    el("td", { text: v.currencyCode }), el("td", { class: "numeric", text: fmt(v.balance, v.currencyCode) }),
    el("td", { class: "numeric", text: v.credit ? `${fmt(v.credit.used, v.currencyCode)} / ${fmt(v.credit.totalLimit, v.currencyCode)}` : "—" })));
  const savingsCash = data.cash.filter(v => v.type === "SAVINGS");
  const depositRows = data.deposits.map(v => el("tr", { "data-clickable": "true", onclick: () => depositForm(v, null, savingsCash) },
    el("td", { text: v.currencyCode }), el("td", { class: "numeric", text: fmt(v.principal, v.currencyCode) }),
    el("td", { text: v.startDate }), el("td", { text: v.endDate }),
    el("td", { class: "numeric", text: `${fmt(v.annualRatePercent)}%` }), el("td", { text: v.closed ? t("settled") : t("holding") })));
  const positionRows = data.positions.map(v => el("tr", { "data-clickable": "true", onclick: () => positionDetail(v.id) },
    el("td", {}, el("strong", { text: v.name }), el("div", { class: "subtle", text: v.symbol })),
    el("td", { class: "numeric", text: fmt(v.quantity) }), el("td", { class: "numeric", text: fmt(v.currentPrice, v.currencyCode) }),
    el("td", { class: "numeric", text: fmt(v.marketValue, v.currencyCode) }),
    el("td", { class: `numeric ${gainClass(v.unrealized)}`, text: fmt(v.unrealized, v.currencyCode) })));
  const content = el("div", {},
    el("div", { class: "section-head" }, el("div", {}, el("strong", { text: a.name }),
      el("div", { class: "subtle", text: a.note || "—" })), button(t("edit"), () => accountForm({ ...a, cash: data.cash, creditSourceCandidates: data.creditSourceCandidates }))),
    sectionTable(t("balanceAccounts"), [t("type"), t("name"), t("note"), t("currency"), t("balance"), t("usedLimit")], cashRows),
    sectionTable(t("deposits"), [t("currency"), t("principal"), t("start"), t("endDate"), t("annualRate"), t("status")], depositRows,
      button(t("openDeposit"), () => depositForm(null, a.id, savingsCash))),
    sectionTable(t("positions"), [t("nameCode"), t("quantity"), t("currentPrice"), t("marketValue"), t("pnl")], positionRows));
  openDrawer(t("accountDetails"), "ACCOUNT", content);
}
function sectionTable(title, headers, rows, action = null) { return el("section", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: title }), action), table(headers, rows)); }

function accountForm(account) {
  const cash = account?.cash || [];
  const picker = iconPicker(account?.icon);
  const form = el("form", { class: "form-grid" });
  form.append(picker.element);
  form.append(field(t("name"), "name", account?.name || "", true), field(t("note"), "note", account?.note || "", false, "text", true));
  cash.forEach((v, index) => {
    form.append(field(`${v.currencyCode} · ${v.name}`, `cash-${index}`, v.balance, true));
    if (v.type === "CREDIT") form.append(selectField(`${t("limitSource")} · ${v.name}`, `source-${index}`,
      [{ value: "", label: t("independentLimit") }, ...(account?.creditSourceCandidates || []).filter(s => s.accountId === account.id && s.id !== v.id && s.currencyCode === v.currencyCode)
        .map(s => ({ value: s.id, label: `${s.accountName} · ${s.name}` }))], v.credit?.limitSourceAccountId),
      field(`${t("creditLimit")} · ${v.name}`, `limit-${index}`, v.credit?.creditLimit || ""),
      field(`${t("statementDay")} · ${v.name}`, `statement-${index}`, v.credit?.statementDay || 12, true),
      selectField(`${t("dueRule")} · ${v.name}`, `dueType-${index}`, [{ value: "AFTER_STATEMENT_DAYS", label: t("dueAfter") }, { value: "FIXED_DAY_OF_MONTH", label: t("dueFixed") }], v.credit?.dueRule?.type),
      field(`${t("dueValue")} · ${v.name}`, `dueValue-${index}`, v.credit?.dueRule?.value || 20, true));
  });
  form.append(field(t("newCashName"), "newCashName", ""), field(t("newCashCurrency"), "newCashCurrency", account ? "" : (state.session.baseCurrency || "CNY"), !account),
    field(t("newCashBalance"), "newCashBalance", "0"),
    selectField(t("type"), "newCashType", [{ value: "SAVINGS", label: t("savingsAccount") }, { value: "CREDIT", label: t("creditAccount") }]),
    selectField(t("limitSource"), "newLimitSource", [{ value: "", label: t("independentLimit") }, ...(account?.creditSourceCandidates || []).filter(s => s.accountId === account?.id)
      .map(s => ({ value: s.id, label: `${s.accountName} · ${s.name}` }))]),
    field(t("creditLimit"), "newCreditLimit", "10000", true), field(t("statementDay"), "newStatementDay", "12", true),
    selectField(t("dueRule"), "newDueType", [{ value: "AFTER_STATEMENT_DAYS", label: t("dueAfter") }, { value: "FIXED_DAY_OF_MONTH", label: t("dueFixed") }]),
    field(t("dueValue"), "newDueValue", "20", true));
  const error = el("p", { class: "drawer-error" });
  form.append(error, actions(async submit => {
    const fd = new FormData(form);
    const body = { operationId: uuid(), dataGeneration: state.generation, expectedRevision: account?.revision ?? null,
      name: fd.get("name"), note: fd.get("note") || "", cashChanges: cash.map((v, i) => ({ cashAccountId: v.id, expectedRevision: v.revision, currencyCode: v.currencyCode, balance: fd.get(`cash-${i}`), name: v.name, note: v.note, type: v.type,
        credit: v.type === "CREDIT" ? { limitSourceAccountId: fd.get(`source-${i}`) ? Number(fd.get(`source-${i}`)) : null, creditLimit: fd.get(`limit-${i}`) || v.credit?.creditLimit || "", statementDay: Number(fd.get(`statement-${i}`)), dueRule: { type: fd.get(`dueType-${i}`), value: Number(fd.get(`dueValue-${i}`)) } } : null })) };
    if (String(fd.get("newCashCurrency") || "").trim()) body.cashChanges.push({ cashAccountId: null, expectedRevision: null,
      currencyCode: String(fd.get("newCashCurrency")).trim().toUpperCase(), balance: fd.get("newCashBalance") || "0",
      name: fd.get("newCashName") || String(fd.get("newCashCurrency")).trim().toUpperCase(), note: "", type: fd.get("newCashType"),
      credit: fd.get("newCashType") === "CREDIT" ? { limitSourceAccountId: fd.get("newLimitSource") ? Number(fd.get("newLimitSource")) : null, creditLimit: fd.get("newCreditLimit"), statementDay: Number(fd.get("newStatementDay")), dueRule: { type: fd.get("newDueType"), value: Number(fd.get("newDueValue")) } } : null });
    body.iconChange = await picker.value();
    await saveForm(submit, error, account ? `/api/v1/accounts/${account.id}` : "/api/v1/accounts", account ? "PUT" : "POST", body);
  }));
  const updateCredit = () => {
    const credit = form.elements.newCashType.value === "CREDIT";
    ["newLimitSource", "newCreditLimit", "newStatementDay", "newDueType", "newDueValue"].forEach(name => {
      const input = form.elements[name]; input.disabled = !credit; input.closest(".field").hidden = !credit;
    });
    const currency = form.elements.newCashCurrency.value.trim().toUpperCase();
    Array.from(form.elements.newLimitSource.options).slice(1).forEach(option => {
      const candidate = account?.creditSourceCandidates?.find(v => String(v.id) === option.value);
      option.disabled = candidate?.currencyCode !== currency;
      if (option.disabled && option.selected) form.elements.newLimitSource.value = "";
    });
  };
  form.elements.newCashType.addEventListener("change", updateCredit);
  form.elements.newCashCurrency.addEventListener("input", updateCredit); updateCredit();
  openDrawer(account ? t("editAccount") : t("newAccount"), "ACCOUNT", form);
}

async function renderRecords(reset) {
  if (reset) { state.recordsHistory = []; state.recordsCursor = null; }
  const root = $("#content");
  const oldControls = $("#record-controls");
  if (oldControls) state.recordFilters = Object.fromEntries(new FormData(oldControls));
  if (reset) clear(root);
  const requestId = state.recordsRequest = (state.recordsRequest || 0) + 1;
  let controls = $("#record-controls");
  if (!controls) {
    controls = el("form", { id: "record-controls", class: "toolbar" },
      field(t("fromDate"), "from", "", false, "date"), field(t("toDate"), "to", "", false, "date"),
      selectField(t("account"), "accountId", [{ value: "", label: t("all") }, ...state.accounts.map(v => ({ value: v.id, label: v.name }))]),
      selectField(t("type"), "type", [{ value: "", label: t("all") }, { value: "CASH", label: t("cash") }, { value: "DEPOSIT", label: t("deposit") }, { value: "TRADE", label: t("trade") }]),
      field(t("search"), "query", ""), button(t("filter"), () => renderRecords(true), "primary"));
    Object.entries(state.recordFilters).forEach(([name, value]) => { if (controls.elements[name]) controls.elements[name].value = value; });
    controls.addEventListener("submit", event => { event.preventDefault(); renderRecords(true).catch(showError); });
    root.append(controls, el("div", { id: "records-table" }));
  }
  const fd = new FormData(controls); const params = new URLSearchParams({ pageSize: "50" });
  if (fd.get("from")) params.set("from", String(new Date(`${fd.get("from")}T00:00:00`).getTime()));
  if (fd.get("to")) params.set("to", String(new Date(`${fd.get("to")}T23:59:59.999`).getTime()));
  ["accountId", "type", "query"].forEach(k => { if (fd.get(k)) params.set(k, fd.get(k)); });
  if (!reset && state.recordsCursor) params.set("cursor", state.recordsCursor);
  const data = await api(`/api/v1/records?${params}`); if (requestId !== state.recordsRequest) return; state.recordsHistory.push(...data.items); state.recordsCursor = data.nextCursor;
  const rows = state.recordsHistory.map(v => el("tr", { "data-clickable": "true", onclick: () => openRecord(v) },
    el("td", { text: when(v.businessAtMs) }), el("td", { text: v.accountName }), el("td", { text: v.childName || "—" }),
    el("td", {}, el("strong", { class: v.action === "BUY" ? "gain" : v.action === "SELL" ? "loss" : "", text: actionLabel(v.action) })),
    el("td", { text: v.objectName || "—" }), el("td", { class: "numeric", text: v.amount ? fmt(v.amount, v.currencyCode) : fmt(v.quantity) }),
    el("td", { text: v.currencyCode }), el("td", { text: v.note || "—" }), el("td", { text: when(v.updatedAtMs) }), el("td", { text: t("edit") })));
  const holder = $("#records-table"); clear(holder); holder.append(table([t("time"), t("account"), t("subaccount"), t("type"), t("object"), t("amountQuantity"), t("currency"), t("note"), t("updated"), t("operation")], rows));
  if (state.recordsCursor) holder.append(toolbar(el("div", { class: "spacer" }), button(t("loadMore"), () => renderRecords(false))));
}
function actionLabel(action) {
  return ({ BUY: t("buy"), SELL: t("sell"), CASH_SET: t("cashChange"), TERM_OPEN: t("depositOpen"), TERM_CLOSE: t("depositClose"), OPEN: t("depositOpen"), CLOSE: t("depositClose") })[action] || action;
}
async function openRecord(record) {
  state.viewEpoch++;
  try {
    if (record.kind === "CASH" && record.action === "CASH_SET") return cashRecordForm(record);
    if (record.kind === "TRADE" || (record.kind === "CASH" && record.action === "TRADE")) {
      const positionId = record.kind === "TRADE" ? record.childId : record.sourceParentId;
      const tradeId = record.kind === "TRADE" ? record.id : record.sourceId;
      const data = await api(`/api/v1/positions/${positionId}`), position = data.position;
      const account = await api(`/api/v1/accounts/${position.accountId}`);
      const trade = data.trades.find(v => v.id === tradeId);
      if (!trade) throw new Error("NOT_FOUND");
      return tradeForm(position, trade, account.cash.filter(v => v.type === "SAVINGS" && v.currencyCode === position.currencyCode));
    }
    const account = await api(`/api/v1/accounts/${record.accountId}`);
    const depositId = record.kind === "DEPOSIT" ? record.id : record.sourceId;
    const deposit = account.deposits.find(v => v.id === depositId);
    if (!deposit) throw new Error("NOT_FOUND");
    return depositForm(deposit, null, account.cash);
  } catch (error) { showError(error); }
}
function cashRecordForm(record) {
  const form = el("form", { class: "form-grid" });
  form.append(field(t("amount"), "amount", record.amount || "0", true), field(t("currency"), "currency", record.currencyCode, true, "text", false, true), field(t("time"), "occurred", toLocalDateTime(record.businessAtMs), true, "datetime-local"), field(t("note"), "note", record.note || "", false, "text", true));
  const error = el("p", { class: "drawer-error" }); form.append(error, actions(async submit => {
    const fd = new FormData(form); await saveForm(submit, error, `/api/v1/cash-entries/${record.id}`, "PUT", {
      operationId: uuid(), dataGeneration: state.generation, expectedRevision: record.revision, currencyCode: record.currencyCode,
      amount: fd.get("amount"), occurredAtMs: new Date(fd.get("occurred")).getTime(), note: fd.get("note") || ""
    });
  })); openDrawer(t("editCashRecord"), "RECORD", form);
}

async function renderInvestments() {
  const [, summary] = await Promise.all([preload(), api("/api/v1/statistics/summary")]); const root = $("#content"); clear(root);
  root.append(hero(summary, "investmentValue"));
  const tabs = el("div", { class: "tabs" }, ...[["instruments", t("instruments")], ["positions", t("positions")]].map(([key, label]) => el("button", { type: "button", class: `tab ${state.investmentTab === key ? "active" : ""}`, text: label, onclick: () => { state.investmentTab = key; renderInvestments(); } })));
  root.append(tabs);
  if (state.investmentTab === "instruments") {
    root.append(toolbar(button(t("assetTypes"), assetTypeForm), el("div", { class: "spacer" }), button(t("newInstrument"), () => instrumentForm(null), "primary")));
    const rows = state.instruments.map(v => el("tr", { "data-clickable": "true", onclick: () => instrumentForm(v) },
      el("td", {}, el("strong", { text: v.name }), el("div", { class: "subtle", text: v.symbol })),
      el("td", { text: v.typeName }), el("td", { text: v.currencyCode }),
      el("td", { class: "numeric", text: fmt(v.currentPrice, v.currencyCode) }), el("td", { text: when(v.priceUpdatedAtMs) })));
    root.append(table([t("nameCode"), t("assetType"), t("currency"), t("price"), t("priceUpdated")], rows));
  } else {
    root.append(toolbar(el("div", { class: "spacer" }), button(t("newPosition"), positionForm, "primary")));
    const rows = state.positions.map(v => el("tr", { "data-clickable": "true", onclick: () => positionDetail(v.id) },
      el("td", { text: accountName(v.accountId) }),
      el("td", {}, el("strong", { text: v.name }), el("div", { class: "subtle", text: `${v.symbol} · ${v.currencyCode}` })),
      el("td", { class: "numeric", text: fmt(v.quantity) }), el("td", { class: "numeric", text: fmt(v.averageCost, v.currencyCode) }),
      el("td", { class: "numeric", text: fmt(v.remainingCost, v.currencyCode) }), el("td", { class: "numeric", text: fmt(v.marketValue, v.currencyCode) }),
      el("td", { class: `numeric ${gainClass(v.realized)}`, text: fmt(v.realized, v.currencyCode) }),
      el("td", { class: `numeric ${gainClass(v.unrealized)}`, text: fmt(v.unrealized, v.currencyCode) })));
    root.append(table([t("account"), t("nameCode"), t("quantity"), t("averageCost"), t("holdingCost"), t("marketValue"), t("realized"), t("unrealized")], rows));
  }
}
function assetTypeForm() {
  const form = el("form", { class: "form-grid" }); form.append(field(t("assetTypeName"), "name", "", true, "text", true));
  const error = el("p", { class: "drawer-error" }); form.append(error, actions(async submit => {
    const fd = new FormData(form); await saveForm(submit, error, "/api/v1/asset-types", "POST", { operationId: uuid(), dataGeneration: state.generation, name: fd.get("name") });
  })); openDrawer(t("newAssetType"), "INVESTMENT", form);
}
function positionForm() {
  const form = el("form", { class: "form-grid" });
  form.append(selectField(t("account"), "accountId", state.accounts.map(v => ({ value: v.id, label: v.name }))),
    selectField(t("instrument"), "instrumentId", state.instruments.map(v => ({ value: v.id, label: `${v.name} · ${v.symbol}` }))));
  const error = el("p", { class: "drawer-error" }); form.append(error, actions(async submit => {
    const fd = new FormData(form); await saveForm(submit, error, "/api/v1/positions", "POST", { operationId: uuid(), dataGeneration: state.generation, accountId: Number(fd.get("accountId")), instrumentId: Number(fd.get("instrumentId")) });
  })); openDrawer(t("newPosition"), "INVESTMENT", form);
}
function instrumentForm(item) {
  const form = el("form", { class: "form-grid" });
  form.append(field(t("name"), "name", item?.name || "", true), field(t("symbol"), "symbol", item?.symbol || "", true, "text", false, !!item?.symbolLocked),
    selectField(t("assetType"), "typeId", state.assetTypes.map(v => ({ value: v.id, label: v.name })), item?.typeId),
    field(t("currency"), "currencyCode", item?.currencyCode || state.session.baseCurrency || "CNY", true, "text", false, !!item?.currencyLocked),
    field(t("price"), "currentPrice", item?.currentPrice || "0", true));
  const error = el("p", { class: "drawer-error" }); form.append(error, actions(async submit => {
    const fd = new FormData(form); await saveForm(submit, error, item ? `/api/v1/instruments/${item.id}` : "/api/v1/instruments", item ? "PUT" : "POST", {
      operationId: uuid(), dataGeneration: state.generation, expectedRevision: item?.revision ?? null, name: fd.get("name"), symbol: fd.get("symbol"), typeId: Number(fd.get("typeId")), currencyCode: fd.get("currencyCode"), currentPrice: fd.get("currentPrice"), currencyPriceConfirmed: !!item?.currencyLocked
    });
  })); openDrawer(item ? t("editInstrument") : t("newInstrument"), "INSTRUMENT", form);
}
async function positionDetail(id) {
  state.viewEpoch++;
  const data = await api(`/api/v1/positions/${id}`), p = data.position;
  const account = await api(`/api/v1/accounts/${p.accountId}`);
  const cash = account.cash.filter(v => v.type === "SAVINGS" && v.currencyCode === p.currencyCode);
  const summary = el("div", { class: "cards" }, metric(t("quantity"), fmt(p.quantity)), metric(t("marketValue"), fmt(p.marketValue, p.currencyCode)), metric(t("realized"), fmt(p.realized, p.currencyCode), gainClass(p.realized)), metric(t("unrealized"), fmt(p.unrealized, p.currencyCode), gainClass(p.unrealized)));
  const tradeRows = data.trades.map(v => el("tr", { "data-clickable": "true", onclick: () => tradeForm(p, v, cash) }, el("td", { text: when(v.businessAtMs) }), el("td", { class: v.action === "BUY" ? "gain" : "loss", text: v.action === "BUY" ? t("buy") : t("sell") }), el("td", { class: "numeric", text: fmt(v.quantity) }), el("td", { class: "numeric", text: fmt(v.unitPrice, p.currencyCode) }), el("td", { class: "numeric", text: fmt(v.fee, p.currencyCode) }), el("td", { text: v.cashLinked ? "✓" : "—" })));
  const content = el("div", {}, summary, toolbar(el("div", { class: "spacer" }), button(t("newTrade"), () => tradeForm(p, null, cash), "primary")), table([t("time"), t("direction"), t("quantity"), t("executionPrice"), t("fee"), t("cashLink")], tradeRows));
  openDrawer(`${p.name} · ${p.symbol}`, accountName(p.accountId), content);
}
function tradeForm(position, trade, cashAccounts) {
  const form = el("form", { class: "form-grid" });
  form.append(selectField(t("direction"), "direction", [{ value: "BUY", label: t("buy") }, { value: "SELL", label: t("sell") }], trade?.action), field(t("quantity"), "quantity", trade?.quantity || "", true), field(t("executionPrice"), "executionPrice", trade?.unitPrice || "", true), field(t("fee"), "fee", trade?.fee || "0", true), field(t("time"), "occurred", trade ? toLocalDateTime(trade.businessAtMs) : toLocalDateTime(Date.now()), true, "datetime-local"), checkField(t("cashLink"), "cashLinked", trade?.cashLinked),
    selectField(t("cashAccount"), "cashAccountId", [{ value: "", label: t("none") }, ...cashAccounts.map(v => ({ value: v.id, label: `${v.name} · ${v.currencyCode}` }))], trade?.linkedCashAccountId));
  const error = el("p", { class: "drawer-error" });
  const actionBar = actions(async submit => {
    const fd = new FormData(form); await saveForm(submit, error, trade ? `/api/v1/trades/${trade.id}` : `/api/v1/positions/${position.id}/trades`, trade ? "PUT" : "POST", {
      operationId: uuid(), dataGeneration: state.generation, expectedRevision: trade?.revision ?? null, direction: fd.get("direction"), quantity: fd.get("quantity"), executionPrice: fd.get("executionPrice"), fee: fd.get("fee") || "0", occurredAtMs: new Date(fd.get("occurred")).getTime(), currencyCode: position.currencyCode, cashLinked: fd.get("cashLinked") === "on", cashAccountId: fd.get("cashLinked") === "on" && fd.get("cashAccountId") ? Number(fd.get("cashAccountId")) : null
    });
  });
  if (trade) actionBar.prepend(button(t("delete"), async event => {
    if (!confirm(t("confirmDelete"))) return;
    await saveForm(event.currentTarget, error, `/api/v1/trades/${trade.id}`, "DELETE", { operationId: uuid(), dataGeneration: state.generation, expectedRevision: trade.revision });
  }, "secondary danger"));
  form.append(error, actionBar); openDrawer(trade ? t("editTrade") : t("newTrade"), position.symbol, form);
}

function depositForm(deposit, accountId = null, cashAccounts = []) {
  const form = el("form", { class: "form-grid" });
  const eligibleCash = cashAccounts.filter(v => v.type === "SAVINGS" && (!deposit || v.currencyCode === deposit.currencyCode));
  form.append(field(t("currency"), "currency", deposit?.currencyCode || state.session.baseCurrency || "CNY", true, "text", false, !!deposit),
    field(t("principal"), "principal", deposit?.principal || "", true), field(t("rate"), "rate", deposit?.annualRatePercent || "", true),
    field(t("startDate"), "start", deposit?.startDate || new Date().toISOString().slice(0, 10), true, "date"),
    field(t("toDate"), "end", deposit?.endDate || new Date(Date.now() + 31536000000).toISOString().slice(0, 10), true, "date"),
    checkField(t("cashLink"), deposit ? "openCashLinked" : "cashLinked", deposit?.openCashLinked),
    selectField(t("cashAccount"), deposit ? "openCashAccountId" : "cashAccountId", [{ value: "", label: t("none") }, ...eligibleCash.map(v => ({ value: v.id, label: `${v.name} · ${v.currencyCode}` }))], deposit?.openCashAccountId));
  if (deposit && !deposit.closed) form.append(checkField(`${t("settle")} · ${t("cashLink")}`, "closeCashLinked", deposit.closeCashLinked),
    selectField(`${t("settle")} · ${t("cashAccount")}`, "closeCashAccountId", [{ value: "", label: t("none") }, ...eligibleCash.map(v => ({ value: v.id, label: `${v.name} · ${v.currencyCode}` }))], deposit.closeCashAccountId));
  const error = el("p", { class: "drawer-error" }); const bar = actions(async submit => {
    const fd = new FormData(form); const body = { operationId: uuid(), dataGeneration: state.generation,
      currencyCode: fd.get("currency"), principal: fd.get("principal"), annualRatePercent: fd.get("rate"),
      startEpochDay: epochDay(fd.get("start")), endEpochDay: epochDay(fd.get("end")) };
    if (deposit) Object.assign(body, { expectedRevision: deposit.revision, openCashLinked: fd.get("openCashLinked") === "on",
      closeCashLinked: fd.get("closeCashLinked") === "on", openCashAccountId: fd.get("openCashLinked") === "on" && fd.get("openCashAccountId") ? Number(fd.get("openCashAccountId")) : null,
      closeCashAccountId: fd.get("closeCashLinked") === "on" && fd.get("closeCashAccountId") ? Number(fd.get("closeCashAccountId")) : null });
    else Object.assign(body, { accountId, cashLinked: fd.get("cashLinked") === "on",
      cashAccountId: fd.get("cashLinked") === "on" && fd.get("cashAccountId") ? Number(fd.get("cashAccountId")) : null });
    await saveForm(submit, error, deposit ? `/api/v1/deposits/${deposit.id}` : "/api/v1/deposits", deposit ? "PUT" : "POST", body);
  });
  if (deposit && !deposit.closed) bar.prepend(button(t("settle"), async event => {
    const fd = new FormData(form);
    await saveForm(event.currentTarget, error, `/api/v1/deposits/${deposit.id}/close`, "POST", { operationId: uuid(), dataGeneration: state.generation,
      cashLinked: fd.get("closeCashLinked") === "on", cashAccountId: fd.get("closeCashLinked") === "on" && fd.get("closeCashAccountId") ? Number(fd.get("closeCashAccountId")) : null });
  }, "secondary"));
  form.append(error, bar); openDrawer(deposit ? t("editDeposit") : t("openDeposit"), deposit?.currencyCode || "DEPOSIT", form);
}

async function renderStatistics() {
  const [summary, dist, history] = await Promise.all([api("/api/v1/statistics/summary"), api("/api/v1/statistics/distribution"), api("/api/v1/statistics/history?granularity=DAILY&metric=TOTAL_ASSETS")]);
  const root = $("#content"); clear(root);
  root.append(el("div", { class: "cards" }, metric(t("totalAssets"), fmt(summary.totalAssets, summary.currency)), metric(t("availableCash"), fmt(summary.availableCash, summary.currency)), metric(t("investmentValue"), fmt(summary.investmentValue, summary.currency)), metric(t("monthlyChange"), fmt(summary.monthlyChange, summary.currency), gainClass(summary.monthlyChange))));
  root.append(chart(history.items));
  [[t("accountDistribution"), dist.accounts], [t("assetTypeDistribution"), dist.assetTypes], [t("currencyDistribution"), dist.currencies]].forEach(([title, items]) => root.append(distribution(title, items, dist.currency)));
}
function chart(points) {
  const valid = points.filter(v => v.value !== null && !v.future); const section = el("section", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("history") })));
  if (!valid.length) { section.append(el("div", { class: "empty", text: t("empty") })); return section; }
  const values = valid.map(v => Number(v.value)), min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  const coords = valid.map((v, i) => `${(i / Math.max(1, valid.length - 1)) * 100},${94 - ((Number(v.value) - min) / span) * 84}`).join(" ");
  const svg = el("svg", { class: "chart", viewBox: "0 0 100 100", preserveAspectRatio: "none", role: "img", "aria-label": t("history") });
  [10, 38, 66, 94].forEach(y => svg.append(el("line", { class: "chart-grid", x1: 0, x2: 100, y1: y, y2: y })));
  svg.append(el("polyline", { points: coords })); section.append(svg); return section;
}
function distribution(title, items, currency) {
  const max = Math.max(1, ...items.map(v => Math.abs(Number(v.value))));
  const rows = items.map(v => el("div", { class: "bar-row" },
    el("span", { text: v.label }),
    el("div", { class: "bar-track" }, el("div", { class: "bar-fill", style: `width:${Math.min(100, Math.abs(Number(v.value)) / max * 100)}%` })),
    el("span", { class: "numeric", text: fmt(v.value, currency) })));
  return el("section", { class: "section" },
    el("div", { class: "section-head" }, el("h2", { text: title })), el("div", { class: "bars" }, ...rows));
}

function field(label, name, value = "", required = false, type = "text", full = false, readOnly = false) {
  return el("div", { class: `field ${full ? "full" : ""}` }, el("label", { for: `f-${name}`, text: label }), el("input", { id: `f-${name}`, name, type, value, required, readonly: readOnly }));
}
function selectField(label, name, options, selected) {
  const select = el("select", { id: `f-${name}`, name }); options.forEach(v => { const o = el("option", { value: v.value, text: v.label }); if (String(v.value) === String(selected ?? "")) o.selected = true; select.append(o); });
  return el("div", { class: "field" }, el("label", { for: `f-${name}`, text: label }), select);
}
function checkField(label, name, checked) { return el("label", { class: "check-field" }, el("input", { name, type: "checkbox", checked }), el("span", { text: label })); }
function actions(saveHandler) {
  const saveButton = button(t("save"), async () => {
    const form = saveButton.closest("form") || $("#drawer-content form");
    if (state.saving) return;
    if (form?.pendingSubmission) return saveForm(saveButton, $(".drawer-error", form), ...form.pendingSubmission);
    if (form && !form.reportValidity()) return;
    try { await saveHandler(saveButton); } catch (error) { $(".drawer-error", form).textContent = errorMessage(error); }
  }, "primary");
  saveButton.dataset.save = "true";
  return el("div", { class: "drawer-actions full" }, button(t("cancel"), () => closeDrawer()), saveButton);
}
async function saveForm(buttonNode, errorNode, path, method, body) {
  if (state.saving) return;
  if (state.socket?.readyState !== WebSocket.OPEN) { errorNode.textContent = t("connectionRequired"); return; }
  const form = errorNode.closest("form");
  const existing = form.pendingSubmission;
  if (existing) [path, method, body] = existing;
  state.saving = true; errorNode.textContent = t("saving");
  const controls = Array.from(form.elements).filter(v => !v.disabled);
  controls.forEach(v => v.disabled = true);
  $("#drawer-close").disabled = true;
  try {
    let result;
    if (existing) {
      try { result = await api(`/api/v1/operations/${body.operationId}`); }
      catch (error) { if (error.code !== "NOT_FOUND") throw error; }
    }
    if (!result) await api(path, { method, body: JSON.stringify(body) });
    form.pendingSubmission = null; state.pendingRefresh = false;
    saved(); closeDrawer(true);
  } catch (error) {
    // A browser/proxy may have retried a committed POST before returning a conflict.
    // Reconcile the operation even for a structured rejection before permitting a new ID.
    let confirmed = false, outcomeUnknown = false;
    if (!document.body.dataset.ended) {
      try { await api(`/api/v1/operations/${body.operationId}`); confirmed = true; }
      catch (lookup) { outcomeUnknown = lookup.code !== "NOT_FOUND"; }
    }
    if (confirmed) { form.pendingSubmission = null; state.pendingRefresh = false; saved(); closeDrawer(true); }
    else if (!document.body.dataset.ended && (outcomeUnknown || !error.code || String(error.code).startsWith("HTTP_5") || error.code === "INTERNAL")) {
      form.pendingSubmission = [path, method, body]; errorNode.textContent = t("unknownResult");
    } else { form.pendingSubmission = null; errorNode.textContent = errorMessage(error); }
  } finally {
    controls.forEach(v => v.disabled = false); $("#drawer-close").disabled = false; state.saving = false;
    if (form.pendingSubmission) {
      // Freeze the submitted payload until its outcome is known. Never generate a new ID on retry.
      Array.from(form.elements).forEach(v => { if (v.type !== "button") v.disabled = true; });
      const save = $("[data-save]", form); save.textContent = t("confirmResult"); save.disabled = false;
    } else if (!$("#drawer").hidden) {
      Array.from(form.elements).forEach(v => { if (!v.closest("[hidden]")) v.disabled = false; });
    }
  }
  if ($("#drawer").hidden && !document.body.dataset.ended) await refreshCurrent();
}
function errorMessage(error) {
  const labels = { STALE_RECORD: t("staleRecord"), STALE_BALANCE: t("staleBalance"), CURRENCY_LOCKED: t("currencyLocked"), SYMBOL_LOCKED: t("symbolLocked"), INSUFFICIENT_HOLDING: t("insufficientHolding"), FORMAT: t("invalidFormat") };
  return labels[error.code] || (error.code && t(error.code) !== error.code ? t(error.code) : null) || `${t("saveFailed")}: ${error.code || error.message}`;
}
let toastTimer, drawerOrigin;
function notify(message) { const toast = $("#toast"); toast.textContent = message; toast.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.hidden = true, 5000); }
function saved() { notify(t("saved")); }
function openDrawer(title, kicker, content) {
  if (!$("#drawer").hidden && !closeDrawer()) return;
  drawerOrigin = document.activeElement;
  $("#drawer-title").textContent = title; $("#drawer-kicker").textContent = kicker || "";
  clear($("#drawer-content")); $("#drawer-content").append(content);
  $("#scrim").hidden = false; $("#drawer").hidden = false; $("#workspace").inert = true;
  document.body.classList.add("modal-open");
  const form = content.matches("form") ? content : $("form", content);
  if (form) {
    form.addEventListener("input", () => form.dataset.dirty = "true");
    form.addEventListener("change", () => form.dataset.dirty = "true");
    form.addEventListener("submit", event => { event.preventDefault(); $("[data-save]", form)?.click(); });
  }
  $("#drawer-close").focus();
}
function closeDrawer(force = false) {
  if (force !== true && state.saving) return false;
  const form = $("#drawer-content form");
  if (force !== true && form?.pendingSubmission) { notify(t("unknownResult")); return false; }
  if (force !== true && form?.dataset.dirty && !confirm(t("discard"))) return false;
  $("#scrim").hidden = true; $("#drawer").hidden = true; clear($("#drawer-content"));
  $("#workspace").inert = false; document.body.classList.remove("modal-open");
  if (drawerOrigin?.isConnected) drawerOrigin.focus(); drawerOrigin = null;
  if (state.pendingRefresh && force !== true) scheduleRefresh();
  return true;
}
function hero(summary, primary) {
  return el("section", { class: "hero" }, metric(t(primary), fmt(summary[primary], summary.currency)),
    el("div", { class: "hero-details" }, ...["availableCash", "monthlyChange"].map(key =>
      el("span", {}, el("span", { class: "subtle", text: t(key) }), el("strong", { class: key === "monthlyChange" ? gainClass(summary[key]) : "", text: fmt(summary[key], summary.currency) })))));
}
function toLocalDateTime(ms) { const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000); return d.toISOString().slice(0, 16); }

export { preload, renderNav, navigate, refreshCurrent, scheduleRefresh, closeDrawer, notify };
hooks.error = error => { if (error.name !== "AbortError") notify(errorMessage(error)); };
