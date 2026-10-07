// Presentation rounding only. Editable values and API payloads keep their original precision.
export function decimalText(value, locale, digits = 2, trim = false) {
  if (value === null || value === undefined || value === "") return "—";
  const match = String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/);
  if (!match) return String(value);
  const fraction = match[3] || "";
  let units = BigInt(match[2] + fraction.slice(0, digits).padEnd(digits, "0"));
  if (Number(fraction[digits] || "0") >= 5) units++;
  const scale = 10n ** BigInt(digits);
  const integer = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(units / scale);
  let decimals = (units % scale).toString().padStart(digits, "0");
  if (trim) decimals = decimals.replace(/0+$/, "");
  return `${match[1] && units !== 0n ? "-" : ""}${integer}${decimals ? "." + decimals : ""}`;
}

export function priceUnits(value) {
  const text = String(value).trim();
  if (text.length > 64 || !/^\d+(?:\.\d{1,5})?$/.test(text)) return null;
  const [whole, fraction = ""] = text.split(".");
  const units = BigInt(whole + fraction.padEnd(5, "0"));
  // The Android read model also represents prices at E8 precision.
  return units <= 9223372036854775n ? units : null;
}
