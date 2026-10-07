#!/usr/bin/env node
// Checks that the two builds' twins agree (docs/MULTIVERSION.md: a difference between versions
// lives in compat/, in both files, with the same names). Prints only differences; exit 1 if any.
//
//   - every file in a package called "compat" exists on both sides, with the same non-private
//     declarations (classes, methods, fields; signatures, not bodies or constant values);
//   - the two mc_puppet.mixins.json name the same mixin classes under mixins/client/server
//     (compatibilityLevel, min_version and the like differ by design and are not compared).
//
// Usage: node tools/check-twins.js        (from anywhere; paths are relative to this file)
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const OTHER = 'mc1.20.1';
// [the 1.21.1 tree, the 1.20.1 tree] pairs whose compat packages are twins.
const SOURCE_PAIRS = [
  ['common/src/main/java', `${OTHER}/common/src/main/java`],
  ['fabric/src/main/java', `${OTHER}/fabric/src/main/java`],
];
const RESOURCE_PAIRS = [['common/src/main/resources', `${OTHER}/common/src/main/resources`]];

const problems = [];
const report = (message) => problems.push(message);

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'build' || entry.name === 'run' || entry.name === 'node_modules') continue;
      walk(full, out);
    } else out.push(full);
  }
  return out;
}

/** Blanks comments and string/char literals, keeping the length and the newlines. */
function blank(source) {
  return source.replace(
    /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'/g,
    (match) => match.replace(/[^\n]/g, ' '),
  );
}

/** The non-private declaration headers of a Java file, one normalised line each. */
function signatures(source) {
  const text = blank(source);
  const found = [];
  let buf = '';
  let parens = 0;
  const keep = (header) => {
    const line = header.replace(/\s+/g, ' ').trim();
    if (!line || /^(package|import)\b/.test(line) || /\bprivate\b/.test(line)) return;
    found.push(line);
  };
  /** Index just past the balanced {...} or the statement that starts at i. */
  const skipBody = (i) => {
    let depth = 0;
    for (; i < text.length; i++) {
      if (text[i] === '{') depth++;
      else if (text[i] === '}' && --depth === 0) return i + 1;
    }
    return i;
  };
  const skipStatement = (i) => {
    let depth = 0;
    for (; i < text.length; i++) {
      const c = text[i];
      if (c === '{' || c === '(') depth++;
      else if (c === '}' || c === ')') depth--;
      else if (c === ';' && depth <= 0) return i + 1;
    }
    return i;
  };
  for (let i = 0; i < text.length; ) {
    const c = text[i];
    if (c === '(') parens++;
    else if (c === ')') parens--;
    if (parens > 0 || !'{;=}'.includes(c)) {
      buf += c;
      i++;
    } else if (c === ';') {
      keep(buf);
      buf = '';
      i++;
    } else if (c === '=') {
      keep(buf);
      buf = '';
      i = skipStatement(i + 1);
    } else if (c === '{') {
      if (/\b(class|interface|enum|record)\b/.test(buf) && !/->/.test(buf)) {
        keep(buf); // a type: go on inside it, its members are declarations too
        buf = '';
        i++;
      } else {
        if (buf.includes('(')) keep(buf); // a method or constructor: header only
        buf = '';
        i = skipBody(i);
      }
    } else {
      // '}' closes a type
      buf = '';
      i++;
    }
  }
  return found;
}

function compare(label, a, b) {
  const inB = new Set(b);
  const inA = new Set(a);
  for (const line of a) if (!inB.has(line)) report(`${label}: only in 1.21.1: ${line}`);
  for (const line of b) if (!inA.has(line)) report(`${label}: only in 1.20.1: ${line}`);
}

function compatFiles(base) {
  return walk(base)
    .filter((file) => file.endsWith('.java') && path.relative(base, file).split(path.sep).includes('compat'))
    .map((file) => path.relative(base, file).split(path.sep).join('/'));
}

for (const [a, b] of SOURCE_PAIRS) {
  const baseA = path.join(root, a);
  const baseB = path.join(root, b);
  const filesA = compatFiles(baseA);
  const filesB = compatFiles(baseB);
  for (const file of new Set([...filesA, ...filesB])) {
    const label = `${a.split('/')[0]}/${file.split('/').slice(-2).join('/')}`;
    if (!filesA.includes(file)) report(`${label}: has no 1.21.1 twin (${a}/${file})`);
    else if (!filesB.includes(file)) report(`${label}: has no 1.20.1 twin (${b}/${file})`);
    else {
      const srcA = fs.readFileSync(path.join(baseA, file), 'utf8');
      const srcB = fs.readFileSync(path.join(baseB, file), 'utf8');
      // A mixin accessor's member types are the game's own and differ by design
      // (e.g. DisconnectedScreen.info vs .reason); existence on both sides is still required.
      if (/@Mixin\(/.test(srcA) && /@Mixin\(/.test(srcB)) continue;
      compare(label, signatures(srcA), signatures(srcB));
    }
  }
}

for (const [a, b] of RESOURCE_PAIRS) {
  const names = new Set(
    [a, b].flatMap((dir) => walk(path.join(root, dir))).filter((f) => f.endsWith('.mixins.json')).map((f) => path.basename(f)),
  );
  for (const name of names) {
    const fileA = path.join(root, a, name);
    const fileB = path.join(root, b, name);
    if (!fs.existsSync(fileA) || !fs.existsSync(fileB)) {
      report(`${name}: exists only in ${fs.existsSync(fileA) ? a : b}`);
      continue;
    }
    const jsonA = JSON.parse(fs.readFileSync(fileA, 'utf8'));
    const jsonB = JSON.parse(fs.readFileSync(fileB, 'utf8'));
    if (jsonA.package !== jsonB.package) report(`${name}: package differs: ${jsonA.package} / ${jsonB.package}`);
    for (const list of ['mixins', 'client', 'server']) {
      compare(`${name} ${list}`, jsonA[list] || [], jsonB[list] || []);
    }
  }
}

if (problems.length) {
  console.log(problems.join('\n'));
  console.log(`check-twins: ${problems.length} difference(s) between the 1.21.1 and 1.20.1 builds`);
  process.exit(1);
}
