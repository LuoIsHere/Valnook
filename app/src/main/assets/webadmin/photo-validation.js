// Inspect dimensions and animation flags before handing user images to a decoder.
export function staticPhotoSize(buffer) {
  const b = new Uint8Array(buffer), d = new DataView(buffer);
  const text = (offset, length) => String.fromCharCode(...b.subarray(offset, offset + length));
  const finish = (width, height) => {
    if (!(width > 0 && height > 0 && width * height <= 64000000)) throw new Error("Invalid photo dimensions");
    return { width, height };
  };
  if (b.length < 12) throw new Error("Invalid photo");
  if (b[0] === 137 && text(1, 7) === "PNG\r\n\x1a\n") {
    if (b.length < 33 || text(12, 4) !== "IHDR") throw new Error("Invalid PNG");
    const size = finish(d.getUint32(16), d.getUint32(20));
    for (let offset = 8; offset + 12 <= b.length;) {
      const length = d.getUint32(offset), type = text(offset + 4, 4);
      if (offset + length + 12 > b.length || type === "acTL") throw new Error("Invalid or animated PNG");
      if (type === "IEND") return size;
      offset += length + 12;
    }
  } else if (text(0, 4) === "RIFF" && text(8, 4) === "WEBP") {
    let size;
    for (let offset = 12; offset + 8 <= b.length;) {
      const length = d.getUint32(offset + 4, true), type = text(offset, 4), p = offset + 8;
      if (p + length > b.length || type === "ANIM" || type === "ANMF") throw new Error("Invalid or animated WebP");
      if (type === "VP8X" && length >= 10) {
        if (b[p] & 2) throw new Error("Animated WebP");
        size = finish(1 + b[p + 4] + (b[p + 5] << 8) + (b[p + 6] << 16), 1 + b[p + 7] + (b[p + 8] << 8) + (b[p + 9] << 16));
      } else if (type === "VP8 " && length >= 10 && b[p + 3] === 157 && b[p + 4] === 1 && b[p + 5] === 42) {
        size ||= finish(d.getUint16(p + 6, true) & 16383, d.getUint16(p + 8, true) & 16383);
      } else if (type === "VP8L" && length >= 5 && b[p] === 47) {
        const bits = d.getUint32(p + 1, true);
        size ||= finish(1 + (bits & 16383), 1 + ((bits >>> 14) & 16383));
      }
      offset = p + length + (length & 1);
    }
    if (size) return size;
  } else if (b[0] === 255 && b[1] === 216) {
    for (let offset = 2; offset + 4 <= b.length;) {
      if (b[offset++] !== 255) break;
      while (b[offset] === 255) offset++;
      const marker = b[offset++];
      if (marker === 217 || marker === 218) break;
      if (marker === 1 || (marker >= 208 && marker <= 215)) continue;
      if (offset + 2 > b.length) break;
      const length = d.getUint16(offset);
      if (length < 2 || offset + length > b.length) break;
      if ([192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].includes(marker) && length >= 8)
        return finish(d.getUint16(offset + 5), d.getUint16(offset + 3));
      offset += length;
    }
  }
  throw new Error("Unsupported or damaged photo");
}
