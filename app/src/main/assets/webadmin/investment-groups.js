import { el, fmt, fmtPrice, gainClass, state, t } from "./core.js";
import { avatar } from "./icons.js";

export function investmentGroups({ table, positionDetail }) {
  const groups = el("div", { class: "investment-groups" });
  const cell = (primary, secondary, cls = "") => el("td", { class: "numeric" },
    el("div", { class: cls, text: primary }), el("div", { class: "subtle", text: secondary }));
  function holdings(positions) {
    return table([t("nameCode"), t("marketQuantity"), t("priceCost"), t("unrealized")], positions.map(v =>
      el("tr", { "data-clickable": "true", onclick: () => positionDetail(v.id) },
        el("td", {}, el("strong", { text: v.name }), el("div", { class: "subtle", text: `${v.symbol} · ${v.currencyCode}` })),
        cell(fmt(v.marketValue, v.currencyCode), fmt(v.quantity)),
        cell(fmtPrice(v.currentPrice, v.currencyCode), fmt(v.averageCost, v.currencyCode)),
        cell(fmt(v.unrealized, v.currencyCode), v.unrealizedPercent == null ? "—" : `${fmt(v.unrealizedPercent)}%`, gainClass(v.unrealized))
      )));
  }
  state.accounts.forEach(account => {
    const positions = state.positions.filter(v => v.accountId === account.id)
      .sort((a, b) => b.lastActivityAtMs - a.lastActivityAtMs || a.id - b.id);
    if (!positions.length) return;
    const active = positions.filter(v => Number(v.quantity) > 0);
    const closed = positions.filter(v => Number(v.quantity) === 0);
    const bodyId = `investment-account-${account.id}`;
    const expanded = state.expandedInvestmentAccounts.has(account.id);
    const body = el("div", { id: bodyId, class: "investment-group-body", hidden: !expanded }, holdings(active));
    if (closed.length) body.append(el("details", { class: "closed-positions" },
      el("summary", { text: `${t("cleared")} (${closed.length})` }), holdings(closed)));
    const toggle = el("button", { type: "button", class: "investment-account-toggle", "aria-expanded": String(expanded),
      "aria-controls": bodyId, onclick: () => {
        const next = toggle.getAttribute("aria-expanded") !== "true";
        toggle.setAttribute("aria-expanded", String(next)); body.hidden = !next;
        if (next) state.expandedInvestmentAccounts.add(account.id); else state.expandedInvestmentAccounts.delete(account.id);
      } },
      el("span", { class: "account-cell" }, avatar(account.icon), el("strong", { text: account.name }),
        el("span", { class: "expansion-chevron", "aria-hidden": "true" }, "⌄")),
      el("strong", { class: "numeric", text: fmt(account.investments, state.session.baseCurrency) }));
    const profit = el("div", { class: "investment-account-profit" },
      el("span", {}, `${t("realized")} `, el("span", { class: gainClass(account.realized), text: fmt(account.realized, state.session.baseCurrency) })),
      el("span", {}, `${t("unrealized")} `, el("span", { class: gainClass(account.unrealized), text: fmt(account.unrealized, state.session.baseCurrency) })));
    groups.append(el("section", { class: "investment-group" }, toggle, profit,
      account.investmentComplete === false ? el("p", { class: "subtle", text: t("incomplete") }) : null, body));
  });
  if (!groups.children.length) groups.append(el("div", { class: "empty", text: t("empty") }));
  return groups;
}
