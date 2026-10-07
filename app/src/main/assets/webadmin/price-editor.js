import { el, $, state, t, api, uuid } from "./core.js";
import { priceUnits } from "./decimal.js";

export function openPriceEditor({ actions, saveForm, openDrawer }) {
  const generation = state.generation;
  const form = el("form", { class: "price-editor" });
  const search = el("input", { type: "search", placeholder: t("priceSearch"), "aria-label": t("priceSearch") });
  const count = el("span", { class: "subtle", "aria-live": "polite" });
  const rows = state.instruments.map(item => {
    const id = `price-${item.id}`;
    const input = el("input", { id, name: id, type: "text", inputmode: "decimal", maxlength: "64",
      value: item.currentPrice, "aria-label": `${item.name} · ${item.currencyCode} · ${t("currentPrice")}`,
      "aria-describedby": `${id}-error` });
    const error = el("span", { id: `${id}-error`, class: "price-error" });
    const mark = el("span", { class: "price-changed", text: "•", hidden: true, "aria-hidden": "true" });
    const node = el("div", { class: "price-row", "data-instrument-id": item.id },
      el("div", { class: "price-identity" }, el("label", { for: id }, mark, item.name),
        el("div", { class: "subtle", text: `${item.symbol} · ${item.currencyCode}` })),
      el("div", { class: "field" }, input, error));
    return { item, input, error, mark, node, original: priceUnits(item.currentPrice) };
  });
  const changes = () => rows.filter(row => priceUnits(row.input.value) !== row.original);
  form.isDirty = () => changes().length > 0;
  function filter() {
    const query = search.value.trim().toLocaleLowerCase();
    rows.forEach(row => row.node.hidden = !`${row.item.name} ${row.item.symbol}`.toLocaleLowerCase().includes(query));
  }
  function sync() {
    count.textContent = t("priceChanges").replace("{n}", changes().length);
    rows.forEach(row => {
      const invalid = priceUnits(row.input.value) === null;
      row.input.setAttribute("aria-invalid", String(invalid));
      row.error.textContent = invalid ? t("priceInvalid") : "";
      row.mark.hidden = priceUnits(row.input.value) === row.original;
    });
    if (!form.pendingSubmission && !state.saving) $("[data-save]", form).disabled = !changes().length;
  }
  search.addEventListener("input", filter);
  form.addEventListener("input", sync);
  form.addEventListener("save-settled", sync);
  const error = el("p", { class: "drawer-error" });
  const controls = actions(async submit => {
    const invalid = rows.find(row => priceUnits(row.input.value) === null);
    if (invalid) { search.value = ""; filter(); sync(); invalid.input.focus(); return; }
    const edited = changes();
    if (!edited.length) return;
    await saveForm(submit, error, "/api/v1/instrument-prices", "PUT", {
      operationId: uuid(), dataGeneration: generation,
      changes: edited.map(row => ({ instrumentId: row.item.id, expectedRevision: row.item.revision, price: row.input.value.trim() }))
    });
    if (form.isConnected && !form.pendingSubmission && !document.body.dataset.ended) {
      try {
        const latest = (await api("/api/v1/instruments")).items;
        edited.forEach(row => {
          if (latest.find(item => item.id === row.item.id)?.revision !== row.item.revision)
            row.error.textContent = t("priceConflict");
        });
      } catch (_) { /* Preserve the draft and the original save error. */ }
    }
  });
  $("[data-save]", controls).textContent = t("saveAll");
  form.append(el("div", { class: "field price-search" }, search), count,
    ...rows.map(row => row.node), error, controls);
  openDrawer(t("updatePrices"), "INVESTMENT", form);
  sync();
}
