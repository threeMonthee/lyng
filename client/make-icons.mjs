// 生成 PWA 图标：深色底上一个白色对话气泡。无依赖，直接写 PNG。
import { deflateSync } from "node:zlib";
import { writeFileSync } from "node:fs";

function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function inRoundRect(x, y, x0, y0, x1, y1, r) {
  if (x < x0 || x > x1 || y < y0 || y > y1) return false;
  const cx = Math.min(Math.max(x, x0 + r), x1 - r);
  const cy = Math.min(Math.max(y, y0 + r), y1 - r);
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
}

function icon(size) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  const u = size / 100;
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      const px = x / u, py = y / u;
      const bubble = inRoundRect(px, py, 22, 26, 78, 62, 14);
      const tail = py >= 58 && py <= 74 && px >= 30 && px <= 30 + (74 - py) * 0.9 && px >= 30 + (py - 58) * 0.0;
      const on = bubble || tail;
      const i = y * (size * 4 + 1) + 1 + x * 4;
      raw[i] = on ? 255 : 28; raw[i + 1] = on ? 255 : 28; raw[i + 2] = on ? 255 : 30; raw[i + 3] = 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

writeFileSync("public/icon-192.png", icon(192));
writeFileSync("public/icon-512.png", icon(512));
