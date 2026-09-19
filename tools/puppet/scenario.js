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
 * first element that matches, and a# for how many there are. "${name.path}"
 * anywhere in args or in an expectation is replaced by a value saved earlier.
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
        const held = each[step.filter];
        if (held === undefined || held === null) return false;
        const text = String(held).toLowerCase();
        return step.partial ? text.includes(wanted) : text === wanted;
      });
    }
  }
  return value;
}

/** Replaces ${name.path} by saved values. A string that is only a reference keeps the value's type. */
function substitute(value, saved) {
  if (typeof value === "string") {
    const whole = /^\$\{([^}]+)\}$/.exec(value);
    if (whole) return lookup(whole[1], saved);
    return value.replace(/\$\{([^}]+)\}/g, (all, reference) => {
      const found = lookup(reference, saved);
      return found === undefined ? all : typeof found === "object" ? JSON.stringify(found) : String(found);
    });
  }
  if (Array.isArray(value)) return value.map((each) => substitute(each, saved));
  if (value && typeof value === "object") {
    const out = {};
    for (const [key, each] of Object.entries(value)) out[key] = substitute(each, saved);
    return out;
  }
  return value;
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

/**
 * Runs a scenario.
 *
 * @param {import("./lib").Puppet} puppet
 * @param {{name?: string, steps: object[]}} scenario
 * @param {{keepGoing?: boolean, defaultSide?: string}} options
 * @returns {Promise<{name: string, ok: boolean, passed: number, failed: number, steps: object[], saved: object}>}
 */
async function run(puppet, scenario, options = {}) {
  const saved = {};
  const report = [];
  let failed = 0;
  for (let index = 0; index < scenario.steps.length; index++) {
    const step = scenario.steps[index];
    const side = step.side || options.defaultSide || "client";
    const entry = { step: index + 1, side, op: step.op };
    if (step.note) entry.note = step.note;
    const started = Date.now();
    try {
      const args = substitute(step.args || {}, saved);
      const result = await puppet.call(side, step.op, args);
      if (step.save) saved[step.save] = result;
      const problems = [];
      for (const expectation of step.expect || []) {
        const problem = check(substitute(expectation, saved), result);
        if (problem) problems.push(problem);
      }
      entry.ok = problems.length === 0;
      if (!entry.ok) entry.problems = problems;
      if (step.show) entry.shown = valueAt(result, step.show === true ? "" : step.show);
    } catch (failure) {
      // A step may be meant to be refused: {"expect_error": "part of the message"}.
      if (step.expect_error !== undefined
          && failure.message.toLowerCase().includes(String(step.expect_error).toLowerCase())) {
        entry.ok = true;
      } else {
        entry.ok = false;
        entry.problems = [failure.message];
      }
    }
    entry.ms = Date.now() - started;
    if (!entry.ok && step.optional) {
      // Tried, not needed: a confirmation that may or may not come up.
      entry.ok = true;
      entry.skipped = true;
    }
    report.push(entry);
    if (!entry.ok) {
      failed++;
      if (!options.keepGoing) break;
    }
  }
  return {
    name: scenario.name || "scenario",
    ok: failed === 0,
    passed: report.filter((entry) => entry.ok).length,
    failed,
    ran: report.length,
    of: scenario.steps.length,
    steps: report,
    saved,
  };
}

module.exports = { parsePath, valueAt, substitute, check, run };
