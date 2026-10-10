import { el, clear, fmt, fmtPrice, when, epochDay, gainClass, accountName, uuid, restoreSessionCredentials, rememberSessionCredentials, clearSessionCredentials, api, $, state, hooks, t, motion } from "./core.js";
import { symbol, avatar, iconPicker } from "./icons.js";
import { openPriceEditor } from "./price-editor.js";
import { investmentGroups } from "./investment-groups.js";
async function preload() {
  const [accounts, instruments, types, positions] = await Promise.all([
    api("/api/v1/accounts"), api("/api/v1/instruments"), api("/api/v1/asset-types"), api("/api/v1/positions")
  ]);
  state.accounts = accounts.items; state.instruments = instruments.items; state.assetTypes = types.items; state.positions = positions.items;
}
function renderNav() {
  const nav = $("#nav");
  if (!nav.querySelector(".nav-indicator")) {
    nav.append(el("span", { class: "nav-indicator", "aria-hidden": "true" }));
    [["accounts", "account_balance_wallet"], ["records", "payments"], ["investments", "trending_up"], ["statistics", "monitoring"]].forEach(([key, icon]) => {
      nav.append(el("button", { class: "nav-item", "data-page": key, type: "button", onclick: () => navigate(key) },
        symbol(icon), el("span", { class: "nav-label", text: t(key) })));
    });
    new ResizeObserver(() => positionNav(false)).observe(nav);
  }
  for (const item of nav.querySelectorAll(".nav-item")) {
    const selected = item.dataset.page === state.page;
    item.classList.toggle("active", selected); item.setAttribute("aria-label", t(item.dataset.page));
    item.querySelector(".nav-label").textContent = t(item.dataset.page);
    if (selected) item.setAttribute("aria-current", "page"); else item.removeAttribute("aria-current");
  }
  positionNav(true);
}
function positionNav(animate) {
  const nav = $("#nav"), pill = nav.querySelector(".nav-indicator"), target = nav.querySelector(".active");
  if (!pill || !target || !nav.getClientRects().length) return;
  const old = pill.getBoundingClientRect(), box = nav.getBoundingClientRect(), next = target.getBoundingClientRect();
  motion.stop(pill);
  Object.assign(pill.style, { left: `${next.left-box.left}px`, top: `${next.top-box.top}px`, width: `${next.width}px`, height: `${next.height}px` });
  if (animate && old.width && (Math.abs(old.left-next.left) > 1 || Math.abs(old.top-next.top) > 1)) motion.run(pill,
    [{ transform: `translate(${old.left-next.left}px,${old.top-next.top}px)` }, { transform: "none" }], { duration: 340 });
}
let loadingTimer;
function setLoading(refresh) {
  const root = $("#content"); root.setAttribute("aria-busy", "true"); root.inert = !refresh;
  let status = $("#page-loading");
  if (!status) { status = el("div", { id: "page-loading", class: "page-loading", role: "status" }); root.before(status); }
  status.hidden = true;
  clearTimeout(loadingTimer);
  loadingTimer = setTimeout(() => {
    status.textContent = t(refresh ? "updating" : "loading"); status.hidden = false;
    if (!root.children.length) root.append(el("div", { class: "loading-skeleton", "aria-hidden": "true" },
      el("div", { class: "skeleton-hero" }), el("div", { class: "skeleton-body" })));
  }, 150);
}
function finishLoading() {
  clearTimeout(loadingTimer); $("#page-loading")?.setAttribute("hidden", "");
  $("#content").removeAttribute("aria-busy"); $("#content").inert = false;
}
function replacePage(content, refresh = false) {
  const root = $("#content"), focusedId = root.contains(document.activeElement) ? document.activeElement.id : null;
  const previousRows = new Map(Array.from(root.querySelectorAll("[data-account-row]"), node => [node.dataset.accountRow, node.getBoundingClientRect().top]));
  const previousCurve = root.querySelector(".chart-curve")?.innerHTML;
  const previousBars = Array.from(root.querySelectorAll(".bar-fill"), v => ({ key: v.dataset.barKey, width: v.style.width }));
  motion.stop(root); root.replaceChildren(...content.childNodes); motion.bindDetails(root);
  if (!refresh) motion.enter(root);
  else for (const bar of root.querySelectorAll(".bar-fill")) {
    const old = previousBars.find(v => v.key === bar.dataset.barKey), width = parseFloat(bar.style.width);
    if (old && width && old.width !== bar.style.width) motion.run(bar,
      [{ transform: `scaleX(${parseFloat(old.width)/width})` }, { transform: "scaleX(1)" }], { duration: 460 });
  }
  if (refresh) {
    for (const row of root.querySelectorAll("[data-account-row]")) {
      const previous = previousRows.get(row.dataset.accountRow), top = row.getBoundingClientRect().top;
      if (previous == null) motion.run(row, [{ opacity: 0 }, { opacity: 1 }], { duration: 320 });
      else if (Math.abs(previous-top)>1) motion.run(row, [{ transform: `translateY(${previous-top}px)` }, { transform: "none" }], { duration: 320 });
    }
    const curve = root.querySelector(".chart-curve");
    if (curve && previousCurve !== curve.innerHTML) motion.run(curve, [{ opacity: .45 }, { opacity: 1 }], { duration: 240 });
  }
  if (focusedId) document.getElementById(focusedId)?.focus({ preventScroll: true });
}
async function navigate(page, { refresh = false } = {}) {
  if (!closeDrawer(false, true)) return;
  state.viewEpoch++; state.page = page; renderNav();
  const epoch = state.viewEpoch; state.pageLoading = true;
  $("#page-kicker").textContent = state.session?.dataMode === "DEMO" ? `${t("demoMode")} · WEB ADMIN` : "WEB ADMIN";
  setLoading(refresh);
  try {
    if (page === "accounts") await renderAccounts(refresh);
    else if (page === "records") await renderRecords(true);
    else if (page === "investments") await renderInvestments(refresh);
    else await renderStatistics(refresh);
    if (epoch === state.viewEpoch) $("#page-title").textContent = t(page);
  } catch (error) {
    if (epoch === state.viewEpoch) {
      if (refresh) notify(t("loadFailed"));
      else { $("#page-title").textContent = t(page); showError(error); }
    }
  } finally { if (epoch === state.viewEpoch) { state.pageLoading = false; finishLoading(); } }
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
  const epoch = state.viewEpoch;
  setLoading(true);
  refreshTask = (async () => {
    const scroll = window.scrollY;
    try {
      await preload();
      if (epoch !== state.viewEpoch) return;
      if (state.saving || !$("#drawer").hidden) { state.pendingRefresh = true; return; }
      await navigate(state.page, { refresh: true });
      if (state.viewEpoch === epoch + 1) window.scrollTo(0, scroll);
    } catch (error) { if (error.name !== "AbortError") notify(t("loadFailed")); }
  })().finally(() => {
    if (epoch === state.viewEpoch) finishLoading();
    refreshTask = null; state.refreshing = false;
    if (state.pendingRefresh && $("#drawer").hidden && !state.saving) scheduleRefresh();
  });
  return refreshTask;
}
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

async function renderAccounts(refresh = false) {
  const [data, summary] = await Promise.all([api("/api/v1/accounts"), api("/api/v1/statistics/summary")]); state.accounts = data.items;
  const root = el("div");
  root.append(hero(summary, "totalAssets"));
  root.append(toolbar(el("div", { class: "spacer" }), button(t("newAccount"), () => accountForm(null), "primary")));
  const rows = data.items.map(item => el("tr", { "data-clickable": "true", "data-account-row": item.id, onclick: () => accountDetail(item.id) },
    el("td", {}, el("div", { class: "account-cell" }, avatar(item.icon), el("div", {}, el("strong", { text: item.name }), el("div", { class: "subtle", text: item.note || "—" })) )),
    el("td", { class: "numeric", text: fmt(item.availableCash ?? item.cash, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.creditBalance, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.deposits, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.investments, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.total, data.baseCurrency) }),
    el("td", { text: item.complete ? "—" : "!", title: item.complete ? "" : t("incomplete") })));
  const accountTable = table([t("account"), t("cash"), t("creditBalance"), t("deposits"), t("investmentValue"), t("totalAssets"), t("complete")], rows);
  accountTable.classList.add("accounts-table"); root.append(accountTable);
  if (data.items.some(item => !item.complete)) root.append(el("p", { class: "subtle", text: t("incomplete") }));
  replacePage(root, refresh);
}
async function accountDetail(id, returnState = null) {
  state.viewEpoch++;
  const data = await api(`/api/v1/accounts/${id}`); const a = data.account;
  if (returnState && returnState.cycle !== drawerCycle) return;
  const editAccount = { ...a, cash: data.cash, creditSourceCandidates: data.creditSourceCandidates };
  const cashCards = data.cash.map(v => el("article", { class: "cash-card", "data-cash-id": v.id },
    el("div", { class: "cash-card-head" }, el("div", { class: "cash-identity" },
      el("h3", { text: v.name }), el("div", { class: "subtle", text: `${v.type === "CREDIT" ? t("creditAccount") : t("savingsAccount")} · ${v.currencyCode}` })),
      el("div", { class: "action-group" }, deleteAccountButton(a.id, v.id))),
    el("div", { class: "cash-amount" }, el("span", { class: "subtle", text: t("balance") }), el("strong", { text: fmt(v.balance, v.currencyCode) })),
    v.credit ? el("div", { class: "cash-credit-summary subtle", text: `${t("usedLimit")} ${fmt(v.credit.used, v.currencyCode)} · ${t("creditLimit")} ${fmt(v.credit.totalLimit, v.currencyCode)}` }) : null,
    v.note ? el("p", { class: "account-note", text: v.note }) : null));
  const savingsCash = data.cash.filter(v => v.type === "SAVINGS");
  const depositRows = data.deposits.map(v => el("tr", { "data-clickable": "true", onclick: () => depositForm(v, null, savingsCash) },
    el("td", { text: v.currencyCode }), el("td", { class: "numeric", text: fmt(v.principal, v.currencyCode) }),
    el("td", { text: v.startDate }), el("td", { text: v.endDate }),
    el("td", { class: "numeric", text: `${fmt(v.annualRatePercent)}%` }), el("td", { text: v.closed ? t("settled") : t("holding") })));
  const positionRows = data.positions.map(v => el("tr", { "data-clickable": "true", onclick: () => positionDetail(v.id) },
    el("td", {}, el("strong", { text: v.name }), el("div", { class: "subtle", text: v.symbol })),
    el("td", { class: "numeric", text: fmt(v.quantity) }), el("td", { class: "numeric", text: fmtPrice(v.currentPrice, v.currencyCode) }),
    el("td", { class: "numeric", text: fmt(v.marketValue, v.currencyCode) }),
    el("td", { class: `numeric ${gainClass(v.unrealized)}`, text: fmt(v.unrealized, v.currencyCode) })));
  const content = el("div", {},
    el("div", { class: "account-detail-head" },
      el("div", { class: "section-head" }, el("div", { class: "account-cell" }, avatar(a.icon), el("strong", { text: a.name })),
        el("div", { class: "action-group" }, button(t("edit"), () => accountForm(editAccount)), deleteAccountButton(a.id))),
      a.note ? el("p", { class: "account-note", text: a.note }) : null),
    el("section", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("subaccount") }),
      button(t("addSubaccount"), () => accountForm(editAccount, null, true))),
      el("div", { class: "cash-card-list" }, ...cashCards, !cashCards.length ? el("p", { class: "empty", text: t("empty") }) : null)),
    sectionTable(t("deposits"), [t("currency"), t("principal"), t("start"), t("endDate"), t("annualRate"), t("status")], depositRows,
      button(t("openDeposit"), () => depositForm(null, a.id, savingsCash))),
    sectionTable(t("positions"), [t("nameCode"), t("quantity"), t("currentPrice"), t("marketValue"), t("pnl")], positionRows));
  content.drawerRoute = { kind: "account", id };
  openDrawer(t("accountDetails"), "ACCOUNT", content, !!returnState);
  if (returnState) $("#drawer-content").scrollTop = returnState.scrollTop;
}
function sectionTable(title, headers, rows, action = null) { return el("section", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: title }), action), table(headers, rows)); }

function deleteAccountButton(accountId, balanceAccountId = null) {
  return el("button", { type: "button", class: "icon-button danger", "aria-label": t("delete"), onclick: () => deletionForm(accountId, balanceAccountId).catch(error => notify(errorMessage(error))) },
    el("svg", { class: "symbol", viewBox: "0 0 24 24", "aria-hidden": "true" },
      el("path", { d: "M4 6h16M9 3h6M7 6v15h10V6M10 9v9M14 9v9", fill: "none", stroke: "currentColor", "stroke-width": "1.7" })));
}
async function deletionForm(accountId, balanceAccountId) {
  if (!closeDrawer()) return;
  const p = await api("/api/v1/account-deletion-preview", { method: "POST", body: JSON.stringify({ accountId, balanceAccountId }) });
  const codeField = field(t("deletionCode"), "code", "", true);
  const input = $("input", codeField); input.inputMode = "numeric"; input.maxLength = 6; input.pattern = p.code;
  const error = el("p", { class: "drawer-error full", role: "alert" });
  const form = el("form", { class: "form-grid" }, el("h2", { class: "full", text: p.name }),
    el("p", { class: "full", text: t(balanceAccountId == null ? "deleteParentScope" : "deleteChildScope") }),
    el("p", { class: "full", text: `${t("subaccount")}: ${p.cashCount} · ${t("records")}: ${p.entryCount} · ${t("deposits")}: ${p.depositCount} · ${t("trade")}: ${p.tradeCount}` }),
    p.balance == null ? null : el("p", { class: "full", text: `${fmt(p.balance)} ${p.currencyCode}` }),
    ...p.transfers.map(v => el("p", { class: "full", text: `${v.name}: ${fmt(v.limit)} ${v.currencyCode} · ${t("independentLimit")}` })),
    p.transfers.length ? el("p", { class: "full", text: t("deleteLimitNotice") }) : null,
    el("strong", { class: "full", text: `${t("deletionCode")}: ${p.code}` }), codeField, error);
  form.deletionTicket = p.ticket;
  const bar = actions(btn => saveForm(btn, error, balanceAccountId == null ? `/api/v1/accounts/${accountId}` : `/api/v1/balance-accounts/${balanceAccountId}`, "DELETE", {
    operationId: uuid(), dataGeneration: p.dataGeneration, accountId, expectedRevision: p.revision, ticket: p.ticket, code: input.value
  }));
  const confirmButton = $("[data-save]", bar); confirmButton.textContent = t("delete"); confirmButton.disabled = true;
  input.addEventListener("input", () => { confirmButton.disabled = input.value !== p.code; });
  form.append(bar); openDrawer(t("delete"), "ACCOUNT", form);
}

function accountForm(account, focusCashId = null, addNew = false) {
  const parentDrawer = account ? captureDrawerParent() : null;
  const cash = account?.cash || [];
  const picker = iconPicker(account?.icon);
  const form = el("form", { class: "form-grid account-form" });
  form.parentDrawer = parentDrawer;
  const noteField = (name, value = "") => el("div", { class: "field full" },
    el("label", { for: `f-${name}`, text: t("note") }), el("textarea", { id: `f-${name}`, name, rows: 3, maxlength: 2000, text: value }));
  const basic = el("section", { class: "account-form-section full" }, el("h3", { text: t("basicInformation") }),
    el("div", { class: "form-grid" }, field(t("name"), "name", account?.name || "", true, "text", true),
      el("details", { class: "icon-disclosure full" }, el("summary", { text: t("icon") }), picker.element),
      noteField("note", account?.note || ""),
      el("div", { class: "setting-group full" }, checkField(t("showDeposits"), "showDepositSummary", account?.showDepositSummary ?? true),
        checkField(t("showInvestments"), "showInvestmentSummary", account?.showInvestmentSummary ?? true))));
  form.append(basic);
  const children = el("section", { class: "account-form-section full cash-edit-list" }, el("h3", { text: t("subaccount") }));
  const sourceOptions = (currency, excluded) => [{ value: "", label: t("independentLimit") }, ...(account?.creditSourceCandidates || [])
    .filter(v => v.accountId === account.id && v.id !== excluded && (!currency || v.currencyCode === currency))
    .map(v => ({ value: v.id, label: `${v.accountName} · ${v.name}` }))];
  cash.forEach(v => {
    // Stable account IDs bind inputs to data; list position is never an edit target.
    const key = v.id;
    const fields = el("div", { class: "form-grid" }, field(t("name"), `cashName-${key}`, v.name, true),
      field(t("balance"), `cash-${key}`, v.balance, true), noteField(`cashNote-${key}`, v.note || ""),
      el("div", { class: "setting-group full" }, checkField(t("showOnAccounts"), `visible-${key}`, v.showOnAccountsPage ?? true),
        v.type === "SAVINGS" ? checkField(t("includeAvailable"), `available-${key}`, v.includeInAvailableCash ?? true) : null));
    if (v.type === "CREDIT") fields.append(
      selectField(t("limitSource"), `source-${key}`, sourceOptions(v.currencyCode, v.id), v.credit?.limitSourceAccountId),
      field(t("creditLimit"), `limit-${key}`, v.credit?.creditLimit || ""),
      field(t("statementDay"), `statement-${key}`, v.credit?.statementDay || 12, true),
      selectField(t("dueRule"), `dueType-${key}`, [{ value: "AFTER_STATEMENT_DAYS", label: t("dueAfter") }, { value: "FIXED_DAY_OF_MONTH", label: t("dueFixed") }], v.credit?.dueRule?.type),
      field(t("dueValue"), `dueValue-${key}`, v.credit?.dueRule?.value || 20, true));
    const item = el("details", { class: "cash-editor-card", "data-edit-cash": key, open: focusCashId === key || cash.length === 1 },
      el("summary", {}, el("strong", { text: v.name }), el("span", { class: "subtle", text: `${v.currencyCode} · ${v.type === "CREDIT" ? t("creditAccount") : t("savingsAccount")}` })), fields);
    children.append(item);
  });
  if (cash.length) form.append(children);
  const newFields = el("fieldset", { class: "cash-editor-card form-grid new-cash-fields" },
    el("legend", { text: t("addSubaccount") }),
    field(t("newCashName"), "newCashName", "", true),
    field(t("newCashCurrency"), "newCashCurrency", state.session.baseCurrency || "CNY", true),
    field(t("newCashBalance"), "newCashBalance", "0", true),
    selectField(t("type"), "newCashType", [{ value: "SAVINGS", label: t("savingsAccount") }, { value: "CREDIT", label: t("creditAccount") }]),
    noteField("newCashNote"), selectField(t("limitSource"), "newLimitSource", sourceOptions(), ""),
    field(t("creditLimit"), "newCreditLimit", "10000", true), field(t("statementDay"), "newStatementDay", "12", true),
    selectField(t("dueRule"), "newDueType", [{ value: "AFTER_STATEMENT_DAYS", label: t("dueAfter") }, { value: "FIXED_DAY_OF_MONTH", label: t("dueFixed") }]),
    field(t("dueValue"), "newDueValue", "20", true));
  const addToggle = checkField(t("addSubaccount"), "addNewCash", !account || addNew);
  form.append(el("section", { class: "account-form-section full new-cash-section" }, addToggle, newFields));
  const error = el("p", { class: "drawer-error" });
  form.append(error, actions(async submit => {
    const fd = new FormData(form);
    const body = { operationId: uuid(), dataGeneration: state.generation, expectedRevision: account?.revision ?? null,
      name: fd.get("name"), note: fd.get("note") || "", showDepositSummary: fd.get("showDepositSummary") === "on", showInvestmentSummary: fd.get("showInvestmentSummary") === "on", cashChanges: cash.map(v => ({ cashAccountId: v.id, expectedRevision: v.revision, currencyCode: v.currencyCode, balance: fd.get(`cash-${v.id}`), name: fd.get(`cashName-${v.id}`), note: fd.get(`cashNote-${v.id}`) || "", type: v.type, showOnAccountsPage: fd.get(`visible-${v.id}`) === "on", includeInAvailableCash: v.type === "SAVINGS" ? fd.get(`available-${v.id}`) === "on" : v.includeInAvailableCash,
        credit: v.type === "CREDIT" ? { limitSourceAccountId: fd.get(`source-${v.id}`) ? Number(fd.get(`source-${v.id}`)) : null, creditLimit: fd.get(`limit-${v.id}`) || v.credit?.creditLimit || "", statementDay: Number(fd.get(`statement-${v.id}`)), dueRule: { type: fd.get(`dueType-${v.id}`), value: Number(fd.get(`dueValue-${v.id}`)) } } : null })) };
    if (String(fd.get("newCashCurrency") || "").trim()) body.cashChanges.push({ cashAccountId: null, expectedRevision: null,
      currencyCode: String(fd.get("newCashCurrency")).trim().toUpperCase(), balance: fd.get("newCashBalance") || "0",
      name: fd.get("newCashName") || "", note: fd.get("newCashNote") || "", type: fd.get("newCashType"),
      credit: fd.get("newCashType") === "CREDIT" ? { limitSourceAccountId: fd.get("newLimitSource") ? Number(fd.get("newLimitSource")) : null, creditLimit: fd.get("newCreditLimit"), statementDay: Number(fd.get("newStatementDay")), dueRule: { type: fd.get("newDueType"), value: Number(fd.get("newDueValue")) } } : null });
    body.iconChange = await picker.value();
    await saveForm(submit, error, account ? `/api/v1/accounts/${account.id}` : "/api/v1/accounts", account ? "PUT" : "POST", body);
  }));
  const updateCredit = () => {
    // An uncertain save must keep every input frozen until its operation is reconciled.
    if (form.pendingSubmission) return;
    const adding = form.elements.addNewCash.checked;
    newFields.hidden = !adding; newFields.disabled = !adding;
    const credit = adding && form.elements.newCashType.value === "CREDIT";
    ["newLimitSource", "newCreditLimit", "newStatementDay", "newDueType", "newDueValue"].forEach(name => {
      const input = form.elements[name]; input.disabled = !credit; input.closest(".field").hidden = !credit;
    });
    const currency = form.elements.newCashCurrency.value.trim().toUpperCase();
    form.elements.newCashName.required = adding;
    Array.from(form.elements.newLimitSource.options).slice(1).forEach(option => {
      const candidate = account?.creditSourceCandidates?.find(v => String(v.id) === option.value);
      option.disabled = candidate?.currencyCode !== currency;
      if (option.disabled && option.selected) form.elements.newLimitSource.value = "";
    });
    form.elements.newCreditLimit.readOnly = !!form.elements.newLimitSource.value;
  };
  form.elements.newLimitSource.addEventListener("change", updateCredit);
  form.elements.addNewCash.addEventListener("change", updateCredit);
  form.addEventListener("save-settled", updateCredit);
  form.addEventListener("invalid", event => { const group=event.target.closest("details"); if(group) motion.details(group, true); }, true);
  form.elements.newCashType.addEventListener("change", updateCredit);
  form.elements.newCashCurrency.addEventListener("input", updateCredit); updateCredit();
  const updateSources = () => cash.filter(v => v.type === "CREDIT").forEach(v => {
    form.elements[`limit-${v.id}`].readOnly = !!form.elements[`source-${v.id}`].value;
  });
  form.addEventListener("change", updateSources); updateSources();
  form.validateInputs = () => {
    const check = (name, valid) => {
      const input = form.elements[name];
      if (!input || input.disabled) return;
      input.setCustomValidity(valid ? "" : t("invalidFormat"));
      input.setAttribute("aria-invalid", String(!valid));
    };
    const decimal = (name, signed, code) => {
      const value = String(form.elements[name]?.value || "").trim();
      const scale = state.session.currencies?.find(v => v.code === code)?.fractionDigits ?? (code === "JPY" || code === "KRW" || code === "VND" ? 0 : ["KWD","BHD","OMR","TND"].includes(code) ? 3 : 2);
      check(name, (signed ? /^-?\d+(?:\.\d+)?$/ : /^\d+(?:\.\d+)?$/).test(value) && (value.split(".")[1]?.replace(/0+$/, "").length || 0) <= scale);
    };
    cash.forEach(v => {
      decimal(`cash-${v.id}`,true,v.currencyCode);
      if (v.type === "CREDIT") {
        check(`limit-${v.id}`,true);
        if (!form.elements[`source-${v.id}`].value) decimal(`limit-${v.id}`,false,v.currencyCode);
        check(`statement-${v.id}`,/^(?:[1-9]|[12]\d|3[01])$/.test(form.elements[`statement-${v.id}`].value));
        const due=Number(form.elements[`dueValue-${v.id}`].value);
        check(`dueValue-${v.id}`,Number.isInteger(due) && due>=1 && due<=(form.elements[`dueType-${v.id}`].value === "FIXED_DAY_OF_MONTH" ? 31 : 365));
      }
    });
    const currency=form.elements.addNewCash.checked ? form.elements.newCashCurrency.value.trim().toUpperCase() : "";
    if (currency) {
      decimal("newCashBalance",true,currency);
      if (form.elements.newCashType.value === "CREDIT") {
        check("newCreditLimit",true);
        if (!form.elements.newLimitSource.value) decimal("newCreditLimit",false,currency);
        check("newStatementDay",/^(?:[1-9]|[12]\d|3[01])$/.test(form.elements.newStatementDay.value));
        const due=Number(form.elements.newDueValue.value);
        check("newDueValue",Number.isInteger(due) && due>=1 && due<=(form.elements.newDueType.value === "FIXED_DAY_OF_MONTH" ? 31 : 365));
      }
    }
  };
  form.addEventListener("input", event => { event.target.setCustomValidity?.(""); event.target.removeAttribute?.("aria-invalid"); });
  openDrawer(account ? t("editAccount") : t("newAccount"), "ACCOUNT", form);
  if (focusCashId != null) form.querySelector(`[data-edit-cash="${focusCashId}"]`)?.scrollIntoView({ block: "start" });
  if (addNew) newFields.scrollIntoView({ block: "start" });
}

async function renderRecords(reset) {
  const root = el("div");
  const oldControls = $("#record-controls");
  if (oldControls) state.recordFilters = Object.fromEntries(new FormData(oldControls));
  const requestId = state.recordsRequest = (state.recordsRequest || 0) + 1;
  const controls = el("form", { id: "record-controls", class: "toolbar" },
      field(t("fromDate"), "from", "", false, "date"), field(t("toDate"), "to", "", false, "date"),
      selectField(t("account"), "accountId", [{ value: "", label: t("all") }, ...state.accounts.map(v => ({ value: v.id, label: v.name }))]),
      selectField(t("type"), "type", [{ value: "", label: t("all") }, { value: "CASH", label: t("cash") }, { value: "DEPOSIT", label: t("deposit") }, { value: "TRADE", label: t("trade") }]),
      field(t("search"), "query", ""), button(t("filter"), () => renderRecords(true), "primary"));
    Object.entries(state.recordFilters).forEach(([name, value]) => { if (controls.elements[name]) controls.elements[name].value = value; });
    controls.addEventListener("submit", event => { event.preventDefault(); renderRecords(true).catch(showError); });
    root.append(controls, el("div", { id: "records-table" }));
  const fd = new FormData(controls); const params = new URLSearchParams({ pageSize: "50" });
  if (fd.get("from")) params.set("from", String(new Date(`${fd.get("from")}T00:00:00`).getTime()));
  if (fd.get("to")) params.set("to", String(new Date(`${fd.get("to")}T23:59:59.999`).getTime()));
  ["accountId", "type", "query"].forEach(k => { if (fd.get(k)) params.set(k, fd.get(k)); });
  if (!reset && state.recordsCursor) params.set("cursor", state.recordsCursor);
  const data = await api(`/api/v1/records?${params}`); if (requestId !== state.recordsRequest) return;
  state.recordsHistory = reset ? data.items : [...state.recordsHistory, ...data.items]; state.recordsCursor = data.nextCursor;
  const rows = state.recordsHistory.map(v => el("tr", { "data-clickable": "true", onclick: () => openRecord(v) },
    el("td", { text: when(v.businessAtMs) }), el("td", { text: v.accountName }), el("td", { text: v.childName || "—" }),
    el("td", {}, el("strong", { class: v.action === "BUY" ? "gain" : v.action === "SELL" ? "loss" : "", text: actionLabel(v.action) })),
    el("td", { text: v.objectName || "—" }), el("td", { class: "numeric", text: v.amount ? fmt(v.amount, v.currencyCode) : fmt(v.quantity) }),
    el("td", { text: v.currencyCode }), el("td", { text: v.note || "—" }), el("td", { text: when(v.updatedAtMs) }), el("td", { text: t("edit") })));
  const holder = $("#records-table", root); holder.append(table([t("time"), t("account"), t("subaccount"), t("type"), t("object"), t("amountQuantity"), t("currency"), t("note"), t("updated"), t("operation")], rows));
  if (state.recordsCursor) holder.append(toolbar(el("div", { class: "spacer" }), button(t("loadMore"), () => renderRecords(false))));
  replacePage(root, !!oldControls);
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

async function renderInvestments(refresh = false) {
  const requestId = state.investmentRequest = (state.investmentRequest || 0) + 1;
  const [, summary] = await Promise.all([preload(), api("/api/v1/statistics/summary")]);
  if (requestId !== state.investmentRequest) return;
  const root = el("div");
  root.append(hero(summary, "investmentValue"));
  const tabs = el("div", { class: "tabs" }, ...[["instruments", t("instruments")], ["positions", t("positions")]].map(([key, label]) => el("button", { type: "button", class: `tab ${state.investmentTab === key ? "active" : ""}`, text: label, onclick: () => { state.investmentTab = key; renderInvestments(true).catch(error => { if (error.name !== "AbortError") notify(t("loadFailed")); }); } })));
  root.append(tabs);
  if (state.investmentTab === "instruments") {
    const prices = button(t("updatePrices"), () => openPriceEditor({ actions, saveForm, openDrawer }));
    prices.disabled = !state.instruments.length;
    root.append(toolbar(button(t("assetTypes"), assetTypeForm), el("div", { class: "spacer" }), prices, button(t("newInstrument"), () => instrumentForm(null), "primary")));
    const rows = state.instruments.map(v => el("tr", { "data-clickable": "true", onclick: () => instrumentForm(v) },
      el("td", {}, el("strong", { text: v.name }), el("div", { class: "subtle", text: v.symbol })),
      el("td", { text: v.typeName }), el("td", { text: v.currencyCode }),
      el("td", { class: "numeric", text: fmtPrice(v.currentPrice, v.currencyCode) }), el("td", { text: when(v.priceUpdatedAtMs) })));
    root.append(table([t("nameCode"), t("assetType"), t("currency"), t("price"), t("priceUpdated")], rows));
  } else {
    root.append(toolbar(el("div", { class: "spacer" }), button(t("newPosition"), positionForm, "primary")));
    root.append(investmentGroups({ table, positionDetail }));
  }
  replacePage(root, refresh);
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
    field(t("searchInstrument"), "search", ""));
  const list = el("div", { class: "field full" }); form.append(list);
  const render = () => {
    const query = form.elements.search.value.trim().toLowerCase();
    list.replaceChildren(...state.instruments.filter(v => `${v.name} ${v.symbol} ${v.currencyCode}`.toLowerCase().includes(query)).map(v =>
      button(`${v.name} · ${v.symbol} · ${v.currencyCode}`, async () => {
        const accountId = Number(form.elements.accountId.value);
        const existing = state.positions.find(p => p.accountId === accountId && p.instrumentId === v.id);
        if (existing) return positionDetail(existing.id);
        const account = await api(`/api/v1/accounts/${accountId}`);
        tradeForm({ id: 0, accountId, instrumentId: v.id, name: v.name, currencyCode: v.currencyCode }, null,
          account.cash.filter(c => c.type === "SAVINGS" && c.currencyCode === v.currencyCode));
      })));
  };
  form.elements.search.addEventListener("input", render); render();
  openDrawer(t("newPosition"), "INVESTMENT", form);
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
async function positionDetail(id, returnState = null, parentDrawer = captureDrawerParent()) {
  state.viewEpoch++;
  const data = await api(`/api/v1/positions/${id}`), p = data.position;
  const account = await api(`/api/v1/accounts/${p.accountId}`);
  if (returnState && returnState.cycle !== drawerCycle) return;
  const cash = account.cash.filter(v => v.type === "SAVINGS" && v.currencyCode === p.currencyCode);
  const summary = el("div", { class: "cards" }, metric(t("quantity"), fmt(p.quantity)), metric(t("marketValue"), fmt(p.marketValue, p.currencyCode)), metric(t("realized"), fmt(p.realized, p.currencyCode), gainClass(p.realized)), metric(t("unrealized"), fmt(p.unrealized, p.currencyCode), gainClass(p.unrealized)));
  summary.append(metric(t("currentPrice"), fmtPrice(p.currentPrice, p.currencyCode)),
    metric(t("averageCost"), fmt(p.averageCost, p.currencyCode)), metric(t("holdingCost"), fmt(p.remainingCost, p.currencyCode)));
  const tradeRows = data.trades.map(v => el("tr", { "data-clickable": "true", onclick: () => tradeForm(p, v, cash) }, el("td", { text: when(v.businessAtMs) }), el("td", { class: v.action === "BUY" ? "gain" : "loss", text: v.action === "BUY" ? t("buy") : t("sell") }), el("td", { class: "numeric", text: fmt(v.quantity) }), el("td", { class: "numeric", text: fmt(v.unitPrice, p.currencyCode) }), el("td", { class: "numeric", text: fmt(v.fee, p.currencyCode) }), el("td", { text: v.cashLinked ? "✓" : "—" })));
  const content = el("div", {}, summary, toolbar(el("div", { class: "spacer" }), button(t("newTrade"), () => tradeForm(p, null, cash), "primary")), table([t("time"), t("direction"), t("quantity"), t("executionPrice"), t("fee"), t("cashLink")], tradeRows));
  content.parentDrawer = parentDrawer;
  content.drawerRoute = { kind: "position", id, parentDrawer };
  openDrawer(`${p.name} · ${p.symbol}`, accountName(p.accountId), content, !!returnState);
  if (returnState) $("#drawer-content").scrollTop = returnState.scrollTop;
}
function tradeForm(position, trade, cashAccounts) {
  const form = el("form", { class: "form-grid" });
  form.parentDrawer = captureDrawerParent();
  form.append(selectField(t("direction"), "direction", [{ value: "BUY", label: t("buy") }, { value: "SELL", label: t("sell") }], trade?.action), field(t("quantity"), "quantity", trade?.quantity || "", true), field(t("executionPrice"), "executionPrice", trade?.unitPrice || state.instruments.find(v => v.id === position.instrumentId)?.currentPrice || "", true), field(t("fee"), "fee", trade?.fee || "0", true), field(t("time"), "occurred", trade ? toLocalDateTime(trade.businessAtMs) : toLocalDateTime(Date.now()), true, "datetime-local"), checkField(t("cashLink"), "cashLinked", trade?.cashLinked),
    selectField(t("cashAccount"), "cashAccountId", [{ value: "", label: t("none") }, ...cashAccounts.map(v => ({ value: v.id, label: `${v.name} · ${v.currencyCode}` }))], trade?.linkedCashAccountId));
  const error = el("p", { class: "drawer-error" });
  const actionBar = actions(async submit => {
    const fd = new FormData(form); await saveForm(submit, error, trade ? `/api/v1/trades/${trade.id}` : position.id ? `/api/v1/positions/${position.id}/trades` : "/api/v1/trades", trade ? "PUT" : "POST", {
      operationId: uuid(), dataGeneration: state.generation, expectedRevision: trade?.revision ?? null, accountId: position.accountId, instrumentId: position.instrumentId, direction: fd.get("direction"), quantity: fd.get("quantity"), executionPrice: fd.get("executionPrice"), fee: fd.get("fee") || "0", occurredAtMs: new Date(fd.get("occurred")).getTime(), currencyCode: position.currencyCode, cashLinked: fd.get("cashLinked") === "on", cashAccountId: fd.get("cashLinked") === "on" && fd.get("cashAccountId") ? Number(fd.get("cashAccountId")) : null
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
  form.parentDrawer = captureDrawerParent();
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

async function renderStatistics(refresh = false) {
  const [summary, dist, history] = await Promise.all([api("/api/v1/statistics/summary"), api("/api/v1/statistics/distribution"), api("/api/v1/statistics/history?granularity=DAILY&metric=TOTAL_ASSETS")]);
  const root = el("div");
  root.append(el("div", { class: "cards" }, metric(t("totalAssets"), fmt(summary.totalAssets, summary.currency)), metric(t("availableCash"), fmt(summary.availableCash, summary.currency)), metric(t("investmentValue"), fmt(summary.investmentValue, summary.currency)), metric(t("monthlyChange"), fmt(summary.monthlyChange, summary.currency), gainClass(summary.monthlyChange))));
  root.append(chart(history.items));
  [[t("accountDistribution"), dist.accounts], [t("assetTypeDistribution"), dist.assetTypes], [t("currencyDistribution"), dist.currencies]].forEach(([title, items]) => root.append(distribution(title, items, dist.currency)));
  replacePage(root, refresh);
}
function chart(points) {
  const valid = points.filter(v => v.value !== null && !v.future); const section = el("section", { class: "section" }, el("div", { class: "section-head" }, el("h2", { text: t("history") })));
  if (!valid.length) { section.append(el("div", { class: "empty", text: t("empty") })); return section; }
  const values = valid.map(v => Number(v.value)), min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  const coords = valid.map((v, i) => `${(i / Math.max(1, valid.length - 1)) * 100},${94 - ((Number(v.value) - min) / span) * 84}`).join(" ");
  const svg = el("svg", { class: "chart", viewBox: "0 0 100 100", preserveAspectRatio: "none", role: "img", "aria-label": t("history") });
  [10, 38, 66, 94].forEach(y => svg.append(el("line", { class: "chart-grid", x1: 0, x2: 100, y1: y, y2: y })));
  if (valid.length === 1) svg.append(el("circle", { class: "chart-point", cx: 50, cy: 52, r: 1.3 }));
  else svg.append(el("g", { class: "chart-curve" }, el("polyline", { points: coords })));
  section.append(svg); return section;
}
function distribution(title, items, currency) {
  const max = Math.max(1, ...items.map(v => Math.abs(Number(v.value))));
  const rows = items.map(v => el("div", { class: "bar-row" },
    el("span", { text: v.label }),
    el("div", { class: "bar-track" }, el("div", { class: "bar-fill", "data-bar-key": `${title}:${v.label}`, style: `width:${Math.min(100, Math.abs(Number(v.value)) / max * 100)}%` })),
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
    form?.validateInputs?.();
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
  form.dispatchEvent(new Event("save-settled"));
  if ($("#drawer").hidden && !document.body.dataset.ended) await refreshCurrent();
}
function errorMessage(error) {
  const labels = { STALE_RECORD: t("staleRecord"), STALE_BALANCE: t("staleBalance"), CURRENCY_LOCKED: t("currencyLocked"), SYMBOL_LOCKED: t("symbolLocked"), INSUFFICIENT_HOLDING: t("insufficientHolding"), FORMAT: t("invalidFormat") };
  return labels[error.code] || (error.code && t(error.code) !== error.code ? t(error.code) : null) || `${t("saveFailed")}: ${error.code || error.message}`;
}
let toastTimer, drawerOrigin, drawerCycle = 0, toastCycle = 0;
function captureDrawerParent() {
  const content = $("#drawer-content").firstElementChild;
  if ($("#drawer").hidden || !content?.drawerRoute) return null;
  return { content, route: content.drawerRoute, title: $("#drawer-title").textContent,
    kicker: $("#drawer-kicker").textContent, scrollTop: $("#drawer-content").scrollTop };
}
let sharedDrawerWidth = null, cancelDrawerResize = () => {};
function configureDrawerResize(drawer) {
  cancelDrawerResize();
  let handle = drawer.querySelector(".drawer-resize-handle");
  if (!handle) {
    handle = el("div", { class: "drawer-resize-handle", role: "separator", tabindex: 0,
      "aria-orientation": "vertical", "aria-controls": "drawer-content" });
    drawer.prepend(handle);
    let drag = null;
    const bounds = () => ({ min: Math.min(480, innerWidth - 32), max: innerWidth - 32 });
    const applyWidth = () => {
      const { min, max } = bounds(), width = Math.round(Math.max(min, Math.min(max, sharedDrawerWidth ?? 680)));
      drawer.style.setProperty("--drawer-width", `${width}px`);
      handle.setAttribute("aria-valuemin", String(min)); handle.setAttribute("aria-valuemax", String(max));
      handle.setAttribute("aria-valuenow", String(width)); handle.setAttribute("aria-valuetext", `${width} px`);
    };
    const finish = (commit) => {
      if (!drag) return;
      const previous = drag; drag = null;
      if (!commit) sharedDrawerWidth = previous.savedWidth;
      if (handle.hasPointerCapture(previous.id)) handle.releasePointerCapture(previous.id);
      document.body.classList.remove("resizing-drawer"); applyWidth();
    };
    cancelDrawerResize = () => finish(false);
    handle.addEventListener("pointerdown", event => {
      if (event.button !== 0 || !event.isPrimary || drawer.inert || matchMedia("(max-width:640px)").matches) return;
      event.preventDefault();
      // Direct manipulation must follow the pointer, not the entrance animation.
      motion.stop(drawer);
      drag = { id: event.pointerId, x: event.clientX, width: drawer.getBoundingClientRect().width, savedWidth: sharedDrawerWidth };
      handle.setPointerCapture(event.pointerId); handle.focus({ preventScroll: true });
      document.body.classList.add("resizing-drawer");
    });
    handle.addEventListener("pointermove", event => {
      if (drag?.id !== event.pointerId) return;
      const { min, max } = bounds();
      sharedDrawerWidth = Math.max(min, Math.min(max, drag.width + drag.x - event.clientX)); applyWidth();
    });
    handle.addEventListener("pointerup", event => { if (drag?.id === event.pointerId) finish(true); });
    handle.addEventListener("pointercancel", () => finish(false));
    handle.addEventListener("lostpointercapture", () => finish(false));
    handle.addEventListener("click", event => event.stopPropagation());
    handle.addEventListener("dblclick", () => { finish(false); sharedDrawerWidth = null; applyWidth(); });
    handle.addEventListener("keydown", event => {
      if (event.key === "Escape" && drag) { event.preventDefault(); event.stopPropagation(); finish(false); return; }
      if (!["ArrowLeft", "ArrowRight", "Home", "End", "Enter"].includes(event.key)) return;
      event.preventDefault(); finish(false);
      const { min, max } = bounds(), current = drawer.getBoundingClientRect().width;
      sharedDrawerWidth = event.key === "Enter" ? null : event.key === "Home" ? min : event.key === "End" ? max :
        Math.max(min, Math.min(max, current + (event.key === "ArrowLeft" ? 24 : -24)));
      applyWidth();
    });
    window.addEventListener("blur", () => finish(false));
    window.addEventListener("resize", () => { finish(false); applyWidth(); });
    handle.applyWidth = applyWidth;
  }
  handle.setAttribute("aria-label", t("resizePanel")); handle.title = t("resizePanelHint");
  handle.applyWidth();
}
function notify(message) {
  if (document.body.dataset.ended) return;
  const toast = $("#toast"), cycle = ++toastCycle;
  const alreadyVisible = !toast.hidden; toast.textContent = message; toast.hidden = false; clearTimeout(toastTimer);
  if (alreadyVisible) motion.stop(toast);
  if (!alreadyVisible) motion.run(toast, [{ opacity: 0, translate: "0 6px" }, { opacity: 1, translate: "0 0" }], { duration: 220 });
  toastTimer = setTimeout(async () => {
    const done = await motion.run(toast, [{ opacity: 1 }, { opacity: 0 }], { duration: 200 });
    if (done && cycle === toastCycle) toast.hidden = true;
  }, 5000);
}
function saved() { notify(t("saved")); }
function openDrawer(title, kicker, content, confirmedReplacement = false) {
  if (document.body.dataset.ended) return;
  finishLoading();
  const drawer = $("#drawer"), replacing = !drawer.hidden, origin = drawerOrigin;
  if (replacing && !confirmedReplacement && !closeDrawer(false, true)) return;
  ++drawerCycle; motion.stop(drawer); motion.stop($("#scrim")); drawer.inert = false;
  drawerOrigin = replacing ? origin : document.activeElement;
  $("#drawer-title").textContent = title; $("#drawer-kicker").textContent = kicker || "";
  clear($("#drawer-content")); $("#drawer-content").append(content);
  configureDrawerResize(drawer);
  $("#scrim").hidden = false; $("#drawer").hidden = false; $("#workspace").inert = true;
  $("#drawer-content").scrollTop = 0;
  document.body.classList.add("modal-open");
  const form = content.matches("form") ? content : $("form", content);
  if (form) {
    const markDirty = () => {
      if (!form.isDirty || form.isDirty()) form.dataset.dirty = "true";
      else delete form.dataset.dirty;
    };
    form.addEventListener("input", markDirty);
    form.addEventListener("change", markDirty);
    form.addEventListener("submit", event => { event.preventDefault(); $("[data-save]", form)?.click(); });
  }
  $("#drawer-close").focus();
  motion.bindDetails(content);
  if (replacing) motion.run($("#drawer-content"), [{ opacity: .45, transform: "translateY(5px)" }, { opacity: 1, transform: "none" }], { duration: 260 });
  else {
    const offset = matchMedia("(max-width:640px)").matches ? "translateY(20px)" : "translateX(24px)";
    motion.run(drawer, [{ opacity: .65, transform: offset }, { opacity: 1, transform: "none" }], { duration: 380 });
    motion.run($("#scrim"), [{ opacity: 0 }, { opacity: 1 }], { duration: 300 });
    motion.enter(content);
  }
}
function closeDrawer(force = false, immediate = false) {
  const drawer = $("#drawer");
  if (document.body.dataset.ended) { immediate = true; finishLoading(); motion.stop($("#content")); motion.stop($("#toast")); }
  if (drawer.hidden) return true;
  if (drawer.inert && force !== true && !immediate) return false;
  if (force !== true && state.saving) return false;
  const form = $("#drawer-content form");
  if (force !== true && form?.pendingSubmission) { notify(t("unknownResult")); return false; }
  if (force !== true && form?.dataset.dirty && !confirm(t("discard"))) return false;
  const parent = $("#drawer-content").firstElementChild?.parentDrawer;
  if (!immediate && parent) {
    const reload = force === true || state.pendingRefresh;
    if (force === true) state.pendingRefresh = true;
    // Return within the existing shell; a successful save must reload fresh account data.
    const content = reload ? el("div", { class: "empty", role: "status", text: t("loading") }) : parent.content;
    openDrawer(parent.title, parent.kicker, content, true);
    $("#drawer-content").scrollTop = parent.scrollTop;
    if (reload) {
      const returnState = { cycle: drawerCycle, scrollTop: parent.scrollTop };
      // Preserve the original route chain when refreshing a parent after a mutation.
      content.parentDrawer = parent.route.parentDrawer || null;
      const retry = () => (parent.route.kind === "position"
        ? positionDetail(parent.route.id, returnState, parent.route.parentDrawer)
        : accountDetail(parent.route.id, returnState)).catch(error => {
        if (error.name === "AbortError" || returnState.cycle !== drawerCycle || document.body.dataset.ended) return;
        clear(content); content.append(el("p", { role: "alert", text: t("loadFailed") }), button(t("refresh"), retry));
      });
      retry();
    }
    return true;
  }
  if (form?.deletionTicket) api("/api/v1/account-deletion-cancel", { method: "POST", body: JSON.stringify({ ticket: form.deletionTicket }) }).catch(() => {});
  cancelDrawerResize();
  const cycle = ++drawerCycle;
  const finish = () => {
    if (cycle !== drawerCycle) return;
    motion.stop(drawer); motion.stop($("#scrim"));
    $("#scrim").hidden = true; drawer.hidden = true; drawer.inert = false; clear($("#drawer-content"));
    $("#workspace").inert = false; document.body.classList.remove("modal-open");
    if (drawerOrigin?.isConnected) drawerOrigin.focus({ preventScroll: true }); drawerOrigin = null;
    if (!document.body.dataset.ended && (state.pendingRefresh || force === true)) scheduleRefresh();
  };
  if (immediate || document.body.dataset.ended) finish();
  else {
    drawer.inert = true;
    const style = getComputedStyle(drawer), offset = matchMedia("(max-width:640px)").matches ? "translateY(14px)" : "translateX(18px)";
    motion.run($("#scrim"), [{ opacity: getComputedStyle($("#scrim")).opacity }, { opacity: 0 }], { duration: 240 });
    motion.run(drawer, [{ opacity: style.opacity, transform: style.transform }, { opacity: 0, transform: offset }], { duration: 260 }).then(done => { if (done) finish(); });
  }
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
