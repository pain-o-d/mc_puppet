/**
 * Scenarios: a list of operations with what each should answer.
 *
 *   { "name": "the currency button restates the counter",
 *     "steps": [
 *       { "side": "client", "op": "use_entity", "args": { "type": "minecraft:villager" } },
 *       { "side": "client", "op": "wait", "args": { "for": "screen", "value": "Merchant" } },
 *       { "side": "client", "op": "screen", "save": "before",
 *         "expect": [ { "path": "widgets[text~=Currency]", "exists": true },
 *                     { "path": "offers#", "gte": 1 } ] },
 *       { "side": "client", "op": "click_widget", "args": { "text": "Currency" } },
 *       { "side": "client", "op": "screen",
 *         "expect": [ { "path": "offers[0].buy.id", "not": "${before.offers[0].buy.id}" } ] } ] }
 *
 * A path walks the answer: a.b, a[2], a[key=value] and a[key~=part] for the
 * first element that matches (the key may be a path: slots[stack.id~=sword]), and a# for how many there are. "${name.path}"
 * anywhere in args or in an expectation is replaced by a value saved earlier,
 * and "${= before.count - 3 * price}" by a sum over them.
 *
 * Beside "steps" a scenario may have "setup" and "teardown". Teardown runs
 * whatever happened before it, so a failed run leaves the world as it found
 * it and the next run does not fail for that reason.
 *
 * A step that is {"let": {"name": value}} calls nothing: it gives a name to a
 * value or a sum, for the steps after it to use, and may "expect" of what it
 * named as of any answer.
 *
 * A step with "eventually": true (or a number of milliseconds) is asked again
 * until its expectations hold. For one expectation on one answer the game's
 * own wait_until is exact to the tick and one round trip; "eventually" is for
 * the rest: several expectations at once, a value saved earlier, or - with
 * "every_ms" - an operation too costly to be asked twenty times a second.
 *
 * While a scenario runs the game's log is read, and an error logged during it
 * fails it: a test that passes while the game throws behind it has not passed.
 *
 * Pure functions apart from run(), so the language is tested without a game.
 */

/** Splits "widgets[text~=Buy it].x" into steps. */
function parsePath(path) {
  const steps = [];
  let rest = String(path).trim();
  while (rest.length) {
    if (rest[0] === ".") {
      rest = rest.slice(1);
      continue;
    }
    if (rest[0] === "[") {
      const close = rest.indexOf("]");
      if (close < 0) throw new Error(`unclosed [ in path "${path}"`);
      const inside = rest.slice(1, close);
      rest = rest.slice(close + 1);
      const filter = /^([^~=]+)(~=|=)(.*)$/.exec(inside);
      if (filter) steps.push({ filter: filter[1].trim(), partial: filter[2] === "~=", value: filter[3] });
      else if (/^-?\d+$/.test(inside.trim())) steps.push({ index: Number(inside) });
      else throw new Error(`[${inside}] in path "${path}" is neither an index nor key=value`);
      continue;
    }
    const match = /^[^.[#]+/.exec(rest);
    if (match) {
      steps.push({ key: match[0] });
      rest = rest.slice(match[0].length);
      continue;
    }
    if (rest[0] === "#") {
      steps.push({ count: true });
      rest = rest.slice(1);
      continue;
    }
    throw new Error(`cannot read path "${path}" at "${rest}"`);
  }
  return steps;
}

/** The value at a path, or undefined where the path leads nowhere. */
function valueAt(root, path) {
  if (path === undefined || path === null || path === "") return root;
  let value = root;
  for (const step of parsePath(path)) {
    if (value === undefined || value === null) return undefined;
    if (step.key !== undefined) value = value[step.key];
    else if (step.index !== undefined) value = Array.isArray(value) ? value.at(step.index) : undefined;
    else if (step.count) value = Array.isArray(value) ? value.length : typeof value === "object" ? Object.keys(value).length : undefined;
    else if (step.filter !== undefined) {
      if (!Array.isArray(value)) return undefined;
      const wanted = step.value.toLowerCase();
      value = value.find((each) => {
        if (each === null || typeof each !== "object") return false;
        // The key may itself be a path: slots[stack.id~=sword].
        const held = valueAt(each, step.filter);
        if (held === undefined || held === null) return false;
        const text = String(held).toLowerCase();
        return step.partial ? text.includes(wanted) : text === wanted;
      });
    }
  }
  return value;
}

/**
 * Replaces ${name.path} by saved values. A string that is only a reference
 * keeps the value's type. References nest, innermost first, so that one saved
 * value can pick from another: ${wallet.coins[item=${counter.offers[0].buy.id}].units}.
 */
function substitute(value, saved) {
  if (typeof value === "string") {
    let text = value;
    for (let round = 0; round < 8; round++) {
      const whole = /^\$\{([^${}]+)\}$/.exec(text);
      if (whole) return resolve(whole[1], saved);
      let replaced = false;
      const next = text.replace(/\$\{([^${}]+)\}/g, (all, reference) => {
        const found = resolve(reference, saved);
        if (found === undefined) return all;
        replaced = true;
        return typeof found === "object" ? JSON.stringify(found) : String(found);
      });
      if (!replaced) return next;
      text = next;
    }
    return text;
  }
  if (Array.isArray(value)) return value.map((each) => substitute(each, saved));
  if (value && typeof value === "object") {
    const out = {};
    for (const [key, each] of Object.entries(value)) out[key] = substitute(each, saved);
    return out;
  }
  return value;
}

/** -1, 0 or 1: "1.20.1" against "1.20.5", number by number, a missing one being zero. */
function compareVersions(a, b) {
  const left = String(a).split(".").map(Number);
  const right = String(b).split(".").map(Number);
  for (let index = 0; index < Math.max(left.length, right.length); index++) {
    const difference = (left[index] || 0) - (right[index] || 0);
    if (difference) return difference < 0 ? -1 : 1;
  }
  return 0;
}

const VERSION_KEY = /^mc(<=|>=|<|>|=)(\d+(?:\.\d+)*)$/;

/**
 * A value that depends on the game's version:
 *
 *   { "mc<1.20.5": "give @s diamond_sword{Enchantments:[…]}", "else": "give @s diamond_sword[enchantments={…}]" }
 *
 * The mod hides what differs between versions of the game from a scenario, but a scenario
 * also speaks to the game directly, in commands, and their language changed: an item's data
 * was NBT before 1.20.5 and is components since. An object whose every key is such a test,
 * or "else", is replaced by the first that holds. With no game to ask, "else".
 */
function forVersion(value, minecraft) {
  if (Array.isArray(value)) return value.map((each) => forVersion(each, minecraft));
  if (!value || typeof value !== "object") return value;
  const keys = Object.keys(value);
  if (keys.length && keys.every((key) => key === "else" || VERSION_KEY.test(key))) {
    for (const key of keys) {
      const test = VERSION_KEY.exec(key);
      if (!test || !minecraft) continue;
      const compared = compareVersions(minecraft, test[2]);
      const holds = { "<": compared < 0, "<=": compared <= 0, ">": compared > 0, ">=": compared >= 0, "=": compared === 0 }[test[1]];
      if (holds) return forVersion(value[key], minecraft);
    }
    return forVersion(value.else, minecraft);
  }
  const out = {};
  for (const key of keys) out[key] = forVersion(value[key], minecraft);
  return out;
}

function resolve(reference, saved) {
  const trimmed = reference.trim();
  return trimmed[0] === "=" ? evaluate(trimmed.slice(1), saved) : lookup(trimmed, saved);
}

const FUNCTIONS = { min: Math.min, max: Math.max, floor: Math.floor, ceil: Math.ceil, round: Math.round, abs: Math.abs };

/**
 * A sum over saved values: + - * / %, brackets, min max floor ceil round abs.
 * Read by hand rather than handed to eval: a scenario is a file somebody
 * downloaded, and running one must not be running its author's code.
 */
function evaluate(expression, saved) {
  const text = String(expression);
  let at = 0;
  const fail = (what) => { throw new Error(`${what} in "${text.trim()}" at ${at}`); };
  const skip = () => { while (at < text.length && /\s/.test(text[at])) at++; };
  const eat = (symbol) => { skip(); if (text[at] === symbol) { at++; return true; } return false; };

  function sum() {
    let value = product();
    for (;;) {
      if (eat("+")) value += product();
      else if (eat("-")) value -= product();
      else return value;
    }
  }
  function product() {
    let value = unary();
    for (;;) {
      if (eat("*")) value *= unary();
      else if (eat("/")) value /= unary();
      else if (eat("%")) value %= unary();
      else return value;
    }
  }
  function unary() {
    if (eat("-")) return -unary();
    if (eat("+")) return unary();
    return atom();
  }
  function atom() {
    skip();
    if (eat("(")) {
      const value = sum();
      if (!eat(")")) fail("a ) is missing");
      return value;
    }
    const number = /^\d+(\.\d+)?/.exec(text.slice(at));
    if (number) {
      at += number[0].length;
      return Number(number[0]);
    }
    const name = /^[A-Za-z_]\w*/.exec(text.slice(at));
    if (!name) fail(at >= text.length ? "something is missing" : `cannot read "${text[at]}"`);
    at += name[0].length;
    if (Object.hasOwn(FUNCTIONS, name[0]) && text[at] === "(") {
      at++;
      const given = [sum()];
      while (eat(",")) given.push(sum());
      if (!eat(")")) fail("a ) is missing");
      return FUNCTIONS[name[0]](...given);
    }
    // The rest of a reference: .key, [selector] and #, with no spaces outside brackets.
    let reference = name[0];
    for (;;) {
      const part = /^(\.[A-Za-z_][\w]*|\[[^\]]*\]|#)/.exec(text.slice(at));
      if (!part) break;
      reference += part[0];
      at += part[0].length;
    }
    const found = lookup(reference, saved);
    const value = typeof found === "string" && found.trim() !== "" ? Number(found) : found;
    if (typeof value !== "number" || Number.isNaN(value)) fail(`${reference} is ${JSON.stringify(found)}, not a number`);
    return value;
  }

  const value = sum();
  skip();
  if (at < text.length) fail(`cannot read "${text[at]}"`);
  // 0.1 + 0.2 should compare equal to 0.3 in an expectation.
  return Number(value.toPrecision(12));
}

function lookup(reference, saved) {
  const dot = reference.search(/[.[#]/);
  const name = dot < 0 ? reference : reference.slice(0, dot);
  if (!(name in saved)) throw new Error(`nothing was saved as "${name}"`);
  return valueAt(saved[name], dot < 0 ? "" : reference.slice(dot));
}

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * Checks one expectation against an answer.
 *
 * @returns {string|null} what was wrong, or null
 */
function check(expectation, result) {
  const actual = valueAt(result, expectation.path);
  const at = expectation.path ? `"${expectation.path}"` : "the answer";
  const shown = JSON.stringify(actual);
  if ("exists" in expectation) {
    const exists = actual !== undefined && actual !== null;
    if (exists !== Boolean(expectation.exists)) return `${at} ${exists ? "exists" : "does not exist"}`;
  }
  if ("equals" in expectation && !same(actual, expectation.equals)) {
    return `${at} is ${shown}, expected ${JSON.stringify(expectation.equals)}`;
  }
  if ("not" in expectation && same(actual, expectation.not)) return `${at} is ${shown}, which it should not be`;
  if ("contains" in expectation) {
    const held = Array.isArray(actual) ? actual.some((each) => same(each, expectation.contains))
      : String(actual === undefined ? "" : actual).toLowerCase().includes(String(expectation.contains).toLowerCase());
    if (!held) return `${at} is ${shown}, which does not contain ${JSON.stringify(expectation.contains)}`;
  }
  if ("matches" in expectation && !new RegExp(expectation.matches, "i").test(String(actual))) {
    return `${at} is ${shown}, which does not match /${expectation.matches}/`;
  }
  for (const [name, holds] of [["gt", (a, b) => a > b], ["gte", (a, b) => a >= b],
    ["lt", (a, b) => a < b], ["lte", (a, b) => a <= b]]) {
    if (name in expectation && !(typeof actual === "number" && holds(actual, Number(expectation[name])))) {
      return `${at} is ${shown}, expected ${name} ${expectation[name]}`;
    }
  }
  return null;
}

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

/** One step, once. Returns what was wrong with it, as a list that is empty when nothing was. */
async function attempt(puppet, side, step, saved, entry, options = {}) {
  try {
    const args = substitute(step.args || {}, saved);
    const result = await puppet.call(side, step.op, args);
    // Saved before it is checked, so that a step can compare two parts of its own answer:
    // "text_w" lt "${fit.widgets[0].w}" on the step that saves "fit".
    if (step.save) saved[step.save] = result;
    const problems = [];
    for (const expectation of step.expect || []) {
      const problem = check(substitute(expectation, saved), result);
      if (problem) problems.push(problem);
    }
    if (step.golden) {
      const problem = compareGolden(step.golden, result, options, entry);
      if (problem) problems.push(problem);
    }
    if (step.expect_error !== undefined) problems.push(`answered, where it should have been refused with "${step.expect_error}"`);
    // Shown whether it passed or not: what a failed step saw is the first thing anyone asks.
    if (step.show) entry.shown = valueAt(result, step.show === true ? "" : step.show);
    return problems;
  } catch (failure) {
    // A step may be meant to be refused: {"expect_error": "part of the message"}.
    if (step.expect_error !== undefined
        && failure.message.toLowerCase().includes(String(step.expect_error).toLowerCase())) {
      return [];
    }
    return [failure.message];
  }
}

/**
 * A screenshot against a kept one: {"golden": {"file": "golden/trade.png",
 * "max_percent": 0.5, "tolerance": 16, "region": {x, y, w, h}}} on a step that
 * answers with a "path", which screenshot does. The file is beside the
 * scenario. Missing, or with updateGolden, it is written from what was seen,
 * and the step passes: the first run makes the goldens, a person looks at
 * them once, and from then on a program does.
 *
 * A golden holds for one window size, GUI scale and language. Set the window
 * in setup, and compare a region when the world shows behind the screen.
 *
 * A scenario is a file somebody downloaded, and "file" is the one place in it
 * that names where to write. So it names a .png under the scenario's own
 * folder or it names nothing: "../../.ssh/authorized_keys" with --update-golden
 * was a file overwritten, and an absolute path into a Startup folder a file
 * planted, before the review that found this.
 */
function compareGolden(golden, result, options, entry) {
  const fs = options.fs || require("fs");
  const path = require("path");
  const png = require("./png");
  const taken = result && result.path;
  if (!taken) return "\"golden\" is for a step that answers with a \"path\", as screenshot does";
  const base = path.resolve(options.baseDir || ".");
  if (typeof golden.file !== "string" || !/\.png$/i.test(golden.file)) {
    return "\"golden\" needs a \"file\" ending in .png";
  }
  const kept = path.resolve(base, golden.file);
  const within = path.relative(base, kept);
  if (!within || within.startsWith("..") || path.isAbsolute(within)) {
    return `the golden "${golden.file}" is not under the scenario's own folder, and nothing is written or read outside it`;
  }
  if (typeof taken !== "string" || !/\.png$/i.test(taken)) {
    return "the step's \"path\" is not a .png, so there is nothing to keep as a golden";
  }
  if (options.updateGolden || !fs.existsSync(kept)) {
    fs.mkdirSync(path.dirname(kept), { recursive: true });
    fs.copyFileSync(taken, kept);
    entry.golden = { written: kept };
    return null;
  }
  let outcome;
  try {
    outcome = png.diff(png.decode(fs.readFileSync(kept)), png.decode(fs.readFileSync(taken)), golden);
  } catch (unreadable) {
    return `the screenshot could not be compared with ${golden.file}: ${unreadable.message}`;
  }
  const allowed = golden.max_percent === undefined ? 0.5 : Number(golden.max_percent);
  entry.golden = { file: golden.file, percent: outcome.percent, different: outcome.different };
  if (!outcome.same_size) return `the screenshot is not the size of ${golden.file}: ${outcome.says}. Set the window in setup`;
  if (outcome.percent <= allowed) return null;
  const marked = taken.replace(/\.png$/i, "") + ".diff.png";
  try {
    fs.writeFileSync(marked, png.encode(outcome.image));
    entry.golden.diff = marked;
  } catch (unwritable) {
    // The number is the finding; the picture is a help.
  }
  return `the screenshot differs from ${golden.file} in ${outcome.percent}% of pixels (allowed ${allowed}%); see ${marked}`;
}

const EVENTUALLY_MS = 10000;
const ASK_AGAIN_MS = 100;

async function runStep(puppet, step, saved, options, entry) {
  const side = step.side || options.defaultSide || "client";
  const started = Date.now();
  if (step.let) {
    // Names for what the steps after it keep saying: {"let": {"price": "${= offer.count * coin.units}"}}.
    try {
      for (const [name, value] of Object.entries(step.let)) saved[name] = substitute(value, saved);
      const named = Object.fromEntries(Object.keys(step.let).map((name) => [name, saved[name]]));
      // What was worked out can be expected of, like any answer: {"path": "change", "gte": 0}.
      const problems = [];
      for (const expectation of step.expect || []) {
        const problem = check(substitute(expectation, saved), named);
        if (problem) problems.push(problem);
      }
      entry.ok = problems.length === 0;
      if (!entry.ok) entry.problems = problems;
      if (step.show || !entry.ok) entry.shown = named;
    } catch (failure) {
      entry.ok = false;
      entry.problems = [failure.message];
    }
    entry.ms = Date.now() - started;
    return entry;
  }
  let problems = await attempt(puppet, side, step, saved, entry, options);
  if (step.eventually) {
    const patience = step.eventually === true ? EVENTUALLY_MS : Number(step.eventually);
    let asked = 1;
    // "every_ms" for an operation that is work for the game to answer: asked ten times a
    // second, a costly one slows the very thing being waited for.
    const pause = step.every_ms ? Math.max(ASK_AGAIN_MS, Number(step.every_ms)) : ASK_AGAIN_MS;
    while (problems.length && Date.now() - started < patience && !gameIsGone(problems)) {
      await sleep(pause);
      problems = await attempt(puppet, side, step, saved, entry, options);
      asked++;
    }
    entry.asked = asked;
  }
  entry.ok = problems.length === 0;
  if (!entry.ok) entry.problems = problems;
  entry.ms = Date.now() - started;
  if (!entry.ok && step.optional) {
    // Tried, not needed: a confirmation that may or may not come up.
    entry.ok = true;
    entry.skipped = true;
  }
  return entry;
}

const gameIsGone = (problems) => problems.some((problem) => /closed the connection|no running game|ECONNRE/.test(problem));

/**
 * What a game wrote to its log since a moment, that a test should not pass over.
 *
 * Reads <gameDir>/logs/latest.log from where it ended when the run began. A
 * log that begins differently is a game that restarted, and is read from its
 * start; its length says nothing, a new log soon being longer than the old.
 */
const HEAD_BYTES = 96;

class LogWatch {
  constructor(files, fs = require("fs")) {
    this.fs = fs;
    this.marks = files.map((file) => ({ file, size: this.sizeOf(file), head: this.read(file, 0, HEAD_BYTES) }));
  }

  read(file, start, length) {
    try {
      const handle = this.fs.openSync(file, "r");
      try {
        const buffer = Buffer.alloc(Math.max(0, Math.min(length, this.sizeOf(file) - start)));
        this.fs.readSync(handle, buffer, 0, buffer.length, start);
        return buffer.toString("utf8");
      } finally {
        this.fs.closeSync(handle);
      }
    } catch (unreadable) {
      return "";
    }
  }

  sizeOf(file) {
    try {
      return this.fs.statSync(file).size;
    } catch (absent) {
      return 0;
    }
  }

  /** @returns {{file: string, line: string}[]} */
  problems(allowed = []) {
    const allow = allowed.map((each) => new RegExp(each, "i"));
    const found = [];
    for (const mark of this.marks) {
      const size = this.sizeOf(mark.file);
      const restarted = size < mark.size || this.read(mark.file, 0, mark.head.length) !== mark.head;
      const start = restarted ? 0 : mark.size;
      const text = this.read(mark.file, start, size - start);
      for (const line of problemLines(text)) {
        if (!allow.some((each) => each.test(line))) found.push({ file: mark.file, line });
      }
    }
    return found;
  }
}

/** The lines of a log that say something went wrong: an ERROR or FATAL, or the head of a stack trace. */
function problemLines(text) {
  const found = [];
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    const logged = /\/(ERROR|FATAL)\]/.test(line);
    // An exception printed past the logger has no level; its "at …" lines give it away.
    const thrown = /^\S*(Exception|Error)\b/.test(line) && /^\s+at\s/.test(lines[index + 1] || "");
    if (logged || thrown) found.push(line.trim().slice(0, 400));
  }
  return found;
}

/**
 * Runs a scenario.
 *
 * @param {import("./lib").Puppet} puppet
 * @param {{name?: string, setup?: object[], steps: object[], teardown?: object[], allow_log?: string[]}} scenario
 * @param {{keepGoing?: boolean, defaultSide?: string, watchLog?: boolean, baseDir?: string, updateGolden?: boolean}} options
 * @returns {Promise<{name: string, ok: boolean, passed: number, failed: number, steps: object[], saved: object}>}
 */
async function run(puppet, scenario, options = {}) {
  const saved = {};
  // Asked once, of whichever side answers: both sides of one game are one version.
  let minecraft = options.minecraft;
  if (!minecraft) {
    for (const side of ["client", "server"]) {
      try {
        minecraft = (await puppet.call(side, "info")).minecraft;
        if (minecraft) break;
      } catch (absent) {
        // Not that side, then.
      }
    }
  }
  const chosen = (steps) => (steps || []).map((step) => forVersion(step, minecraft));
  scenario = { ...scenario, setup: chosen(scenario.setup), steps: chosen(scenario.steps), teardown: chosen(scenario.teardown) };
  const report = [];
  let failed = 0;
  let number = 0;
  let watch = null;
  if (options.watchLog !== false && typeof puppet.endpoints === "function") {
    const path = require("path");
    const dirs = [...new Set(puppet.endpoints().map((endpoint) => endpoint.dir))];
    watch = new LogWatch(dirs.map((dir) => path.join(dir, "logs", "latest.log")));
  }

  async function phase(name, steps, keepGoing) {
    let clean = true;
    for (const step of steps || []) {
      const entry = step.let ? { step: ++number, side: "-", op: "let" }
        : { step: ++number, side: step.side || options.defaultSide || "client", op: step.op };
      if (name !== "steps") entry.phase = name;
      if (step.note) entry.note = step.note;
      report.push(await runStep(puppet, step, saved, options, entry));
      if (!entry.ok) {
        failed++;
        clean = false;
        if (!keepGoing) break;
      }
    }
    return clean;
  }

  try {
    // What was not set up cannot be tested; what was set up is still torn down.
    if (await phase("setup", scenario.setup, false)) await phase("steps", scenario.steps, options.keepGoing);
  } finally {
    // Every step of a teardown is tried: the second may succeed where the first could not.
    await phase("teardown", scenario.teardown, true);
  }

  const logProblems = watch ? watch.problems(scenario.allow_log) : [];
  const total = (scenario.setup || []).length + scenario.steps.length + (scenario.teardown || []).length;
  return {
    name: scenario.name || "scenario",
    ok: failed === 0 && logProblems.length === 0,
    passed: report.filter((entry) => entry.ok).length,
    failed,
    ran: report.length,
    of: total,
    steps: report,
    log_problems: logProblems,
    saved,
  };
}

module.exports = { parsePath, valueAt, substitute, evaluate, check, run, LogWatch, problemLines, compareGolden, forVersion, compareVersions };
