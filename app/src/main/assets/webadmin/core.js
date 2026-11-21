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
  accounts: [], instruments: [], assetTypes: [], positions: [], investmentTab: "instruments", instrumentTypeFilter: "",
  expandedInvestmentAccounts: new Set(), accountGroups: [], collapsedAccountGroups: new Map(),
  recordsCursor: null, recordsHistory: [], locale: navigator.language.toLowerCase().startsWith("zh") ? "zh-CN" : "en", generation: null,
  // The pages: filters and open sections kept while the session lasts.
  accountQuery: "", accountTab: "subs", collapsedGroups: new Map(), invCollapsed: new Set(), closedOpen: new Set(), insType: "",
  rec: { type: "", account: "", period: "", from: "", to: "", q: "" }, recordItems: [], range: "6m", motion: null, lastSync: 0,
  bills: { month: "", type: "", cat: "", sub: "", counted: "", q: "", scope: "month", shown: 80, day: "" }, billCategories: [], statsMonth: "", statsOpen: new Set()
};
const copy = {
  "zh-CN": {
    deletionCode: "确认码", deleteParentScope: "删除此主账户及全部子账户、现金流水、存单、持仓和交易。保留全局标的及其他账户。历史统计会重算，无法撤销。",
    deleteChildScope: "删除此子账户及现金流水。保留存单与投资交易，解除其现金关联；不转移余额，其他账户余额不变。历史统计会重算，无法撤销。",
    deleteLimitNotice: "关联账户分别继承总额度，汇总额度可能增加；各自欠款不变。",
    accounts: "账户", records: "记录", investments: "投资", statistics: "统计", connected: "已连接", demoMode: "演示模式",
    sessionEnded: "会话已结束", sessionLost: "连接已失效，请回到手机重新配对。", TIMEOUT: "手机长时间没有响应，请检查手机网络后重试。", reconnecting: "连接暂时中断，正在恢复…", loading: "正在加载…",
    empty: "暂无数据", save: "保存", cancel: "取消", saved: "✓ 已保存", showDeposits: "显示定期汇总", showInvestments: "显示投资汇总", showOnAccounts: "在账户页显示", includeAvailable: "计入可用现金", searchInstrument: "搜索标的",
    newAccount: "新增账户",
    newInstrument: "新增标的", instruments: "标的", positions: "持仓", refresh: "刷新",
    details: "详情", edit: "编辑", trade: "交易", end: "结束会话", loadMore: "加载更多",
    pairTitle: "连接到手机", pairCopy: "请在手机上打开网页端管理，并输入手机显示的 6 位配对码。", pairCode: "配对码", connect: "连接",
    trustedLan: "仅用于可信局域网。此版本使用 HTTP/WS，不提供传输加密。", mainNav: "主导航", theme: "切换主题", close: "关闭",
    pairWrong: "配对码不正确，手机上已生成新码。", pairInvalid: "配对码不正确，请核对手机上显示的 6 位数字。", pairFailed: "连接失败", wsFailed: "WebSocket 连接未建立。请检查浏览器代理、VPN 或安全软件，然后在手机上重新开启网页端管理。", qrConnecting: "正在使用二维码凭据连接…", loadFailed: "加载失败",
    account: "账户", cash: "可用现金", creditBalance: "信用账户余额", balanceAccounts: "账户", deposits: "定期", investmentValue: "投资市值", totalAssets: "净资产", complete: "完整",
    settled: "已结算", depositActive: "存续中", name: "名称", note: "备注", currency: "币种", balance: "余额", principal: "本金",
    start: "开始", endDate: "到期日期", annualRate: "年利率", status: "状态", quantity: "数量", currentPrice: "现价", marketValue: "市值", pnl: "盈亏",
    accountDetails: "账户详情", openDeposit: "开立存单", editAccount: "编辑账户",
 fromDate: "开始日期", toDate: "结束日期", all: "全部",
    type: "类型", deposit: "存单", search: "搜索", filter: "筛选", time: "时间", subaccount: "子账户", object: "对象",
    amountQuantity: "金额 / 数量", updated: "更新", amount: "金额", editCashRecord: "编辑现金记录", assetTypes: "资产类型",
    nameCode: "名称 / 代码", assetType: "资产类型", priceUpdated: "价格更新", newPosition: "新增持仓", instrument: "标的",
    averageCost: "持仓均价", holdingCost: "持仓成本", realized: "已实现盈亏", unrealized: "浮动盈亏", assetTypeName: "资产类型名称",
    newAssetType: "新增资产类型", symbol: "代码", price: "当前价格", editInstrument: "编辑标的", direction: "交易方向",
    buy: "买入", sell: "卖出", executionPrice: "成交单价", fee: "手续费", cashLink: "现金联动", cashAccount: "子账户",
    setCurrentPrice: "把成交价设为现价", setCurrentPriceOlder: "交易时间早于现价的更新时间（{time}），不能设为现价。",
    none: "不选择", newTrade: "新增交易", delete: "删除", confirmDelete: "确定删除这条交易？", editTrade: "编辑交易",
    rate: "年利率（%）", startDate: "开始日期", settle: "结算", editDeposit: "编辑存单", availableCash: "可用现金",
    monthlyChange: "本月变化", accountDistribution: "主账户分布", assetTypeDistribution: "资产类型分布", currencyDistribution: "币种分布",
    history: "历史资产变化", staleRecord: "记录已变化，请刷新后重试。", staleBalance: "余额已变化，请刷新后重试。",
    currencyLocked: "该币种已被交易使用，不能修改。", symbolLocked: "代码已被交易使用，不能修改。",
    insufficientHolding: "持仓数量不足。", invalidFormat: "输入格式不正确。", saveFailed: "保存失败：{reason}", saveFailedCode: "保存失败（错误码 {code}）",
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
    connected: "Connected", sessionEnded: "Session ended", sessionLost: "Session expired. Pair again from your phone.", TIMEOUT: "The phone did not answer in time. Check its connection and try again.", reconnecting: "Connection interrupted. Reconnecting…",
    loading: "Loading…", empty: "No data", save: "Save", cancel: "Cancel", saved: "✓ Saved",
    showDeposits: "Show deposit summary", showInvestments: "Show investment summary", showOnAccounts: "Show on Accounts page", includeAvailable: "Include in available cash", searchInstrument: "Search instruments",
    newAccount: "New account", newInstrument: "New instrument", instruments: "Instruments", positions: "Positions",
    refresh: "Refresh", details: "Details", edit: "Edit", trade: "Trade", end: "End session", loadMore: "Load more",
    pairTitle: "Connect to phone", pairCopy: "Open Web administration on your phone and enter its 6-digit pairing code.", pairCode: "Pairing code", connect: "Connect",
    trustedLan: "Use only on a trusted local network. This version uses HTTP/WS without transport encryption.", mainNav: "Main navigation", theme: "Toggle theme", close: "Close",
    pairWrong: "The pairing code was incorrect. A new code is now shown on the phone.", pairInvalid: "The pairing code was incorrect. Check the 6 digits on the phone.", pairFailed: "Connection failed", wsFailed: "The WebSocket connection could not be established. Check the browser proxy, VPN, or security software, then restart Web administration on the phone.", qrConnecting: "Connecting with QR credentials…", loadFailed: "Load failed",
    account: "Account", cash: "Available cash", creditBalance: "Credit account balance", balanceAccounts: "Accounts", deposits: "Deposits", investmentValue: "Investment value", totalAssets: "Net assets", complete: "Complete",
    settled: "Settled", depositActive: "Active", name: "Name", note: "Note", currency: "Currency", balance: "Balance", principal: "Principal",
    start: "Start", endDate: "Maturity date", annualRate: "Annual rate", status: "Status", quantity: "Quantity", currentPrice: "Current price", marketValue: "Market value", pnl: "P/L",
    accountDetails: "Account details", openDeposit: "Open deposit", editAccount: "Edit account",
 fromDate: "From", toDate: "To", all: "All",
    type: "Type", deposit: "Deposit", search: "Search", filter: "Filter", time: "Time", subaccount: "Subaccount", object: "Object",
    amountQuantity: "Amount / quantity", updated: "Updated", amount: "Amount", editCashRecord: "Edit cash record", assetTypes: "Asset classes",
    nameCode: "Name / symbol", assetType: "Asset class", priceUpdated: "Price updated", newPosition: "New position", instrument: "Instrument",
    averageCost: "Average cost", holdingCost: "Holding cost", realized: "Realized P/L", unrealized: "Unrealized P/L", assetTypeName: "Asset class name",
    newAssetType: "New asset class", symbol: "Symbol", price: "Current price", editInstrument: "Edit instrument", direction: "Trade direction",
    buy: "Buy", sell: "Sell", executionPrice: "Execution price", fee: "Fee", cashLink: "Link cash", cashAccount: "Subaccount",
    setCurrentPrice: "Set the trade price as the current price", setCurrentPriceOlder: "The trade is older than the current price, updated {time}, so it can't be set as the current price.",
    none: "None", newTrade: "New trade", delete: "Delete", confirmDelete: "Delete this trade?", editTrade: "Edit trade",
    rate: "Annual rate (%)", startDate: "Start date", settle: "Settle", editDeposit: "Edit deposit", availableCash: "Available cash",
    monthlyChange: "Change this month", accountDistribution: "Account distribution", assetTypeDistribution: "Asset class distribution", currencyDistribution: "Currency distribution",
    history: "Asset history", staleRecord: "This record changed. Refresh and try again.", staleBalance: "This balance changed. Refresh and try again.",
    currencyLocked: "The currency is locked after use in a trade.", symbolLocked: "The symbol is locked after use in a trade.",
    insufficientHolding: "Insufficient holding quantity.", invalidFormat: "Check the input format.", saveFailed: "Save failed: {reason}", saveFailedCode: "Save failed (error code {code})",
    cashChange: "Balance adjustment", depositOpen: "Deposit opened", depositClose: "Deposit settled", operation: "Action",
    savingsAccount: "Savings account", creditAccount: "Credit account", creditLimit: "Credit limit", usedLimit: "Used", availableLimit: "Available",
    limitSource: "Limit source", independentLimit: "Independent limit", statementDay: "Statement day", dueRule: "Payment due rule", dueValue: "Rule day/days",
    dueAfter: "Days after statement", dueFixed: "Fixed day each month"
  }
};
Object.assign(copy["zh-CN"], { basicInformation: "基本信息", addSubaccount: "新增子账户", newCashName: "子账户名称", newCashCurrency: "币种", newCashBalance: "初始余额" });
Object.assign(copy.en, { basicInformation: "Basic information", addSubaccount: "Add subaccount", newCashName: "Subaccount name", newCashCurrency: "Currency", newCashBalance: "Opening balance" });
const t = key => (copy[state.locale] || copy["zh-CN"])[key] || key;

function el(tag, attrs = {}, ...children) {
  const node = ["svg", "g", "path", "line", "polyline", "circle"].includes(tag)
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
// Prices default to the stored E5 precision; execution prices pass 8 digits.
function fmtPrice(value, currency = "", digits = 5) {
  const number = decimalText(value, state.locale, digits, true);
  return number + (currency && number !== "—" ? " " + currency : "");
}
// Quantities are stored at E8 precision and are never rounded for display.
function fmtQuantity(value) { return decimalText(value, state.locale, 8, true); }

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
  // A timeout is not a view left behind: it gets its own error, which is shown.
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 15000);
  let response, payload;
  try {
    response = await fetch(path, { credentials: "same-origin", ...options, headers, signal: controller.signal });
    payload = await response.json();
  } catch (error) {
    if (!timedOut) throw error;
    const timeout = new Error("TIMEOUT"); timeout.name = "TimeoutError"; timeout.code = "TIMEOUT";
    throw timeout;
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
  idleWarning: "即将因无操作断开", continueSession: "继续管理", idleEnded: "5 分钟内无操作，会话已结束。请在手机上重新开启。",
  phoneEnded: "手机已结束管理会话。", phoneBackground: "手机已进入后台或锁屏，管理会话已结束。", sessionHint: "手机保持亮屏 · 5 分钟无操作后断开",
  discard: "放弃尚未保存的修改？", unknownResult: "正在确认保存结果，请勿重复新建。点击保存可继续确认。", saving: "正在保存…",
  changed: "数据已更新。当前编辑内容已保留；关闭后刷新。", refreshFailed: "已保存，但页面刷新失败。请点击刷新。",
  system: "跟随系统", light: "浅色", dark: "深色", icon: "账户图标", upload: "上传图片", imageHint: "图片将居中裁切为圆形图标，拖动预览调整位置。",
  imageError: "请选择不超过 32 MB 的有效图片。", incomplete: "部分币种缺少汇率，汇总可能不完整", connectionRequired: "连接恢复后才能保存。",
  STALE_GENERATION: "账本已变化。请关闭编辑并刷新，核对数据后重新保存。", preview: "图标预览", zoom: "缩放", updateAvailable: "数据已更新", confirmResult: "确认保存结果"
});
Object.assign(copy.en, {
  idleWarning: "Disconnecting due to inactivity", continueSession: "Continue", idleEnded: "Session ended after 5 minutes without activity. Start again on your phone.",
  phoneEnded: "The phone ended this session.", phoneBackground: "The phone was locked or backgrounded. This session has ended.", sessionHint: "Phone stays awake · Disconnects after 5 minutes of inactivity",
  discard: "Discard unsaved changes?", unknownResult: "Confirming the save result. Do not create another entry. Choose Save to check again.", saving: "Saving…",
  changed: "Data updated. Your draft is preserved; close it to refresh.", refreshFailed: "Saved, but refresh failed. Choose Refresh.",
  system: "System", light: "Light", dark: "Dark", icon: "Account icon", upload: "Upload image", imageHint: "Drag the preview to adjust the circular crop.",
  imageError: "Choose a valid image up to 32 MB.", incomplete: "Some exchange rates are missing; totals may be incomplete", connectionRequired: "Wait for the connection to recover before saving.",
  STALE_GENERATION: "The ledger changed. Close the editor and refresh, then review before saving.", preview: "Icon preview", zoom: "Zoom", updateAvailable: "Data updated", confirmResult: "Check save result"
});

const domainMessages = {
  CURRENCY: ["请输入有效币种代码，例如 CNY 或 USD。", "Enter a valid currency code, such as CNY or USD."],
  PRECISION: ["小数位过多：金额按币种精度，现价最多 5 位，成交价、份额与利率最多 8 位。", "Too many decimal places. Amounts follow currency precision; current prices allow up to 5, and trade prices, quantities, and rates up to 8."],
  OVERFLOW: ["数值超出支持范围。", "This value exceeds the supported range."],
  POSITIVE: ["请输入大于零的数值。", "Enter a value greater than zero."],
  DATE: ["请检查日期和时间范围。", "Check the date and time range."],
  INSUFFICIENT_CASH: ["所选子账户余额不足。", "The selected subaccount has insufficient funds."],
  NOT_MATURED: ["存单尚未到期，暂时不能结算。", "This deposit has not matured yet."],
  ALREADY_CLOSED: ["该存单已结算，请刷新查看。", "This deposit is already settled. Refresh to view it."],
  NAME: ["请填写有效名称。", "Enter a valid name."],
  AMOUNT_TOO_SMALL: ["金额低于该币种的最小单位。", "The amount is below the currency's smallest unit."],
  DUPLICATE_TYPE: ["该资产类型已存在。", "This asset class already exists."],
  DEFAULT_TYPE: ["「未分类」是默认资产类型，不能改名或删除。", "Uncategorized is the default class and cannot be renamed or deleted."],
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
  NOT_FOUND: ["该记录已不存在，请刷新页面。", "This record is no longer available. Refresh the page."],
  SOURCE_RECORD: ["请修改来源记录，联动现金会同步更新。", "Edit the source record; linked cash will update with it."],
  PRICE_CONFIRMATION: ["更换币种后，请核对当前价格并勾选确认项。", "After changing currency, check the current price and tick the confirmation box."],
  INVALID_ACCOUNT_TYPE: ["账户类型创建后不能直接修改。", "The account type cannot be changed after creation."],
  CREDIT_LIMIT_IN_USE: ["有其他信用账户正在共享该额度，请先解除关联。", "Other credit accounts share this limit. Unlink them first."],
  BALANCE_ACCOUNT_IN_USE: ["该子账户存在余额、历史记录或业务关联，无法删除。", "This subaccount has a balance, history, or business link and cannot be deleted."],
  WALLET_FACE_IN_USE: ["这张卡面正在使用，请先为使用它的卡片更换卡面。", "This card face is in use. Change the face of the cards using it first."],
  WALLET_FACE_DUPLICATE: ["这张卡面已在卡面库中。", "This card face is already in the library."],
  WALLET_FACE_LIBRARY_FULL: ["卡面库已满，请先删除不用的卡面。", "The card face library is full. Delete faces you no longer need first."],
  SESSION_EXPIRED: ["当前数据会话已结束，请刷新页面。", "This data session has ended. Refresh the page."],
  SESSION_BUSY: ["已有其他浏览器会话正在使用。", "Another browser session is active."],
  WEB_ADMIN_ACTIVE: ["网页端管理正在进行，请稍后再试。", "Web administration is in progress. Try again later."],
  MAINTENANCE_ACTIVE: ["手机正在恢复、清空数据或处理文件，请稍后再试。", "The phone is restoring, clearing data, or handling a file. Try again later."]
};
Object.entries(domainMessages).forEach(([key, [zh, en]]) => { copy["zh-CN"][key] = zh; copy.en[key] = en; });
// The names the app gives its account icons (account_icon_labels), for the icon buttons.
const iconLabels = { "zh-CN": { account_balance: "银行", account_balance_wallet: "账户钱包", credit_card: "信用卡", savings: "储蓄", payments: "付款", wallet: "钱包", trending_up: "增长", show_chart: "图表", monitoring: "投资", business_center: "商务", home: "住宅", apartment: "公寓", storefront: "商店", shopping_bag: "购物", work: "工作", paid: "资金", currency_exchange: "换汇", public: "全球", flight: "旅行", directions_car: "汽车", school: "教育", family_restroom: "家庭", favorite: "收藏", star: "星标" },
  en: { account_balance: "Bank", account_balance_wallet: "Account wallet", credit_card: "Credit card", savings: "Savings", payments: "Payments", wallet: "Wallet", trending_up: "Growth", show_chart: "Chart", monitoring: "Investments", business_center: "Business", home: "Home", apartment: "Apartment", storefront: "Store", shopping_bag: "Shopping", work: "Work", paid: "Money", currency_exchange: "Exchange", public: "Global", flight: "Travel", directions_car: "Car", school: "Education", family_restroom: "Family", favorite: "Favorite", star: "Star" } };
const iconLabel = key => (iconLabels[state.locale] || iconLabels["zh-CN"])[key] || key.replaceAll("_", " ");

Object.assign(copy["zh-CN"], {
  updatePrices: "更新价格", priceSearch: "搜索名称或代码", priceChanges: "已修改 {n} 项",
  saveAll: "保存全部", priceInvalid: "请输入非负价格，最多 5 位小数，且不超过支持范围。",
  priceConflict: "此标的已更新或不存在，请刷新后核对价格。", cleared: "未持有 / 已清仓",
  nameCode: "名称 / 代码", marketQuantity: "市值 / 数量", priceCost: "现价 / 成本"
});
Object.assign(copy.en, {
  updatePrices: "Update prices", priceSearch: "Search name or symbol", priceChanges: "{n} changed",
  saveAll: "Save all", priceInvalid: "Enter a nonnegative price with up to 5 decimal places within the supported range.",
  priceConflict: "This instrument changed or no longer exists. Refresh and review the price.", cleared: "No holdings / Closed positions",
  nameCode: "Name / Symbol", marketQuantity: "Market value / Quantity", priceCost: "Price / Cost"
});
Object.assign(copy["zh-CN"], {
  accountGroups: "分类管理", accountGroupsHint: "所有改动都会立即保存。分类的展开和折叠只在本浏览器生效。",
  defaultGroup: "默认", newAccountGroup: "新增分类", renameAccountGroup: "重命名分类", deleteAccountGroup: "删除分类",
  groupName: "分类名称", groupNameInvalid: "分类名称需为 1–20 个字符。", rename: "重命名",
  deleteGroupMessage: "删除「{name}」后，其中的 {n} 个账户会移回「默认」。", addAccounts: "添加账户",
  addAccountsTo: "添加账户到「{name}」", addAccountsHint: "选中的账户会从原分类移入此分类。", add: "添加",
  noOtherAccounts: "其他分类中没有账户", groupEmpty: "该分类暂无账户", accountOrigin: "{name}（{group}）",
  moveUp: "上移", moveDown: "下移", expand: "展开", collapse: "折叠"
});
Object.assign(copy.en, {
  accountGroups: "Account groups", accountGroupsHint: "Every change is saved immediately. Expanding or collapsing a group only applies in this browser.",
  defaultGroup: "Default", newAccountGroup: "New group", renameAccountGroup: "Rename group", deleteAccountGroup: "Delete group",
  groupName: "Group name", groupNameInvalid: "Group names must be 1–20 characters.", rename: "Rename",
  deleteGroupMessage: "Deleting “{name}” moves the accounts in it back to Default. Accounts: {n}", addAccounts: "Add accounts",
  addAccountsTo: "Add accounts to “{name}”", addAccountsHint: "Selected accounts move here from their current group.", add: "Add",
  noOtherAccounts: "No accounts in other groups", groupEmpty: "No accounts in this group", accountOrigin: "{name} ({group})",
  moveUp: "Move up", moveDown: "Move down", expand: "Expand", collapse: "Collapse"
});

Object.assign(copy["zh-CN"], { updating: "正在更新…" });
Object.assign(copy.en, { updating: "Updating…" });
Object.assign(copy["zh-CN"], { resizePanel: "调整侧栏宽度", resizePanelHint: "拖动调整宽度；双击恢复默认。也可使用左右键调整，Enter 恢复默认。" });
Object.assign(copy.en, { resizePanel: "Resize panel", resizePanelHint: "Drag to resize; double-click to reset. Use arrow keys to resize or Enter to reset." });
Object.assign(copy["zh-CN"], { editAssetType: "编辑资产类型", instrumentCount: "标的数", createTypeFirst: "请先创建资产类型，再新增标的。" });
Object.assign(copy.en, { editAssetType: "Edit asset class", instrumentCount: "Instruments", createTypeFirst: "Create an asset class before adding an instrument." });
Object.assign(copy["zh-CN"], { uncategorized: "未分类", defaultType: "默认", deletedInstrumentTrade: "已删除标的的交易" });
Object.assign(copy.en, { uncategorized: "Uncategorized", defaultType: "Default", deletedInstrumentTrade: "Deleted instrument's trade" });
Object.assign(copy["zh-CN"], { dividend: "派息", deletedInstrumentDividend: "已删除标的的派息", dividendOnPhone: "派息只能在手机上记录和修改。" });
Object.assign(copy.en, { dividend: "Dividend", deletedInstrumentDividend: "Deleted instrument's dividend", dividendOnPhone: "Dividends can only be recorded and edited on the phone." });
Object.assign(copy["zh-CN"], { bill: "账单", billTransferIn: "转账转入", billOnPhone: "账单只能在手机上记录和修改。", billsKept: "笔账单会保留，账户显示为已删除。", deletedBill: "已删除的账单" });
Object.assign(copy.en, { bill: "Bill", billTransferIn: "Transfer in", billOnPhone: "Bills can only be recorded and edited on the phone.", billsKept: "bills stay, showing this account as deleted.", deletedBill: "Deleted bill" });
// Copy for the redesigned pages (v0.0.19).
const v2 = {
  accountCount: ["{n} 个账户", "{n} accounts"], accountCreated: ["账户已创建", "Account created"], accountName: ["账户名称", "Account name"],
  accountTotalBase: ["账户合计（已折算）", "Account total (converted)"], actions: ["操作", "Actions"], addThisInstrument: ["新增这个标的", "Add this instrument"],
  addTo: ["添加到「{name}」", "Add to “{name}”"], allAccounts: ["全部账户", "All accounts"], allTime: ["全部时间", "All time"], annualRatePct: ["年利率（%）", "Annual rate (%)"],
  asOf: ["截至 {when}", "As of {when}"], assetTypesSub: ["用于在统计页按类型查看投资分布", "Used to group investments on the statistics page"],
  billEntry: ["账单流水", "Bill entry"], byAccount: ["按账户", "By account"], byAssetClass: ["按资产构成", "By asset class"], byCurrency: ["按币种", "By currency"],
  cashAndCredit: ["现金与信用", "Cash and credit"], cashEntries: ["现金流水", "Cash entries"], cashEntriesChip: ["现金", "Cash"], cashEntry: ["现金记录", "Cash entry"],
  cashLinkHint: ["联动后，交易金额计入所选子账户的余额", "Linked, the trade amount moves this subaccount’s balance"], cashLinked: ["联动现金", "Cash linked"],
  chooseAgain: ["重新选择", "Choose again"], chronologyInvalid: ["交易顺序与数量不一致，盈亏可能不准确，请在手机上核对。", "The trades do not add up in order; profit may be off. Check them on the phone."],
  clearFilters: ["清除筛选", "Clear filters"], closed: ["已清仓", "Closed"], closedPositions: ["已清仓", "Closed"], connectAgain: ["重新连接", "Connect again"],
  connectedSameLan: ["已连接 · 同一局域网", "Connected · same network"], connectingPhone: ["正在连接手机…", "Connecting to the phone…"], createAccount: ["创建账户", "Create account"],
  credit: ["信用", "Credit"], savings: ["储蓄", "Savings"], creditBalanceLabel: ["当前余额（欠款为负数）", "Balance (debt is negative)"], creditDebt: ["信用负债", "Credit debt"],
  creditSettings: ["信用设置", "Credit settings"], currencyHint: ["3 位字母代码，例如 CNY、USD", "Three letters, such as CNY or USD"],
  currencyLockedHint: ["已有记录，不能修改币种", "In use; the currency is fixed"], customRange: ["自定义…", "Custom…"], daysAgo: ["{n} 天前", "{n} days ago"],
  defaultSubName: ["活期", "Current"], defaultTypeFixed: ["默认类型，不可修改", "Default, cannot be changed"], deleteAccountMenu: ["删除账户…", "Delete account…"],
  deleteAccountTitle: ["删除账户「{name}」", "Delete account “{name}”"], deleteCashTitle: ["删除这条余额调整？", "Delete this balance adjustment?"],
  deleteCashUnchanged: ["删除后余额不变。", "The balance stays the same."], deleteCashLowers: ["删除后余额减少 {amount}。", "The balance goes down by {amount}."],
  deleteCashRaises: ["删除后余额增加 {amount}。", "The balance goes up by {amount}."], deleteForever: ["永久删除", "Delete permanently"],
  deleteGroupTitle: ["删除分组「{name}」？", "Delete group “{name}”?"], deleteSubaccountTitle: ["删除子账户「{name}」", "Delete subaccount “{name}”"],
  deleteTradeMessage: ["持仓数量、成本和盈亏会重新计算。此操作无法撤销。", "Holdings, cost and profit are recalculated. This cannot be undone."],
  deleted: ["已删除", "Deleted"], deletedName: ["已删除「{name}」", "Deleted “{name}”"], deleting: ["正在删除…", "Deleting…"], depositOpened: ["存单已开立", "Deposit opened"],
  depositRecord: ["定期记录", "Deposit record"], depositSettled: ["存单已结算", "Deposit settled"], discardConfirm: ["放弃修改", "Discard"],
  discardMessage: ["关闭后，这次填写的内容不会保存。", "What you entered will not be saved."], distribution: ["分布", "Distribution"], done: ["完成", "Done"],
  dueAfterDays: ["账单日后 {n} 天还款", "Due {n} days after the statement"], dueDayLabel: ["每月还款日", "Due day of month"], dueDaysLabel: ["账单日后天数", "Days after statement"],
  dueOnDay: ["每月 {n} 日还款", "Due on day {n}"], editPricesHint: ["直接在表格里改价格，回车跳到下一个", "Edit prices in the table; Enter moves to the next"],
  editSubaccount: ["编辑子账户", "Edit subaccount"], endAfterStart: ["到期日期需晚于开始日期", "Maturity must be after the start"],
  enterAmountDigits: ["请输入有效金额，最多 {n} 位小数", "Enter an amount with up to {n} decimal places"], enterCodeAbove: ["输入上方 6 位数字", "Enter the 6 digits above"],
  enterCurrency: ["请输入 3 位字母币种代码", "Enter a three-letter currency code"], enterFee: ["请输入有效的手续费", "Enter a valid fee"],
  enterLimit: ["请输入大于 0 的额度", "Enter a limit above zero"], enterName: ["请输入名称", "Enter a name"], enterPrice: ["请输入大于 0 的单价，最多 8 位小数", "Enter a price above zero, up to 8 decimal places"],
  enterQuantity: ["请输入大于 0 的数量，最多 8 位小数", "Enter a quantity above zero, up to 8 decimal places"], enterRate: ["请输入有效的年利率", "Enter a valid annual rate"],
  enterSignedAmount: ["请输入有效金额，支出为负数", "Enter a valid amount; spending is negative"], enterSymbol: ["请输入代码", "Enter a symbol"], enterTime: ["请选择时间", "Choose a time"],
  filterAccount: ["按账户筛选", "Filter by account"], firstSubaccount: ["第一个子账户", "First subaccount"], grossAmount: ["成交金额", "Trade value"],
  heldIn: ["持有账户", "Held in"], heldQuantity: ["持有数量", "Quantity held"], hiddenOnAccounts: ["账户页隐藏", "Hidden on Accounts"], hideAmounts: ["隐藏金额", "Hide amounts"],
  showAmounts: ["显示金额", "Show amounts"], holding: ["持有", "Holding"], holdingNow: ["当前持有", "Now holding"], hoursAgo: ["{n} 小时前", "{n} h ago"],
  idleCountdown: ["长时间没有操作，{n} 秒后断开连接。", "No activity: disconnecting in {n} s."], inBase: ["已折算为 {c}", "Converted to {c}"],
  includeAvailableHint: ["备用金、冻结资金等可以不计入", "Leave out reserves or frozen money"], includesDividends: ["含派息", "Includes dividends"],
  instrumentAdded: ["标的已添加", "Instrument added"], instrumentCountSub: ["{n} 个标的", "{n} instruments"], instrumentShared: ["标的在所有账户间共享", "Instruments are shared by all accounts"],
  interest: ["利息", "Interest"], itemCount: ["{n} 项", "{n} items"], justNow: ["刚刚", "just now"], last3Months: ["近 3 个月", "Last 3 months"], last90Days: ["近 90 天", "Last 90 days"],
  limitUsed: ["额度已用", "Limit used"], linkedCashNote: ["这笔现金随交易联动，请修改对应交易。", "This cash follows its trade; edit the trade instead."],
  lockedByTrades: ["已有交易使用，不能修改", "Used by trades; cannot change"], maturedUnsettled: ["已到期，待结算", "Matured, not settled"], maturesIn: ["{n} 天后到期", "Matures in {n} days"],
  maturityDate: ["到期日期", "Maturity date"], minutesAgo: ["{n} 分钟前", "{n} min ago"], missingRate: ["缺汇率", "No rate"], missingRates: ["部分币种缺少汇率", "Some rates are missing"],
  moreActions: ["更多操作", "More actions"], negativeLeftOut: ["负值账户不计入", "Accounts below zero are left out"], newAccountSub: ["余额记在子账户里", "Balances are kept in subaccounts"],
  newBalanceOf: ["「{name}」余额将为", "“{name}” will have"], newPrice: ["新价格", "New price"], newPriceOf: ["{name} 新价格（{currency}）", "New price of {name} ({currency})"],
  newTradeFor: ["为这个标的新增交易", "New trade in this instrument"], noAccountMatch: ["没有找到与“{q}”相关的账户", "No account matches “{q}”"], noAccounts: ["还没有账户", "No accounts yet"],
  noCashLink: ["不联动现金", "No cash link"], noCashOfCurrency: ["这个账户没有 {c} 储蓄子账户，交易不改变现金余额", "This account has no {c} savings subaccount; cash stays as it is"],
  noDebt: ["没有欠款", "No debt"], owed: ["待还款", "To repay"], noDeposits: ["还没有存单", "No deposits yet"], noHolders: ["还没有账户持有这个标的", "No account holds this instrument"],
  noInstrumentMatch: ["没有找到“{q}”", "Nothing matches “{q}”"], noInstruments: ["还没有标的", "No instruments yet"], noInstrumentsOfType: ["没有这个类型的标的", "No instruments of this class"],
  noPositions: ["还没有持仓，点右上角“新增交易”开始记录", "No positions yet. Choose New trade to start."], noPositionsHere: ["这个账户没有持仓", "No positions in this account"],
  noRecordMatch: ["没有符合筛选条件的记录", "No records match the filters"], noRecords: ["还没有记录", "No records yet"], notAvailableCash: ["不计入可用现金", "Not in available cash"],
  notEnoughHistory: ["这段时间的记录还不够画出走势", "Not enough history for this range yet"], nothingFound: ["没有找到“{q}”", "Nothing found for “{q}”"],
  nothingHeld: ["当前没有持有中的标的", "Nothing held now"], ok: ["确定", "OK"], open: ["打开", "Open"], openFrom: ["本金来源", "Principal from"],
  openFromHint: ["开立时从所选子账户扣除本金", "The principal leaves this subaccount when opened"], openSearchAnytime: ["随时打开搜索", "Search anytime"],
  openingBalance: ["初始余额", "Opening balance"], pageCleared: ["本页数据已清除", "This page’s data has been cleared"], pages: ["页面", "Pages"],
  pairDigit: ["配对码第 {n} 位", "Pairing code digit {n}"], pairLead: ["输入手机上显示的 6 位配对码", "Enter the 6-digit code shown on the phone"],
  pairStep1: ["手机打开 设置 › 网页端管理", "On the phone, open Settings › Web admin"], pairStep2: ["扫码或输入配对码", "Scan the code or enter it"],
  pairStep3: ["保持亮屏，连接同一 Wi-Fi", "Keep the screen on, on the same Wi-Fi"], paletteHint: ["搜索账户、标的、页面或操作…", "Search accounts, instruments, pages or actions…"],
  phoneOnly: ["仅手机可改", "Phone only"], positionCount: ["{n} 个持仓", "{n} positions"], priceChecked: ["已核对现价", "Price checked"],
  priceCheckedHint: ["更换币种后，请确认现价对应新币种", "After changing the currency, check the price is in it"], pricesSaved: ["已更新 {n} 个价格", "{n} prices updated"],
  pricesUpdated: ["价格更新：{when}", "Prices: {when}"], qrFailedUseCode: ["二维码已失效，请输入手机上的配对码。", "The QR code has expired. Enter the code on the phone."],
  quickSearch: ["快速搜索（Ctrl K）", "Quick search (Ctrl K)"], range1m: ["1 月", "1M"], range3m: ["3 月", "3M"], range6m: ["6 月", "6M"], range1y: ["1 年", "1Y"], rangeAll: ["全部", "All"],
  rangeChange: ["区间变化", "Change over range"], rangeOf: ["请输入 1–{max}", "Enter 1–{max}"], realizedShort: ["已实现", "Realized"], unrealizedShort: ["浮动", "Unrealized"],
  reconnectingNotice: ["连接暂时中断，正在恢复。此期间不能保存，已填写的内容会保留。", "Connection lost; reconnecting. Saving waits until it is back; what you entered stays."],
  reconnectingShort: ["重新连接中", "Reconnecting"], recordTrade: ["记录交易", "Record trade"], recordsSub: ["现金、定期和交易的全部流水", "Every cash, deposit and trade entry"],
  savePrices: ["保存价格", "Save prices"], searchAccounts: ["搜索账户或备注", "Search accounts or notes"], searchInstrumentHint: ["名称、代码或币种", "Name, symbol or currency"],
  searchRecords: ["搜索备注、标的、账户", "Search notes, instruments, accounts"], select: ["选择", "Select"],
  settleIntoMessage: ["本金和利息将转入「{name}」。结算后存单不能再修改。", "Principal and interest go to “{name}”. A settled deposit cannot be changed."],
  settleOnlyMessage: ["只记录结算，不改变余额。结算后存单不能再修改。", "Only the settlement is recorded; no balance changes. A settled deposit cannot be changed."],
  settleTitle: ["结算这笔存单？", "Settle this deposit?"], settleTo: ["结算转入", "Settle into"], settleToHint: ["结算时本金和利息转入所选子账户", "Principal and interest go here when settled"],
  shareLimitOf: ["共享「{name}」的额度", "Share the limit of “{name}”"], shareOf: ["占", "Share"], sharedWith: ["与 {n} 张卡共享", "Shared with {n} cards"],
  sharesLimitOf: ["共享「{name}」额度", "Shares the limit of “{name}”"], showOnAccountsHint: ["关闭后余额仍计入合计", "Still counted in totals when hidden"],
  signedAmountHint: ["收入为正数，支出为负数", "Positive for income, negative for spending"], sinceStart: ["较区间起点", "Since start"],
  statementOn: ["每月 {n} 日出账", "Statement on day {n}"],
  statementDayIncluded: ["账单日消费计入当日账单", "Statement-day spending on that day\u2019s statement"],
  statementDayIncludedHint: ["关闭后计入下一期账单。各银行规则不同，以账单为准。", "When off, it goes on the next statement. Banks differ; check your statement."], statementRange: ["账单日为 1–31", "The statement day is 1–31"], subaccountAdded: ["子账户已添加", "Subaccount added"],
  subaccountCount: ["{n} 个子账户", "{n} subaccounts"], subaccountName: ["子账户名称", "Subaccount name"], subaccounts: ["子账户", "Subaccounts"], syncedAt: ["{when}同步", "synced {when}"],
  syncsToPhone: ["此操作会立即同步到手机", "This applies on the phone at once"], thisMonth: ["本月", "This month"], thisYear: ["今年", "This year"], timeRange: ["时间范围", "Time range"],
  toDark: ["切换到深色外观", "Switch to dark"], toLight: ["切换到浅色外观", "Switch to light"], today: ["今天", "Today"], yesterday: ["昨天", "Yesterday"], total: ["合计", "Total"],
  totalPaid: ["共支出", "Total paid"], totalReceived: ["共收入", "Total received"], tradeDeleted: ["交易已删除", "Trade deleted"], tradeRecord: ["交易记录", "Trade"],
  tradeRecorded: ["交易已记录", "Trade recorded"], tradeRecords: ["交易记录", "Trades"], tradeStep1: ["第 1 步：选择账户和标的", "Step 1: choose an account and instrument"],
  tradeTime: ["成交时间", "Trade time"], trades: ["交易", "Trades"], trendAria: ["净资产走势图，用左右方向键查看每个点的数值", "Net worth trend; use the arrow keys to read each point"],
  typeCodeToConfirm: ["为避免误删，请输入确认码：", "To confirm, enter this code:"], updatedWhen: ["更新于 {when}", "Updated {when}"], used: ["已用", "Used"],
  viewAccount: ["查看「{name}」", "Open “{name}”"], viewDeposit: ["查看存单", "Open deposit"], viewPosition: ["查看持仓", "Open position"],
  browserEnded: ["已在电脑上结束。如需继续，请在手机上重新开启网页端管理。", "Ended from this computer. To continue, start Web admin on the phone again."],
  saveFailedShort: ["保存失败", "Save failed"], unknownResult: ["正在确认保存结果，请勿重复新建。点保存可继续确认。", "Checking whether it was saved. Do not create it again; choose Save to check again."],
};
Object.entries(v2).forEach(([key, [zh, en]]) => { copy["zh-CN"][key] = zh; copy.en[key] = en; });
// Copy for the bills pages and income/expense statistics (v0.0.19). Bill words follow the app's bill screens.
const v3 = {
  bills: ["账单", "Bills"], expenseStats: ["支出统计", "Expense statistics"], incomeStats: ["收入统计", "Income statistics"],
  previousMonth: ["上个月", "Previous month"], nextMonth: ["下个月", "Next month"], chooseMonth: ["{month}，选择月份", "{month}, choose a month"], month: ["月份", "Month"],
  sameAsLastMonth: ["和上月持平", "Same as last month"], moreThanLastMonth: ["比上月多 {amount}", "{amount} more than last month"], lessThanLastMonth: ["比上月少 {amount}", "{amount} less than last month"],
  expense: ["支出", "Expense"], income: ["收入", "Income"], transfer: ["转账", "Transfer"], refund: ["退款", "Refund"], reimbursement: ["报销回款", "Reimbursement"],
  notCounted: ["不计入", "Not counted"], refundedShort: ["已退", "Refunded"], reimbursedShort: ["已报", "Paid back"], pendingShort: ["待回款", "To be paid back"],
  waived: ["已放弃回款", "Given up"], notYetPosted: ["未入账", "Not yet posted"], deletedAccount: ["已删除的账户", "Deleted account"], noAccount: ["不记账户", "No account"],
  externalAccount: ["外部账户", "External account"], billCount: ["{n} 笔", "{n} bills"], viewOnly: ["仅可查看", "View only"], balanceShort: ["结余", "Balance"],
  savedByDiscounts: ["优惠共省", "Saved by discounts"], pendingReimbursement: ["待回款", "To be paid back"], pendingCount: ["{n} 笔 · 全部月份", "{n} bills · all months"],
  nonePending: ["没有待回款", "Nothing to be paid back"], ratesMissingLeftOut: ["没有汇率的币种未计入合计。", "Currencies without a rate are left out of the totals."],
  allCategories: ["全部分类", "All categories"], filterCategory: ["按分类筛选", "Filter by category"], countedAll: ["计入收支：全部", "Counted: all"],
  countedYes: ["计入收支", "Counted"], countedNo: ["不计入收支", "Not counted"], filterCounted: ["按是否计入收支筛选", "Filter by counted"],
  thisMonthShort: ["当月", "This month"], allMonths: ["全部月份", "All months"], searchScope: ["搜索范围", "Search in"], searchNotes: ["搜索备注", "Search notes"],
  noNoteMatch: ["没有找到备注相符的账单", "No bill notes match"], noBillMatch: ["没有符合条件的账单", "No bills match the filters"], noBillsThisMonth: ["这个月还没有账单", "No bills this month"],
  foundCount: ["找到 {n} 笔", "{n} found"], foundAllMonths: ["在全部月份找到 {n} 笔", "{n} found in all months"], loadMoreLeft: ["加载更多（还有 {n} 笔）", "Load more ({n} left)"],
  imageCount: ["{n} 张图片", "{n} images"], discountShort: ["优惠", "Discount"], transferOut: ["转出", "From"], transferIn: ["转入", "To"], notFilled: ["未填写", "Not given"],
  amountOut: ["转出金额", "Amount sent"], amountIn: ["转入金额", "Amount received"], receivedIn: ["到账账户", "Received in"], category: ["分类", "Category"],
  originalAmount: ["原金额", "Original amount"], discount: ["优惠", "Discount"], paid: ["实付", "Paid"], rateAuto: ["自动汇率", "Automatic rate"], rateManual: ["手动汇率", "Manual rate"],
  rateOneTime: ["仅本次使用", "This time only"], rateDerived: ["按实际金额反推", "From the amount moved"], accountAmount: ["账户实际金额", "Amount in the account"],
  countedInStats: ["计入收支", "Counted"], countedWaived: ["计入（已放弃回款）", "Counted (given up)"], countedYesShort: ["计入", "Counted"], countedNoShort: ["不计入", "Not counted"],
  reimbursementLabel: ["报销", "Reimbursement"], leftToReceive: ["还剩", "Left"], payer: ["回款方", "Paid back by"], images: ["图片", "Images"],
  imagesOnPhone: ["{n} 张 · 在手机上查看", "{n} · view them on the phone"], refundsAndReimbursements: ["退款和报销回款", "Refunds and reimbursements"],
  netAmount: ["净额", "Net"], allReturned: ["已全部退回", "All returned"], originalBill: ["原账单", "Original bill"], viewCategoryStats: ["查看分类统计", "Category statistics"],
  viewBill: ["查看账单", "Open bill"], betweenAccounts: ["账户之间", "Between accounts"], netWorthTab: ["净资产", "Net worth"],
  expenseTotal: ["支出合计", "Total expense"], incomeTotal: ["收入合计", "Total income"], dailyAverage: ["日均", "Daily average"],
  dailyExpense: ["每日支出", "Daily expense"], dailyIncome: ["每日收入", "Daily income"],
  dailyExpenseAria: ["每日支出，用左右方向键查看每天的数值，回车查看当天账单", "Daily expense; arrow keys read each day, Enter opens its bills"],
  dailyIncomeAria: ["每日收入，用左右方向键查看每天的数值，回车查看当天账单", "Daily income; arrow keys read each day, Enter opens its bills"],
  notYet: ["还没到", "Not yet"], dayBillsOpen: ["{n} 笔 · 点击查看", "{n} bills · click to open"], noBillsDay: ["没有账单", "No bills"],
  notSplit: ["未细分", "Not split"], categories: ["分类", "Categories"], categoryCountHint: ["{n} 类 · 点开查看子分类", "{n} · open one for its subcategories"],
  categoryShares: ["分类占比", "Category shares"], noExpenseThisMonth: ["这个月没有支出", "No expense this month"], noIncomeThisMonth: ["这个月没有收入", "No income this month"],
  reimburse_PENDING: ["待回款", "To be paid back"], reimburse_PARTIAL: ["部分回款", "Partly paid back"], reimburse_DONE: ["已回款", "Paid back"],
  reimburse_WAIVED: ["已放弃", "Given up"], reimburse_NONE: ["—", "—"],
  priceTrend: ["价格走势", "Price trend"], priceDays: ["{n} 天", "{n} days"], noPriceHistory: ["还没有价格记录", "No prices recorded yet"],
  priceTrendAria: ["价格走势，用左右方向键查看每天的价格", "Price trend; arrow keys read each day's price"],
};
Object.entries(v3).forEach(([key, [zh, en]]) => { copy["zh-CN"][key] = zh; copy.en[key] = en; });
// The built-in class is stored under its Chinese name; other classes read as the user named them.
const DEFAULT_TYPE_NAME = "未分类";
function typeLabel(name, isDefault = false) { return isDefault || name === DEFAULT_TYPE_NAME ? t("uncategorized") : name; }
export { el, clear, fmt, fmtPrice, fmtQuantity, when, epochDay, gainClass, accountName, uuid, restoreSessionCredentials, rememberSessionCredentials, clearSessionCredentials, api, $, state, hooks, t, typeLabel, iconLabel };
