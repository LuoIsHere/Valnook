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

/** Exact sums of decimal texts (as the server sends amounts): no floating point between the phone and the page. */
function parts(value) {
  const m = String(value ?? "0").trim().match(/^(-?)(\d+)(?:\.(\d+))?$/);
  if (!m) return null;
  return { negative: m[1] === "-", digits: m[2], fraction: m[3] || "" };
}
export function decAdd(...values) {
  const parsed = values.map(parts).filter(Boolean);
  const scale = Math.max(0, ...parsed.map(p => p.fraction.length));
  let total = 0n;
  for (const p of parsed) {
    const units = BigInt(p.digits + p.fraction.padEnd(scale, "0"));
    total += p.negative ? -units : units;
  }
  const negative = total < 0n, abs = negative ? -total : total, text = abs.toString().padStart(scale + 1, "0");
  const whole = scale ? text.slice(0, -scale) : text, fraction = scale ? text.slice(-scale) : "";
  return `${negative ? "-" : ""}${whole}${fraction ? `.${fraction}` : ""}`;
}
export const decNeg = value => decAdd("0", String(value).startsWith("-") ? String(value).slice(1) : `-${value}`);
export const decSub = (a, b) => decAdd(a, decNeg(b));
