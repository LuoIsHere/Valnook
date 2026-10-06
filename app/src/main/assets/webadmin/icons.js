import { $, state, t, el } from "./core.js";
import { symbols } from "./symbols.js";

export function symbol(key) {
  const data = symbols[key] || symbols.account_balance;
  return el("svg", { class: "symbol", viewBox: data.viewBox, "aria-hidden": "true" },
    data.paths.map(d => el("path", { d, fill: "currentColor" })));
}

export function avatar(icon) {
  const result = el("span", { class: "avatar", "aria-hidden": "true" }, symbol(icon?.value));
  if (icon?.type === "IMAGE" && /^[a-f0-9]{64}$/.test(icon.value)) {
    const epoch = state.viewEpoch;
    fetch(`/api/v1/account-icons/${icon.value}`, { headers: state.sessionToken ? { Authorization: `Bearer ${state.sessionToken}` } : {} })
      .then(response => { if (!response.ok) throw new Error(); return response.blob(); })
      .then(blob => {
        if (epoch !== state.viewEpoch) return;
        const url = URL.createObjectURL(blob), image = el("img", { alt: "", src: url });
        image.onload = image.onerror = () => URL.revokeObjectURL(url);
        result.replaceChildren(image);
      }).catch(() => {});
  }
  return result;
}

export function iconPicker(current) {
  let change = null, bitmap = null, zoom = 1, offsetX = 0, offsetY = 0, imageVersion = 0, processing = false;
  const preview = el("canvas", { width: 256, height: 256, class: "crop-preview", "aria-label": t("preview"), tabindex: 0, hidden: true });
  const canvas = preview.getContext("2d");
  const error = el("p", { class: "image-error full", role: "alert" });
  const currentAvatar = avatar(current);
  const slider = el("input", { type: "range", min: 1, max: 3, step: .01, value: 1, "aria-label": t("zoom"), hidden: true });
  const repaint = () => {
    if (!bitmap) return;
    const scale = Math.max(256 / bitmap.width, 256 / bitmap.height) * zoom;
    const w = bitmap.width * scale, h = bitmap.height * scale;
    offsetX = Math.max(-(w - 256) / 2, Math.min((w - 256) / 2, offsetX));
    offsetY = Math.max(-(h - 256) / 2, Math.min((h - 256) / 2, offsetY));
    canvas.clearRect(0, 0, 256, 256); canvas.drawImage(bitmap, (256 - w) / 2 + offsetX, (256 - h) / 2 + offsetY, w, h);
  };
  const upload = el("input", { type: "file", accept: "image/png,image/jpeg,image/webp", "aria-label": t("upload") });
  upload.addEventListener("change", async () => {
    const version = ++imageVersion; error.textContent = "";
    const file = upload.files[0]; if (!file) return;
    processing = true;
    try {
      if (file.size > 32 * 1024 * 1024) throw new Error();
      const url = URL.createObjectURL(file), image = new Image();
      try { image.src = url; await image.decode(); } finally { URL.revokeObjectURL(url); }
      if (version !== imageVersion) return;
      if (image.width * image.height > 64 * 1024 * 1024) throw new Error();
      bitmap = image; zoom = 1; offsetX = offsetY = 0; slider.value = 1;
      preview.hidden = slider.hidden = false; currentAvatar.hidden = true; change = "image"; repaint();
      grid.querySelectorAll("button").forEach(v => v.setAttribute("aria-pressed", "false"));
    } catch (_) { error.textContent = t("imageError"); }
    finally { if (version === imageVersion) processing = false; }
  });
  slider.addEventListener("input", () => { zoom = Number(slider.value); repaint(); });
  preview.addEventListener("keydown", event => {
    if (!event.key.startsWith("Arrow")) return;
    event.preventDefault();
    offsetX += event.key === "ArrowRight" ? 8 : event.key === "ArrowLeft" ? -8 : 0;
    offsetY += event.key === "ArrowDown" ? 8 : event.key === "ArrowUp" ? -8 : 0;
    repaint(); preview.dispatchEvent(new Event("input", { bubbles: true }));
  });
  preview.addEventListener("pointerdown", event => {
    preview.setPointerCapture(event.pointerId);
    let x = event.clientX, y = event.clientY;
    const move = e => { const ratio = 256 / preview.clientWidth; offsetX += (e.clientX - x) * ratio; offsetY += (e.clientY - y) * ratio; x = e.clientX; y = e.clientY; repaint(); };
    const end = () => { preview.removeEventListener("pointermove", move); preview.removeEventListener("lostpointercapture", end); preview.dispatchEvent(new Event("input", { bubbles: true })); };
    preview.addEventListener("pointermove", move); preview.addEventListener("lostpointercapture", end);
  });
  const grid = el("div", { class: "symbol-grid", role: "group", "aria-label": t("icon") },
    Object.keys(symbols).map(key => el("button", { type: "button", "aria-label": key.replaceAll("_", " "), title: key.replaceAll("_", " "), "aria-pressed": String((current?.value || "account_balance") === key), onclick: event => {
      imageVersion++; processing = false; change = { type: "SYMBOL", value: key }; bitmap = null;
      preview.hidden = slider.hidden = true; currentAvatar.hidden = false; currentAvatar.replaceChildren(symbol(key));
      grid.querySelectorAll("button").forEach(v => v.setAttribute("aria-pressed", String(v === event.currentTarget)));
      grid.dispatchEvent(new Event("input", { bubbles: true }));
    } }, symbol(key))));
  return {
    element: el("fieldset", { class: "icon-picker full" }, el("legend", { text: t("icon") }),
      el("div", { class: "crop-row" }, currentAvatar, preview, el("div", {}, upload, slider, el("p", { class: "subtle", text: t("imageHint") }))), grid, error),
    async value() {
      if (processing) throw new Error(t("loading"));
      if (change !== "image") return change;
      const blob = await new Promise(resolve => preview.toBlob(resolve, "image/webp", .88));
      if (!blob || blob.size > 128 * 1024) throw new Error(t("imageError"));
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte);
      return { type: "IMAGE", imageBase64: btoa(binary) };
    }
  };
}
