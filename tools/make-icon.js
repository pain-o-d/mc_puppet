#!/usr/bin/env node
/*
 * Draws the mod's icon: a marionette's crossbar, its strings, and a block on
 * the end of them. Sixteen pixels square, written out eight times the size, so
 * that it stays pixels. A drawing in code rather than a file somebody painted:
 * it can be changed by changing a letter, and needs no editor.
 *
 *   node tools/make-icon.js     writes common/src/main/resources/assets/mc_puppet/icon.png
 */
"use strict";
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const COLOURS = {
  ".": [0x1b, 0x1e, 0x2b],   // night
  "w": [0x8a, 0x5a, 0x2b],   // the crossbar's wood
  "W": [0xb5, 0x7b, 0x3e],   // and its lit edge
  "s": [0xd8, 0xd8, 0xe0],   // string
  "g": [0x5d, 0xa8, 0x3a],   // grass
  "G": [0x7c, 0xc8, 0x4f],   // grass, lit
  "d": [0x7a, 0x55, 0x36],   // dirt
  "D": [0x5e, 0x40, 0x28],   // dirt, in shade
  "e": [0x2a, 0x2a, 0x2a],   // the block's eyes: it is looked through
};
const ART = [
  "................",
  "..WWWWWWWWWWWW..",
  "..wwwwwwwwwwww..",
  "...s...ww...s...",
  "...s...Ww...s...",
  "...s...ww...s...",
  "...s....s...s...",
  "...s....s...s...",
  "...s....s...s...",
  "...GGGGGGGGGG...",
  "...gGgggGgggg...",
  "...ddeeddeedD...",
  "...ddeeddeedD...",
  "...dddddddddD...",
  "...dDddDdddDD...",
  "................",
];
const SCALE = 8;
const size = ART.length * SCALE;

const raw = Buffer.alloc(size * (1 + size * 4));
for (let y = 0; y < size; y++) {
  const row = y * (1 + size * 4);
  raw[row] = 0;
  for (let x = 0; x < size; x++) {
    const colour = COLOURS[ART[Math.floor(y / SCALE)][Math.floor(x / SCALE)]];
    raw.set([...colour, 255], row + 1 + x * 4);
  }
}

function chunk(type, data) {
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const out = Buffer.alloc(8 + data.length + 4);
  out.writeUInt32BE(data.length, 0);
  body.copy(out, 4);
  out.writeUInt32BE(zlib.crc32 ? zlib.crc32(body) : crc32(body), 8 + data.length);
  return out;
}

function crc32(buffer) {
  let crc = ~0;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return ~crc >>> 0;
}

const header = Buffer.alloc(13);
header.writeUInt32BE(size, 0);
header.writeUInt32BE(size, 4);
header.set([8, 6, 0, 0, 0], 8);   // eight bits a channel, RGBA
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk("IHDR", header), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0)),
]);
const target = path.join(__dirname, "..", "common", "src", "main", "resources", "assets", "mc_puppet", "icon.png");
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.writeFileSync(target, png);
console.log(`${target}: ${size}x${size}, ${png.length} bytes`);
