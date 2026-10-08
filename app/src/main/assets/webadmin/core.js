"use strict";
import { decimalText } from "./decimal.js";

const $ = (selector, root = document) => root.querySelector(selector);
const SESSION_TOKEN_KEY = "valnook-session-token";
const CSRF_TOKEN_KEY = "valnook-csrf-token";
const hooks = {};
const state = {
  viewEpoch: 0, saving: false, pendingRefresh: false, recordFilters: {}, idleDeadline: 0,
  csrf: null, sessionToken: null, socket: null, heartbeat: null, session: null, page: "accounts",
  connecting: null, reconnecting: null, allowReconnect: false,
  accounts: [], instruments: [], assetTypes: [], positions: [], investmentTab: "instruments",
  expandedInvestmentAccounts: new Set(),
  recordsCursor: null, recordsHistory: [], locale: navigator.language.toLowerCase().startsWith("zh") ? "zh-CN" : "en", generation: null
};
const copy = {
  "zh-CN": {
    deletionCode: "确认码", deleteParentScope: "删除此主账户及全部子账户、现金流水、存单、持仓和交易。保留全局投资标的及其他账户。历史统计会重算，无法撤销。",
    deleteChildScope: "删除此子账户及现金流水。保留存单与投资交易，解除其现金关联；不转移余额，其他账户余额不变。历史统计会重算，无法撤销。",
    deleteLimitNotice: "关联账户分别继承总额度，汇总额度可能增加；各自欠款不变。",
    accounts: "账户", records: "记录", investments: "投资", statistics: "统计", connected: "已连接", demoMode: "演示模式",
    sessionEnded: "会话已结束", sessionLost: "连接已失效，请回到手机重新配对。", reconnecting: "连接暂时中断，正在恢复…", loading: "正在加载…",
    empty: "暂无数据", save: "保存", cancel: "取消", saved: "✓ 已保存", showDeposits: "显示定期汇总", showInvestments: "显示投资汇总", showOnAccounts: "在账户页显示", includeAvailable: "计入可用现金", searchInstrument: "搜索投资品",
    newAccount: "新增账户",
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
    deletionCode: "Confirmation code", deleteParentScope: "Delete this account, all subaccounts, cash entries, deposits, holdings and trades. Keep global instruments and other accounts. Historical totals are recalculated. This cannot be undone.",
    deleteChildScope: "Delete this subaccount and cash entries. Keep deposits and trades without their cash links. No balance is transferred; other balances stay unchanged. Historical totals are recalculated. This cannot be undone.",
    deleteLimitNotice: "Each linked account inherits the full limit, which may increase the combined limit. Their debts stay unchanged.",
    accounts: "Accounts", records: "Records", investments: "Investments", statistics: "Statistics", demoMode: "Demo mode",
    connected: "Connected", sessionEnded: "Session ended", sessionLost: "Session expired. Pair again from your phone.", reconnecting: "Connection interrupted. Reconnecting…",
    loading: "Loading…", empty: "No data", save: "Save", cancel: "Cancel", saved: "✓ Saved",
    showDeposits: "Show deposit summary", showInvestments: "Show investment summary", showOnAccounts: "Show on Accounts page", includeAvailable: "Include in available cash", searchInstrument: "Search instruments",
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
  const node = ["svg", "path", "line", "polyline", "circle"].includes(tag)
    ? document.createElementNS("http://www.w3.org/2000/svg", tag) : document.createElement(tag);
  Object.entries(attrs).forEach(([key, value]) => {
    if (key === "class") node.setAttribute("class", value);
    else if (key === "style") { const [property, setting] = value.split(":"); node.style.setProperty(property, setting); }
    else if (key === "text") node.textContent = value == null ? "" : String(value);
    else if (key.startsWith("on") && typeof value === "function") node.addEventListener(key.slice(2), event => {
      try { Promise.resolve(value(event)).catch(error => hooks.error?.(error)); }
      catch (error) { hooks.error?.(error); }
    });
    else if (value !== null && value !== undefined && value !== false) node.setAttribute(key, value === true ? "" : String(value));
  });
  children.flat().filter(v => v !== null && v !== undefined).forEach(child => node.append(child.nodeType ? child : document.createTextNode(String(child))));
  if (attrs.class?.includes("drawer-error")) node.setAttribute("role", "alert");
  return node;
}
function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
function fmt(value, currency = "") {
  const number = decimalText(value, state.locale);
  return number + (currency && number !== "—" ? " " + currency : "");
}
function fmtPrice(value, currency = "") {
  const number = decimalText(value, state.locale, 5, true);
  return number + (currency && number !== "—" ? " " + currency : "");
}

function when(ms) { return ms ? new Intl.DateTimeFormat(state.locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(ms)) : "—"; }
function epochDay(date) { return Math.floor(new Date(`${date}T00:00:00Z`).getTime() / 86400000); }
function gainClass(value) { return Number(value) > 0 ? "gain" : Number(value) < 0 ? "loss" : ""; }
function accountName(id) { return state.accounts.find(v => v.id === id)?.name || `#${id}`; }
function uuid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}

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
  const viewEpoch = state.viewEpoch;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  let response, payload;
  try {
    response = await fetch(path, { credentials: "same-origin", ...options, headers, signal: controller.signal });
    payload = await response.json();
  } finally { clearTimeout(timer); }
  if ((!options.method || options.method === "GET") && !path.includes("/operations/") && viewEpoch !== state.viewEpoch)
    throw new DOMException("Stale view", "AbortError");
  if (!response.ok) {
    const error = new Error(payload?.error?.code || `HTTP_${response.status}`);
    error.code = payload?.error?.code;
    error.refreshRequired = payload?.error?.refreshRequired;
    const expectedUnauthenticated = path.startsWith("/api/v1/pair/") || path === "/api/v1/session/resume";
    if ((response.status === 401 || response.status === 410) && !expectedUnauthenticated) hooks.expire?.();
    throw error;
  }
  if (payload?.dataGeneration !== undefined) state.generation = Math.max(state.generation ?? 0, payload.dataGeneration);
  return payload;
}


Object.assign(copy["zh-CN"], {
  idleWarning: "即将因无操作断开", continueSession: "继续管理", idleEnded: "五分钟内无操作，会话已结束。请在手机上重新开启。",
  phoneEnded: "手机已结束管理会话。", phoneBackground: "手机已进入后台或锁屏，管理会话已结束。", sessionHint: "手机保持亮屏 · 5 分钟无操作后断开",
  discard: "放弃尚未保存的修改？", unknownResult: "正在确认保存结果，请勿重复新建。点击保存可继续确认。", saving: "正在保存…",
  changed: "数据已更新。当前编辑内容已保留；关闭后刷新。", refreshFailed: "已保存，但页面刷新失败。请点击刷新。",
  system: "跟随系统", light: "浅色", dark: "深色", icon: "账户图标", upload: "上传图片", imageHint: "图片将居中裁切为圆形头像，拖动预览调整位置。",
  imageError: "请选择不超过 32 MB 的有效图片。", incomplete: "部分币种缺少汇率，汇总可能不完整", connectionRequired: "连接恢复后才能保存。",
  STALE_GENERATION: "账本已变化。请关闭编辑并刷新，核对数据后重新保存。", preview: "头像预览", zoom: "缩放", updateAvailable: "数据已更新", confirmResult: "确认保存结果"
});
Object.assign(copy.en, {
  idleWarning: "Disconnecting due to inactivity", continueSession: "Continue", idleEnded: "Session ended after five minutes without activity. Start again on your phone.",
  phoneEnded: "The phone ended this session.", phoneBackground: "The phone was locked or backgrounded. This session has ended.", sessionHint: "Phone stays awake · Disconnects after 5 minutes of inactivity",
  discard: "Discard unsaved changes?", unknownResult: "Confirming the save result. Do not create another entry. Choose Save to check again.", saving: "Saving…",
  changed: "Data updated. Your draft is preserved; close it to refresh.", refreshFailed: "Saved, but refresh failed. Choose Refresh.",
  system: "System", light: "Light", dark: "Dark", icon: "Account icon", upload: "Upload image", imageHint: "Drag the preview to adjust the circular crop.",
  imageError: "Choose a valid image up to 32 MB.", incomplete: "Some exchange rates are missing; totals may be incomplete", connectionRequired: "Wait for the connection to recover before saving.",
  STALE_GENERATION: "The ledger changed. Close the editor and refresh, then review before saving.", preview: "Avatar preview", zoom: "Zoom", updateAvailable: "Data updated", confirmResult: "Check save result"
});

const domainMessages = {
  CURRENCY: ["请输入有效币种代码，例如 CNY 或 USD。", "Enter a valid currency code, such as CNY or USD."],
  PRECISION: ["小数位数过多，请按币种或数量精度调整。", "Too many decimal places. Check currency or quantity precision."],
  OVERFLOW: ["数值超出支持范围。", "This value exceeds the supported range."],
  POSITIVE: ["请输入大于零的数值。", "Enter a value greater than zero."],
  DATE: ["请检查日期和时间范围。", "Check the date and time range."],
  INSUFFICIENT_CASH: ["所选现金账户余额不足。", "The selected cash account has insufficient funds."],
  NOT_MATURED: ["存单尚未到期，暂时不能结算。", "This deposit has not matured yet."],
  ALREADY_CLOSED: ["该存单已结算，请刷新查看。", "This deposit is already settled. Refresh to view it."],
  NAME: ["请填写有效名称。", "Enter a valid name."],
  AMOUNT_TOO_SMALL: ["金额低于该币种的最小单位。", "The amount is below the currency's smallest unit."],
  DUPLICATE_TYPE: ["该资产类型已存在。", "This asset type already exists."],
  DUPLICATE_CURRENCY: ["该币种账户已存在。", "An account for this currency already exists."],
  WRONG_CASH_ACCOUNT: ["请选择所属主账户下币种一致的储蓄账户。", "Choose a savings account with the same parent and currency."],
  INVALID_CREDIT_LIMIT: ["请输入有效的信用额度。", "Enter a valid credit limit."],
  INVALID_STATEMENT_DAY: ["请检查账单日。", "Check the statement day."],
  INVALID_DUE_RULE: ["请检查还款日规则和日期。", "Check the payment due rule and date."],
  CREDIT_SOURCE_INVALID: ["所选额度来源不可用，请重新选择。", "The credit source is unavailable. Choose another."],
  CREDIT_SOURCE_CURRENCY: ["额度来源必须使用同一币种。", "The credit source must use the same currency."],
  CREDIT_SOURCE_PARENT: ["额度来源必须属于同一主账户。", "The credit source must belong to the same parent account."],
  CREDIT_SOURCE_CHAIN: ["不能引用已经共享额度的账户。", "Choose an account with its own credit limit."],
  CREDIT_SOURCE_CYCLE: ["额度共享关系不能相互引用。", "Credit sources cannot reference each other."],
  HISTORY_CONFLICT: ["此修改与后续记录冲突，请核对交易时间和数量。", "This change conflicts with later records. Check the time and quantity."],
  OPERATION_CONFLICT: ["提交内容与原操作不一致，请刷新核对。", "The submission differs from the original operation. Refresh and review."],
  NOT_FOUND: ["该记录已不存在，请刷新页面。", "This record is no longer available. Refresh the page."]
};
Object.entries(domainMessages).forEach(([key, [zh, en]]) => { copy["zh-CN"][key] = zh; copy.en[key] = en; });

Object.assign(copy["zh-CN"], {
  updatePrices: "更新价格", priceSearch: "搜索名称或代码", priceChanges: "已修改 {n} 项",
  saveAll: "保存全部", priceInvalid: "请输入非负价格，最多 5 位小数，且不超过支持范围。",
  priceConflict: "此标的已更新或不存在，请刷新后核对价格。", cleared: "未持有／已清仓",
  nameCode: "名称／代码", marketQuantity: "市值／数量", priceCost: "现价／成本"
});
Object.assign(copy.en, {
  updatePrices: "Update prices", priceSearch: "Search name or symbol", priceChanges: "{n} changed",
  saveAll: "Save all", priceInvalid: "Enter a nonnegative price with up to 5 decimal places within the supported range.",
  priceConflict: "This instrument changed or no longer exists. Refresh and review the price.", cleared: "No holdings / Closed positions",
  nameCode: "Name / Symbol", marketQuantity: "Market value / Quantity", priceCost: "Price / Cost"
});
export { el, clear, fmt, fmtPrice, when, epochDay, gainClass, accountName, uuid, restoreSessionCredentials, rememberSessionCredentials, clearSessionCredentials, api, $, state, hooks, t };
