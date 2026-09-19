/**
 * Just enough PNG to compare two screenshots.
 *
 * The game writes 8-bit RGB or RGBA, not interlaced; that is what is read
 * here, with Node's own zlib and nothing else. Anything more exotic is
 * refused in words rather than compared wrongly.
 */
const zlib = require("zlib");

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** @returns {{width: number, height: number, data: Buffer}} RGBA, four bytes a pixel */
function decode(buffer) {
  if (buffer.length < 8 || !buffer.subarray(0, 8).equals(SIGNATURE)) throw new Error("not a PNG");
  let at = 8;
  let header = null;
  const packed = [];
  while (at + 8 <= buffer.length) {
    const length = buffer.readUInt32BE(at);
    const type = buffer.toString("ascii", at + 4, at + 8);
    const body = buffer.subarray(at + 8, at + 8 + length);
    if (type === "IHDR") {
      header = { width: body.readUInt32BE(0), height: body.readUInt32BE(4), depth: body[8], colour: body[9], interlace: body[12] };
    } else if (type === "IDAT") packed.push(body);
    else if (type === "IEND") break;
    at += 12 + length;
  }
  if (!header) throw new Error("a PNG without a header");
  if (header.depth !== 8 || (header.colour !== 2 && header.colour !== 6) || header.interlace !== 0) {
    throw new Error(`only 8-bit RGB and RGBA without interlacing are read (this is depth ${header.depth}, colour type ${header.colour})`);
  }
  const channels = header.colour === 6 ? 4 : 3;
  const stride = header.width * channels;
  const raw = zlib.inflateSync(Buffer.concat(packed));
  if (raw.length < (stride + 1) * header.height) throw new Error("a PNG that ends early");
  const lines = Buffer.alloc(stride * header.height);
  for (let y = 0; y < header.height; y++) {
    const filter = raw[y * (stride + 1)];
    const from = y * (stride + 1) + 1;
    const to = y * stride;
    for (let x = 0; x < stride; x++) {
      const left = x >= channels ? lines[to + x - channels] : 0;
      const up = y > 0 ? lines[to + x - stride] : 0;
      const upLeft = y > 0 && x >= channels ? lines[to + x - stride - channels] : 0;
      let predicted = 0;
      if (filter === 1) predicted = left;
      else if (filter === 2) predicted = up;
      else if (filter === 3) predicted = (left + up) >> 1;
      else if (filter === 4) {
        const estimate = left + up - upLeft;
        const a = Math.abs(estimate - left);
        const b = Math.abs(estimate - up);
        const c = Math.abs(estimate - upLeft);
        predicted = a <= b && a <= c ? left : b <= c ? up : upLeft;
      } else if (filter !== 0) throw new Error(`unknown PNG filter ${filter}`);
      lines[to + x] = (raw[from + x] + predicted) & 0xff;
    }
  }
  if (channels === 4) return { width: header.width, height: header.height, data: lines };
  const data = Buffer.alloc(header.width * header.height * 4);
  for (let pixel = 0; pixel < header.width * header.height; pixel++) {
    lines.copy(data, pixel * 4, pixel * 3, pixel * 3 + 3);
    data[pixel * 4 + 3] = 255;
  }
  return { width: header.width, height: header.height, data };
}

const CRC = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function chunk(type, body) {
  const out = Buffer.alloc(12 + body.length);
  out.writeUInt32BE(body.length, 0);
  out.write(type, 4, "ascii");
  body.copy(out, 8);
  let crc = 0xffffffff;
  for (let index = 4; index < 8 + body.length; index++) crc = CRC[(crc ^ out[index]) & 0xff] ^ (crc >>> 8);
  out.writeUInt32BE((crc ^ 0xffffffff) >>> 0, 8 + body.length);
  return out;
}

/** RGBA in, PNG out: for writing down where two images differ. */
function encode({ width, height, data }) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 6;
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) data.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  return Buffer.concat([SIGNATURE, chunk("IHDR", header), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

/**
 * How far apart two images are.
 *
 * @param {{tolerance?: number, region?: {x: number, y: number, w: number, h: number}}} options
 *   tolerance: how far a channel may be off before the pixel counts, 0-255
 *   (the default, 16, lets through the dither and the odd animated pixel);
 *   region: the part to compare, in image pixels
 * @returns {{same_size: boolean, pixels: number, different: number, percent: number, image?: object}}
 *   image: the first with every differing pixel in red, when any differ
 */
function diff(first, second, options = {}) {
  if (first.width !== second.width || first.height !== second.height) {
    return { same_size: false, pixels: 0, different: 0, percent: 100,
      says: `${first.width}x${first.height} against ${second.width}x${second.height}` };
  }
  const tolerance = options.tolerance === undefined ? 16 : Number(options.tolerance);
  const region = options.region || { x: 0, y: 0, w: first.width, h: first.height };
  const x0 = Math.max(0, region.x);
  const y0 = Math.max(0, region.y);
  const x1 = Math.min(first.width, region.x + region.w);
  const y1 = Math.min(first.height, region.y + region.h);
  let different = 0;
  let marked = null;
  for (let y = y0; y < y1; y++) {
    for (let x = x0; x < x1; x++) {
      const at = (y * first.width + x) * 4;
      if (Math.abs(first.data[at] - second.data[at]) > tolerance
          || Math.abs(first.data[at + 1] - second.data[at + 1]) > tolerance
          || Math.abs(first.data[at + 2] - second.data[at + 2]) > tolerance) {
        different++;
        if (!marked) marked = Buffer.from(first.data);
        marked[at] = 255;
        marked[at + 1] = 0;
        marked[at + 2] = 0;
        marked[at + 3] = 255;
      }
    }
  }
  const pixels = Math.max(1, (x1 - x0) * (y1 - y0));
  const result = { same_size: true, pixels, different, percent: Math.round((different / pixels) * 10000) / 100 };
  if (marked) result.image = { width: first.width, height: first.height, data: marked };
  return result;
}

module.exports = { decode, encode, diff };
