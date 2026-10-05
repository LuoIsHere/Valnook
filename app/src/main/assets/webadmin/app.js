"use strict";

const $ = (selector, root = document) => root.querySelector(selector);
const SESSION_TOKEN_KEY = "valnook-session-token";
const CSRF_TOKEN_KEY = "valnook-csrf-token";
const state = {
  csrf: null, sessionToken: null, socket: null, heartbeat: null, session: null, page: "accounts",
  connecting: null, reconnecting: null, allowReconnect: false,
  accounts: [], instruments: [], assetTypes: [], positions: [], investmentTab: "instruments",
  recordsCursor: null, recordsHistory: [], locale: navigator.language.toLowerCase().startsWith("zh") ? "zh-CN" : "en", generation: null
};
const copy = {
  "zh-CN": {
    accounts: "账户", records: "记录", investments: "投资", statistics: "统计", connected: "已连接", demoMode: "演示模式",
    sessionEnded: "会话已结束", sessionLost: "连接已失效，请回到手机重新配对。", reconnecting: "连接暂时中断，正在恢复…", loading: "正在加载…",
    empty: "暂无数据", save: "保存", cancel: "取消", saved: "✓ 已保存", newAccount: "新增账户",
    newInstrument: "新增投资标的", instruments: "投资标的", positions: "持仓", refresh: "刷新",
    details: "详情", edit: "编辑", trade: "交易", end: "结束会话", loadMore: "加载更多",
    pairTitle: "连接到手机", pairCopy: "请在手机上打开网页端管理，并输入手机显示的 6 位配对码。", pairCode: "配对码", connect: "连接",
    trustedLan: "仅用于可信局域网。此版本使用 HTTP/WS，不提供传输加密。", mainNav: "主导航", theme: "切换主题", close: "关闭",
    pairWrong: "配对码不正确，手机上已生成新码。", pairFailed: "连接失败", wsFailed: "WebSocket 连接未建立。请检查浏览器代理、VPN 或安全软件，然后在手机上重新开启网页端管理。", qrConnecting: "正在使用二维码凭据连接…", loadFailed: "加载失败",
    account: "账户", cash: "可用现金", creditBalance: "信用账户余额", balanceAccounts: "账户", deposits: "定期", investmentValue: "投资市值", totalAssets: "总资产", complete: "完整",
    settled: "已结算", holding: "持有", name: "名称", note: "备注", currency: "币种", balance: "余额", principal: "本金",
    start: "开始", endDate: "结束", annualRate: "年利率", status: "状态", quantity: "数量", currentPrice: "现价", marketValue: "市值", pnl: "盈亏",
    accountDetails: "账户详情", openDeposit: "开立存单", editAccount: "编辑账户", newCashName: "新增现金账户名称",
    newCashCurrency: "新增现金账户币种", newCashBalance: "新增现金账户余额", fromDate: "开始日期", toDate: "结束日期", all: "全部",
    type: "类型", deposit: "存单", search: "搜索", filter: "筛选", time: "时间", subaccount: "子账户", object: "对象",
    amountQuantity: "金额 / 数量", updated: "更新", amount: "金额", editCashRecord: "编辑现金记录", assetTypes: "资产类型",
    nameCode: "名称 / 代码", assetType: "资产类型", priceUpdated: "价格更新", newPosition: "新增持仓", instrument: "投资标的",
    averageCost: "持仓均价", holdingCost: "持仓成本", realized: "已实现", unrealized: "浮动盈亏", assetTypeName: "资产类型名称",
    newAssetType: "新增资产类型", symbol: "代码", price: "当前价格", editInstrument: "编辑投资标的", direction: "方向",
    buy: "买入", sell: "卖出", executionPrice: "成交单价", fee: "手续费", cashLink: "现金联动", cashAccount: "现金账户",
    none: "不选择", newTrade: "新增交易", delete: "删除", confirmDelete: "确定删除这条交易？", editTrade: "编辑交易",
    rate: "年利率 (%)", startDate: "开始日期", settle: "结算", editDeposit: "编辑存单", availableCash: "可用现金",
    monthlyChange: "本月变化", accountDistribution: "主账户分布", assetTypeDistribution: "资产类型分布", currencyDistribution: "币种分布",
    history: "历史资产变化", staleRecord: "记录已变化，请刷新后重试。", staleBalance: "余额已变化，请刷新后重试。",
    currencyLocked: "该币种已被交易使用，不能修改。", symbolLocked: "代码已被交易使用，不能修改。",
    insufficientHolding: "持仓数量不足。", invalidFormat: "输入格式不正确。", saveFailed: "保存失败",
    cashChange: "余额调整", depositOpen: "存单开立", depositClose: "存单结算", operation: "操作",
    savingsAccount: "储蓄账户", creditAccount: "信用账户", creditLimit: "信用额度", usedLimit: "已用额度", availableLimit: "可用额度",
    limitSource: "额度来源", independentLimit: "独立额度", statementDay: "账单日", dueRule: "最后还款日规则", dueValue: "规则日期/天数",
    dueAfter: "账单日后 N 天", dueFixed: "每月固定日期"
  },
  "en": {
    accounts: "Accounts", records: "Records", investments: "Investments", statistics: "Statistics", demoMode: "Demo mode",
    connected: "Connected", sessionEnded: "Session ended", sessionLost: "Session expired. Pair again from your phone.", reconnecting: "Connection interrupted. Reconnecting…",
    loading: "Loading…", empty: "No data", save: "Save", cancel: "Cancel", saved: "✓ Saved",
    newAccount: "New account", newInstrument: "New instrument", instruments: "Instruments", positions: "Positions",
    refresh: "Refresh", details: "Details", edit: "Edit", trade: "Trade", end: "End session", loadMore: "Load more",
    pairTitle: "Connect to phone", pairCopy: "Open Web administration on your phone and enter its 6-digit pairing code.", pairCode: "Pairing code", connect: "Connect",
    trustedLan: "Use only on a trusted local network. This version uses HTTP/WS without transport encryption.", mainNav: "Main navigation", theme: "Toggle theme", close: "Close",
    pairWrong: "The pairing code was incorrect. A new code is now shown on the phone.", pairFailed: "Connection failed", wsFailed: "The WebSocket connection could not be established. Check the browser proxy, VPN, or security software, then restart Web administration on the phone.", qrConnecting: "Connecting with QR credentials…", loadFailed: "Load failed",
    account: "Account", cash: "Available cash", creditBalance: "Credit account balance", balanceAccounts: "Accounts", deposits: "Deposits", investmentValue: "Investment value", totalAssets: "Total assets", complete: "Complete",
    settled: "Settled", holding: "Open", name: "Name", note: "Note", currency: "Currency", balance: "Balance", principal: "Principal",
    start: "Start", endDate: "End", annualRate: "Annual rate", status: "Status", quantity: "Quantity", currentPrice: "Current price", marketValue: "Market value", pnl: "P/L",
    accountDetails: "Account details", openDeposit: "Open deposit", editAccount: "Edit account", newCashName: "New cash account name",
    newCashCurrency: "New cash account currency", newCashBalance: "New cash account balance", fromDate: "From", toDate: "To", all: "All",
    type: "Type", deposit: "Deposit", search: "Search", filter: "Filter", time: "Time", subaccount: "Subaccount", object: "Object",
    amountQuantity: "Amount / quantity", updated: "Updated", amount: "Amount", editCashRecord: "Edit cash record", assetTypes: "Asset types",
    nameCode: "Name / symbol", assetType: "Asset type", priceUpdated: "Price updated", newPosition: "New position", instrument: "Instrument",
    averageCost: "Average cost", holdingCost: "Holding cost", realized: "Realized", unrealized: "Unrealized", assetTypeName: "Asset type name",
    newAssetType: "New asset type", symbol: "Symbol", price: "Current price", editInstrument: "Edit instrument", direction: "Direction",
    buy: "Buy", sell: "Sell", executionPrice: "Execution price", fee: "Fee", cashLink: "Link cash", cashAccount: "Cash account",
    none: "None", newTrade: "New trade", delete: "Delete", confirmDelete: "Delete this trade?", editTrade: "Edit trade",
    rate: "Annual rate (%)", startDate: "Start date", settle: "Settle", editDeposit: "Edit deposit", availableCash: "Available cash",
    monthlyChange: "Change this month", accountDistribution: "Account distribution", assetTypeDistribution: "Asset type distribution", currencyDistribution: "Currency distribution",
    history: "Asset history", staleRecord: "This record changed. Refresh and try again.", staleBalance: "This balance changed. Refresh and try again.",
    currencyLocked: "The currency is locked after use in a trade.", symbolLocked: "The symbol is locked after use in a trade.",
    insufficientHolding: "Insufficient holding quantity.", invalidFormat: "Check the input format.", saveFailed: "Save failed",
    cashChange: "Balance adjustment", depositOpen: "Deposit opened", depositClose: "Deposit settled", operation: "Action",
    savingsAccount: "Savings account", creditAccount: "Credit account", creditLimit: "Credit limit", usedLimit: "Used", availableLimit: "Available",
    limitSource: "Limit source", independentLimit: "Independent limit", statementDay: "Statement day", dueRule: "Payment due rule", dueValue: "Rule day/days",
    dueAfter: "Days after statement", dueFixed: "Fixed day each month"
  }
};
const t = key => (copy[state.locale] || copy["zh-CN"])[key] || key;

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(attrs).forEach(([key, value]) => {
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value == null ? "" : String(value);
    else if (key.startsWith("on") && typeof value === "function") node.addEventListener(key.slice(2), value);
    else if (value !== null && value !== undefined && value !== false) node.setAttribute(key, value === true ? "" : String(value));
  });
  children.flat().filter(v => v !== null && v !== undefined).forEach(child => node.append(child.nodeType ? child : document.createTextNode(String(child))));
  return node;
}
function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
function fmt(value, currency = "") {
  if (value === null || value === undefined || value === "") return "—";
  const n = Number(value);
  return `${Number.isFinite(n) ? new Intl.NumberFormat(state.locale, { maximumFractionDigits: 8 }).format(n) : value}${currency ? ` ${currency}` : ""}`;
}
function when(ms) { return ms ? new Intl.DateTimeFormat(state.locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(ms)) : "—"; }
function epochDay(date) { return Math.floor(new Date(`${date}T00:00:00Z`).getTime() / 86400000); }
function gainClass(value) { return Number(value) > 0 ? "gain" : Number(value) < 0 ? "loss" : ""; }
function accountName(id) { return state.accounts.find(v => v.id === id)?.name || `#${id}`; }
function uuid() { return crypto.randomUUID(); }

function restoreSessionCredentials() {
  try {
    state.sessionToken = sessionStorage.getItem(SESSION_TOKEN_KEY);
    state.csrf = sessionStorage.getItem(CSRF_TOKEN_KEY);
  } catch (_) { state.sessionToken = null; state.csrf = null; }
}
function rememberSessionCredentials(sessionToken, csrfToken) {
  if (sessionToken) state.sessionToken = sessionToken;
  if (csrfToken) state.csrf = csrfToken;
  try {
    if (state.sessionToken) sessionStorage.setItem(SESSION_TOKEN_KEY, state.sessionToken);
    if (state.csrf) sessionStorage.setItem(CSRF_TOKEN_KEY, state.csrf);
  } catch (_) { /* The in-memory credentials still support the current page. */ }
}
function clearSessionCredentials() {
  state.sessionToken = null; state.csrf = null;
  try { sessionStorage.removeItem(SESSION_TOKEN_KEY); sessionStorage.removeItem(CSRF_TOKEN_KEY); } catch (_) {}
}

async function api(path, options = {}) {
  const headers = { Accept: "application/json", ...(options.headers || {}) };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (state.sessionToken) headers.Authorization = `Bearer ${state.sessionToken}`;
  if (state.csrf && options.method && options.method !== "GET") headers["X-Valnook-CSRF"] = state.csrf;
  const response = await fetch(path, { credentials: "same-origin", ...options, headers });
  let payload = null;
  try { payload = await response.json(); } catch (_) { payload = {}; }
  if (!response.ok) {
    const error = new Error(payload?.error?.code || `HTTP_${response.status}`);
    error.code = payload?.error?.code;
    error.refreshRequired = payload?.error?.refreshRequired;
    const expectedUnauthenticated = path.startsWith("/api/v1/pair/") || path === "/api/v1/session/resume";
    if ((response.status === 401 || response.status === 410) && !expectedUnauthenticated) expireSession();
    throw error;
  }
  if (payload?.dataGeneration !== undefined) state.generation = payload.dataGeneration;
  return payload;
}

async function pair(kind, value) {
  const status = $("#pair-status");
  const button = $("#pair-submit");
  status.textContent = ""; button.disabled = true;
  try {
    const data = await api(`/api/v1/pair/${kind}`, { method: "POST", body: JSON.stringify({ [kind === "qr" ? "token" : "code"]: value }) });
    rememberSessionCredentials(data.sessionToken, data.csrfToken);
    state.allowReconnect = true;
    delete document.body.dataset.ended;
    history.replaceState(null, "", location.pathname);
    await connect();
  } catch (error) {
    if (String(error.message).startsWith("WS_")) {
      $("#pair-form").hidden = true; $("#pair-copy").textContent = t("wsFailed");
    }
    status.textContent = error.code === "PAIR_CODE_ROTATED" ? t("pairWrong") : `${t("pairFailed")}: ${error.code || error.message || "UNKNOWN"}`;
  } finally { button.disabled = false; }
}

async function connect() {
  const deadline = Date.now() + 40000;
  while (true) {
    try {
      await openSocket();
      break;
    } catch (error) {
      if (!String(error.message).startsWith("WS_") || Date.now() >= deadline) throw error;
      await delay(1000);
    }
  }
  const session = await api("/api/v1/session");
  applySession(session);
  showWorkspace();
  startHeartbeat();
  await preload();
  await navigate("accounts");
  if (state.socket?.readyState !== WebSocket.OPEN) reconnect();
}

function openSocket() {
  if (state.connecting) return state.connecting;
  const operation = new Promise((resolve, reject) => {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const ticket = state.sessionToken ? `?ticket=${encodeURIComponent(state.sessionToken)}` : "";
    const socket = new WebSocket(`${protocol}//${location.host}/ws/v1/session${ticket}`);
    const timer = setTimeout(() => { socket.close(); reject(new Error("WS_TIMEOUT")); }, 15000);
    let ready = false;
    socket.addEventListener("error", () => {
      if (!ready) { clearTimeout(timer); reject(new Error("WS_FAILED")); }
    }, { once: true });
    socket.addEventListener("message", event => {
      let msg; try { msg = JSON.parse(event.data); } catch (_) { return; }
      if (msg.type === "ready" && !ready) {
        ready = true; clearTimeout(timer);
        const previous = state.socket;
        state.socket = socket;
        if (previous && previous !== socket && previous.readyState < WebSocket.CLOSING) previous.close(1000, "replaced");
        socket.send('{"type":"heartbeat"}'); resolve(); return;
      }
      if (msg.type === "dataChanged") refreshCurrent();
      if (msg.type === "serverClosing") expireSession();
    });
    socket.addEventListener("close", () => {
      clearTimeout(timer);
      if (!ready) { reject(new Error("WS_CLOSED")); return; }
      if (state.socket === socket) {
        state.socket = null;
        if (state.session && state.allowReconnect && !document.body.dataset.ended) reconnect();
      }
    });
  });
  state.connecting = operation;
  return operation.finally(() => { if (state.connecting === operation) state.connecting = null; });
}

function applySession(session) {
  state.session = session;
  state.locale = session.language === "ENGLISH" ? "en" : "zh-CN";
  document.documentElement.lang = state.locale;
  applyGainPalette(session.gainLossColors);
}

function startHeartbeat() {
  if (state.heartbeat) clearInterval(state.heartbeat);
  state.heartbeat = setInterval(() => {
    if (state.socket?.readyState === WebSocket.OPEN) state.socket.send('{"type":"heartbeat"}');
  }, 5000);
}

function reconnect() {
  if (state.reconnecting || !state.allowReconnect || document.body.dataset.ended) return state.reconnecting;
  if (!$("#workspace").hidden) $("#connected-label").textContent = t("reconnecting");
  const operation = (async () => {
    const deadline = Date.now() + 40000;
    while (Date.now() < deadline && !document.body.dataset.ended) {
      try {
        await openSocket();
        applySession(await api("/api/v1/session"));
        startHeartbeat();
        showWorkspace();
        await preload();
        await navigate(state.page);
        return;
      } catch (error) {
        if (error.code === "SESSION_EXPIRED" || document.body.dataset.ended) return;
        await delay(1000);
      }
    }
    expireSession();
  })();
  state.reconnecting = operation;
  operation.finally(() => { if (state.reconnecting === operation) state.reconnecting = null; });
  return operation;
}

function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

async function resumeExistingSession() {
  try {
    const data = await api("/api/v1/session/resume", { method: "POST", body: "{}" });
    rememberSessionCredentials(state.sessionToken, data.csrfToken);
    state.allowReconnect = true;
    delete document.body.dataset.ended;
    $("#pair-form").hidden = true;
    $("#pair-copy").textContent = t("reconnecting");
    await connect();
  } catch (error) {
    if (error.code === "SESSION_EXPIRED") clearSessionCredentials();
    if (!state.session) {
      $("#pair-form").hidden = false;
      applyPairingCopy();
    }
  }
}

function applyGainPalette(value) {
  if (value === "RED_GAIN") {
    document.documentElement.style.setProperty("--gain", "#c43c35");
    document.documentElement.style.setProperty("--loss", "#16855b");
  }
}
function applyPairingCopy() {
  document.documentElement.lang = state.locale;
  $("#pair-title").textContent = t("pairTitle");
  $("#pair-copy").textContent = t("pairCopy");
  $(".pair-form label").textContent = t("pairCode");
  $("#pair-submit").textContent = t("connect");
  $(".security-note").textContent = t("trustedLan");
}
function showWorkspace() {
  $("#pairing").hidden = true; $("#workspace").hidden = false;
  $("#connected-label").textContent = state.session?.dataMode === "DEMO" ? `${t("connected")} · ${t("demoMode")}` : t("connected");
  $("#end-session").textContent = t("end");
  $("#nav").setAttribute("aria-label", t("mainNav")); $("#theme-toggle").setAttribute("aria-label", t("theme"));
  $("#drawer-close").setAttribute("aria-label", t("close"));
  renderNav();
}
function expireSession() {
  if (document.body.dataset.ended) return;
  document.body.dataset.ended = "true";
  state.allowReconnect = false;
  clearSessionCredentials();
  if (state.heartbeat) clearInterval(state.heartbeat);
  closeDrawer();
  $("#workspace").hidden = true; $("#pairing").hidden = false;
  $("#pair-form").hidden = true; $("#pair-copy").textContent = t("sessionLost");
  $("#pair-status").textContent = "";
}
async function preload() {
  const [accounts, instruments, types, positions] = await Promise.all([
    api("/api/v1/accounts"), api("/api/v1/instruments"), api("/api/v1/asset-types"), api("/api/v1/positions")
  ]);
  state.accounts = accounts.items; state.instruments = instruments.items; state.assetTypes = types.items; state.positions = positions.items;
}
function renderNav() {
  const nav = $("#nav"); clear(nav);
  [["accounts", "▤"], ["records", "≡"], ["investments", "⌁"], ["statistics", "⌁"]].forEach(([key, icon]) => {
    nav.append(el("button", { class: `nav-item ${state.page === key ? "active" : ""}`, type: "button", onclick: () => navigate(key), "aria-label": t(key) },
      el("span", { class: "nav-icon", "aria-hidden": "true", text: icon }), el("span", { class: "nav-label", text: t(key) })));
  });
}
async function navigate(page) {
  state.page = page; renderNav(); closeDrawer();
  $("#page-kicker").textContent = state.session?.dataMode === "DEMO" ? `${t("demoMode")} · WEB ADMIN` : "WEB ADMIN";
  $("#page-title").textContent = t(page); showLoading();
  try {
    if (page === "accounts") await renderAccounts();
    else if (page === "records") await renderRecords(true);
    else if (page === "investments") await renderInvestments();
    else await renderStatistics();
  } catch (error) { showError(error); }
}
async function refreshCurrent() { await preload(); await navigate(state.page); }
function showLoading() { const root = $("#content"); clear(root); root.append(el("p", { class: "muted", text: t("loading") })); }
function showError(error) { const root = $("#content"); clear(root); root.append(el("div", { class: "empty", text: `${t("loadFailed")}: ${error.code || error.message}` })); }
function emptyRow(cols) { return el("tr", {}, el("td", { colspan: cols, class: "empty", text: t("empty") })); }
function table(headers, rows) {
  const body = el("tbody"); if (!rows.length) body.append(emptyRow(headers.length)); else rows.forEach(row => body.append(row));
  return el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, el("tr", {}, ...headers.map(h => el("th", { text: h, class: h.numeric ? "numeric" : "" })))), body));
}
function toolbar(...nodes) { return el("div", { class: "toolbar" }, ...nodes); }
function button(label, click, kind = "secondary") { return el("button", { class: kind, type: "button", text: label, onclick: click }); }
function metric(label, value, cls = "") { return el("div", { class: "metric" }, el("div", { class: "subtle", text: label }), el("div", { class: `value ${cls}`, text: value })); }

async function renderAccounts() {
  const data = await api("/api/v1/accounts"); state.accounts = data.items;
  const root = $("#content"); clear(root);
  root.append(toolbar(el("div", { class: "spacer" }), button(t("newAccount"), () => accountForm(null), "primary")));
  const rows = data.items.map(item => el("tr", { "data-clickable": "true", onclick: () => accountDetail(item.id) },
    el("td", {}, el("strong", { text: item.name }), el("div", { class: "subtle", text: item.note || "—" })),
    el("td", { class: "numeric", text: fmt(item.cash, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.creditBalance, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.deposits, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.investments, data.baseCurrency) }),
    el("td", { class: "numeric", text: fmt(item.total, data.baseCurrency) }),
    el("td", { text: item.complete ? "✓" : "!" })));
  root.append(table([t("account"), t("cash"), t("creditBalance"), t("deposits"), t("investmentValue"), t("totalAssets"), t("complete")], rows));
}
async function accountDetail(id) {
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
  const form = el("form", { class: "form-grid" });
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
    await saveForm(submit, error, account ? `/api/v1/accounts/${account.id}` : "/api/v1/accounts", account ? "PUT" : "POST", body);
  }));
  openDrawer(account ? t("editAccount") : t("newAccount"), "ACCOUNT", form);
}

async function renderRecords(reset) {
  if (reset) { state.recordsHistory = []; state.recordsCursor = null; }
  const root = $("#content"); if (reset) clear(root);
  let controls = $("#record-controls");
  if (!controls) {
    controls = el("form", { id: "record-controls", class: "toolbar" },
      field(t("fromDate"), "from", "", false, "date"), field(t("toDate"), "to", "", false, "date"),
      selectField(t("account"), "accountId", [{ value: "", label: t("all") }, ...state.accounts.map(v => ({ value: v.id, label: v.name }))]),
      selectField(t("type"), "type", [{ value: "", label: t("all") }, { value: "CASH", label: t("cash") }, { value: "DEPOSIT", label: t("deposit") }, { value: "TRADE", label: t("trade") }]),
      field(t("search"), "query", ""), button(t("filter"), () => renderRecords(true), "primary"));
    root.append(controls, el("div", { id: "records-table" }));
  }
  const fd = new FormData(controls); const params = new URLSearchParams({ pageSize: "50" });
  if (fd.get("from")) params.set("from", String(new Date(`${fd.get("from")}T00:00:00`).getTime()));
  if (fd.get("to")) params.set("to", String(new Date(`${fd.get("to")}T23:59:59.999`).getTime()));
  ["accountId", "type", "query"].forEach(k => { if (fd.get(k)) params.set(k, fd.get(k)); });
  if (!reset && state.recordsCursor) params.set("cursor", state.recordsCursor);
  const data = await api(`/api/v1/records?${params}`); state.recordsHistory.push(...data.items); state.recordsCursor = data.nextCursor;
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
  await preload(); const root = $("#content"); clear(root);
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
  if (trade) actionBar.prepend(button(t("delete"), async () => {
    if (!confirm(t("confirmDelete"))) return;
    try { await api(`/api/v1/trades/${trade.id}`, { method: "DELETE", body: JSON.stringify({ operationId: uuid(), dataGeneration: state.generation, expectedRevision: trade.revision }) }); saved(); closeDrawer(); await refreshCurrent(); } catch (e) { error.textContent = e.code || e.message; }
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
  if (deposit && !deposit.closed) bar.prepend(button(t("settle"), async () => {
    const fd = new FormData(form);
    try { await api(`/api/v1/deposits/${deposit.id}/close`, { method: "POST", body: JSON.stringify({ operationId: uuid(), dataGeneration: state.generation,
      cashLinked: fd.get("closeCashLinked") === "on", cashAccountId: fd.get("closeCashLinked") === "on" && fd.get("closeCashAccountId") ? Number(fd.get("closeCashAccountId")) : null }) }); saved(); closeDrawer(); await refreshCurrent(); } catch (e) { error.textContent = errorMessage(e); }
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
  const saveButton = button(t("save"), () => saveHandler(saveButton), "primary");
  return el("div", { class: "drawer-actions full" }, button(t("cancel"), closeDrawer), saveButton);
}
async function saveForm(buttonNode, errorNode, path, method, body) {
  errorNode.textContent = ""; buttonNode.disabled = true;
  try { await api(path, { method, body: JSON.stringify(body) }); saved(); closeDrawer(); await preload(); await navigate(state.page); }
  catch (error) { errorNode.textContent = errorMessage(error); }
  finally { buttonNode.disabled = false; }
}
function errorMessage(error) {
  const labels = { STALE_RECORD: t("staleRecord"), STALE_BALANCE: t("staleBalance"), CURRENCY_LOCKED: t("currencyLocked"), SYMBOL_LOCKED: t("symbolLocked"), INSUFFICIENT_HOLDING: t("insufficientHolding"), FORMAT: t("invalidFormat") };
  return labels[error.code] || `${t("saveFailed")}: ${error.code || error.message}`;
}
function saved() { $("#save-state").textContent = t("saved"); setTimeout(() => { $("#save-state").textContent = ""; }, 2400); }
function openDrawer(title, kicker, content) { $("#drawer-title").textContent = title; $("#drawer-kicker").textContent = kicker || ""; clear($("#drawer-content")); $("#drawer-content").append(content); $("#scrim").hidden = false; $("#drawer").hidden = false; $("#drawer-close").focus(); }
function closeDrawer() { $("#scrim").hidden = true; $("#drawer").hidden = true; clear($("#drawer-content")); }
function toLocalDateTime(ms) { const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000); return d.toISOString().slice(0, 16); }

$("#pair-form").addEventListener("submit", event => { event.preventDefault(); const code = $("#pair-code").value.trim(); if (/^[0-9]{6}$/.test(code)) pair("code", code); });
$("#drawer-close").addEventListener("click", closeDrawer); $("#scrim").addEventListener("click", closeDrawer);
document.addEventListener("keydown", event => { if (event.key === "Escape") closeDrawer(); });
window.addEventListener("online", () => reconnect());
window.addEventListener("pageshow", () => { if (state.session && state.socket?.readyState !== WebSocket.OPEN) reconnect(); });
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && state.session && state.socket?.readyState !== WebSocket.OPEN) reconnect();
});
$("#end-session").addEventListener("click", async () => { try { await api("/api/v1/session/end", { method: "POST", body: "{}" }); } finally { clearSessionCredentials(); document.body.dataset.ended = "true"; location.reload(); } });
$("#theme-toggle").addEventListener("click", () => { const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark"; document.documentElement.dataset.theme = next; localStorage.setItem("valnook-theme", next); });
const savedTheme = localStorage.getItem("valnook-theme"); if (savedTheme === "light" || savedTheme === "dark") document.documentElement.dataset.theme = savedTheme;
const savedWidth = Number(localStorage.getItem("valnook-sidebar")); if (savedWidth >= 200 && savedWidth <= 320) document.documentElement.style.setProperty("--sidebar", `${savedWidth}px`);
$("#sidebar-resizer").addEventListener("pointerdown", event => { const move = e => { const width = Math.max(200, Math.min(320, e.clientX)); document.documentElement.style.setProperty("--sidebar", `${width}px`); localStorage.setItem("valnook-sidebar", String(width)); }; const up = () => { document.removeEventListener("pointermove", move); document.removeEventListener("pointerup", up); }; document.addEventListener("pointermove", move); document.addEventListener("pointerup", up); event.preventDefault(); });

const fragment = new URLSearchParams(location.hash.slice(1)); const qr = fragment.get("pair");
restoreSessionCredentials();
applyPairingCopy();
if (qr) { $("#pair-form").hidden = true; $("#pair-copy").textContent = t("qrConnecting"); pair("qr", qr); }
else { resumeExistingSession(); }
