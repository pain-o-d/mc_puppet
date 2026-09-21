#!/usr/bin/env node
/*
 * Draws the picture a link to the repository unfolds into, in Discord and the like:
 * the icon, the name, and what it is for, in the icon's own pixels. 1280x640, which
 * is what GitHub asks for under Settings > Social preview.
 *
 *   node tools/make-social-preview.js     writes docs/social-preview.png
 *
 * In code for the reason the icon is: a letter changed here is a picture changed.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const W = 1280, H = 640;
const NIGHT = [0x1b, 0x1e, 0x2b], PAPER = [0xe8, 0xe8, 0xf0], GRASS = [0x7c, 0xc8, 0x4f], DIM = [0x8d, 0x93, 0xa8];
const ICON = {
  ".": NIGHT, "w": [0x8a, 0x5a, 0x2b], "W": [0xb5, 0x7b, 0x3e], "s": [0xd8, 0xd8, 0xe0], "g": [0x5d, 0xa8, 0x3a],
  "G": GRASS, "d": [0x7a, 0x55, 0x36], "D": [0x5e, 0x40, 0x28], "e": [0x2a, 0x2a, 0x2a],
};
const ART = [
  "................", "..WWWWWWWWWWWW..", "..wwwwwwwwwwww..", "...s...ww...s...", "...s...Ww...s...", "...s...ww...s...",
  "...s....s...s...", "...s....s...s...", "...s....s...s...", "...GGGGGGGGGG...", "...gGgggGgggg...", "...ddeeddeedD...",
  "...ddeeddeedD...", "...dddddddddD...", "...dDddDdddDD...", "................",
];
// Five by seven, the letters these lines need and no others.
const FONT = {
  A: ["01110", "10001", "10001", "11111", "10001", "10001", "10001"], B: ["11110", "10001", "10001", "11110", "10001", "10001", "11110"], C: ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
  D: ["11110", "10001", "10001", "10001", "10001", "10001", "11110"], E: ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
  F: ["11111", "10000", "10000", "11110", "10000", "10000", "10000"], G: ["01110", "10001", "10000", "10111", "10001", "10001", "01110"],
  H: ["10001", "10001", "10001", "11111", "10001", "10001", "10001"], I: ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
  K: ["10001", "10010", "10100", "11000", "10100", "10010", "10001"], L: ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
  M: ["10001", "11011", "10101", "10101", "10001", "10001", "10001"], N: ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
  O: ["01110", "10001", "10001", "10001", "10001", "10001", "01110"], P: ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
  R: ["11110", "10001", "10001", "11110", "10100", "10010", "10001"], S: ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
  T: ["11111", "00100", "00100", "00100", "00100", "00100", "00100"], U: ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
  V: ["10001", "10001", "10001", "10001", "10001", "01010", "00100"], W: ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
  Y: ["10001", "10001", "01010", "00100", "00100", "00100", "00100"], " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
  ",": ["00000", "00000", "00000", "00000", "00110", "00100", "01000"], ".": ["00000", "00000", "00000", "00000", "00000", "00110", "00110"],
};

const pixels = Buffer.alloc(W * H * 3);
for (let i = 0; i < W * H; i++) pixels.set(NIGHT, i * 3);
const put = (x, y, colour) => { if (x >= 0 && y >= 0 && x < W && y < H) pixels.set(colour, (y * W + x) * 3); };
const block = (x, y, size, colour) => { for (let dy = 0; dy < size; dy++) for (let dx = 0; dx < size; dx++) put(x + dx, y + dy, colour); };

function text(words, x, y, size, colour) {
  let at = x;
  for (const letter of words) {
    const glyph = FONT[letter];
    if (!glyph) throw new Error(`no glyph for "${letter}"`);
    glyph.forEach((row, gy) => [...row].forEach((bit, gx) => { if (bit === "1") block(at + gx * size, y + gy * size, size, colour); }));
    at += 6 * size;
  }
  return at - x - size;
}

const ICON_SCALE = 24;                       // sixteen pixels, 384 across
const iconX = 96, iconY = (H - 16 * ICON_SCALE) / 2;
ART.forEach((row, y) => [...row].forEach((cell, x) => block(iconX + x * ICON_SCALE, iconY + y * ICON_SCALE, ICON_SCALE, ICON[cell])));

const left = iconX + 16 * ICON_SCALE + 72;
text("MC PUPPET", left, 170, 12, PAPER);
block(left, 170 + 7 * 12 + 28, 8, GRASS); block(left + 8, 170 + 7 * 12 + 28, 8, GRASS);
for (let x = 0; x < 640; x += 8) block(left + x, 170 + 7 * 12 + 28, 8, GRASS);
text("SEE AND DRIVE", left, 320, 6, PAPER);
text("A RUNNING MINECRAFT", left, 320 + 60, 6, PAPER);
text("FROM A PROGRAM", left, 320 + 120, 6, PAPER);
text("FOR TESTING MODS. OFF BY DEFAULT.", left, 320 + 210, 3, DIM);

const raw = Buffer.alloc(H * (1 + W * 3));
for (let y = 0; y < H; y++) { raw[y * (1 + W * 3)] = 0; pixels.copy(raw, y * (1 + W * 3) + 1, y * W * 3, (y + 1) * W * 3); }
function crc32(buffer) { let crc = ~0; for (const byte of buffer) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1)); } return ~crc >>> 0; }
function chunk(type, data) {
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0); body.copy(out, 4); out.writeUInt32BE(crc32(body), 8 + data.length);
  return out;
}
const header = Buffer.alloc(13);
header.writeUInt32BE(W, 0); header.writeUInt32BE(H, 4); header.set([8, 2, 0, 0, 0], 8);   // eight bits a channel, RGB
const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk("IHDR", header), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
const target = path.join(__dirname, "..", "docs", "social-preview.png");
fs.writeFileSync(target, png);
console.log(`${target}: ${W}x${H}, ${png.length} bytes`);
