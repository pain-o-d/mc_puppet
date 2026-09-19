// node --test tools/puppet/scenario.test.js
const test = require("node:test");
const assert = require("node:assert");
const { valueAt, substitute, check, run, parsePath, evaluate, LogWatch, problemLines } = require("./scenario");

const screen = {
  title: "Librarian",
  widgets: [
    { index: 0, kind: "button", text: "Currency: Emeralds", x: 200, y: 10 },
    { index: 1, kind: "button", text: "Done", x: 5, y: 5, visible: false },
  ],
  offers: [
    { index: 0, buy: { id: "minecraft:emerald", count: 9 }, sell: { id: "minecraft:bookshelf", count: 1 }, uses: 0 },
    { index: 1, buy: { id: "minecraft:paper", count: 24 }, sell: { id: "minecraft:emerald", count: 1 }, uses: 3 },
  ],
  cursor: null,
};

test("a path walks keys, indexes, filters and counts", () => {
  assert.equal(valueAt(screen, "title"), "Librarian");
  assert.equal(valueAt(screen, "offers[1].buy.count"), 24);
  assert.equal(valueAt(screen, "offers[-1].uses"), 3);
  assert.equal(valueAt(screen, "offers#"), 2);
  assert.equal(valueAt(screen, "widgets[text~=currency].x"), 200);
  assert.equal(valueAt(screen, "widgets[text=Done].visible"), false);
  assert.equal(valueAt(screen, "offers[index=1].sell.id"), "minecraft:emerald");
  assert.equal(valueAt(screen, "offers[sell.id~=bookshelf].buy.count"), 9);
  assert.equal(valueAt(screen, "offers[sell.nothing=1]"), undefined);
  assert.deepEqual(valueAt(screen, ""), screen);
});

test("a path that leads nowhere is undefined, not an exception", () => {
  assert.equal(valueAt(screen, "nothing.here[3].at"), undefined);
  assert.equal(valueAt(screen, "widgets[text~=absent].x"), undefined);
  assert.equal(valueAt(screen, "cursor.id"), undefined);
  assert.equal(valueAt(screen, "title[0]"), undefined);
});

test("a path that cannot be read says so", () => {
  assert.throws(() => parsePath("offers[oops"), /unclosed/);
  assert.throws(() => parsePath("offers[one two]"), /neither an index/);
});

test("a filter value may hold spaces and dots", () => {
  const list = { items: [{ name: "Buy it. Now", n: 1 }] };
  assert.equal(valueAt(list, "items[name=Buy it. Now].n"), 1);
});

test("expectations say what was wrong, in words", () => {
  assert.equal(check({ path: "offers#", gte: 2 }, screen), null);
  assert.match(check({ path: "offers#", gt: 2 }, screen), /is 2, expected gt 2/);
  assert.equal(check({ path: "widgets[text~=Currency]", exists: true }, screen), null);
  assert.match(check({ path: "widgets[text~=Nope]", exists: true }, screen), /does not exist/);
  assert.equal(check({ path: "cursor", exists: false }, screen), null);
  assert.equal(check({ path: "offers[0].buy", equals: { id: "minecraft:emerald", count: 9 } }, screen), null);
  assert.match(check({ path: "offers[0].buy.count", equals: 10 }, screen), /is 9, expected 10/);
  assert.match(check({ path: "offers[0].buy.id", not: "minecraft:emerald" }, screen), /should not be/);
  assert.equal(check({ path: "title", contains: "libr" }, screen), null);
  assert.equal(check({ path: "title", matches: "^lib.*an$" }, screen), null);
  assert.match(check({ path: "title", gte: 1 }, screen), /expected gte 1/);
});

test("saved answers are reused, and a lone reference keeps its type", () => {
  const saved = { before: screen };
  assert.equal(substitute("${before.offers[0].buy.count}", saved), 9);
  assert.equal(substitute("was ${before.offers[0].buy.id}", saved), "was minecraft:emerald");
  assert.deepEqual(substitute({ slot: "${before.offers#}", keep: [1, "${before.title}"] }, saved),
    { slot: 2, keep: [1, "Librarian"] });
  assert.throws(() => substitute("${missing.x}", saved), /nothing was saved/);
});

/** A game that answers from a script, so the runner is tested without one. */
function fake(answers) {
  const calls = [];
  return {
    calls,
    async call(side, op, args) {
      calls.push({ side, op, args });
      const answer = answers[op];
      if (answer instanceof Error) throw answer;
      return typeof answer === "function" ? answer(args) : answer;
    },
  };
}

test("a scenario stops at the first failure and says how far it got", async () => {
  const puppet = fake({ screen, click: new Error("no visible widget says \"Pay\"") });
  const report = await run(puppet, {
    name: "stops",
    steps: [
      { op: "screen", expect: [{ path: "offers#", equals: 2 }] },
      { op: "click", args: { text: "Pay" } },
      { op: "screen" },
    ],
  });
  assert.equal(report.ok, false);
  assert.equal(report.ran, 2);
  assert.equal(report.of, 3);
  assert.match(report.steps[1].problems[0], /no visible widget/);
  assert.equal(puppet.calls.length, 2);
});

test("keep_going runs the rest; an optional step never fails a scenario", async () => {
  const puppet = fake({ screen, click: new Error("nothing to click") });
  const kept = await run(puppet, { steps: [{ op: "click" }, { op: "screen" }] }, { keepGoing: true });
  assert.equal(kept.ran, 2);
  assert.equal(kept.failed, 1);
  const optional = await run(puppet, { steps: [{ op: "click", optional: true }, { op: "screen" }] });
  assert.equal(optional.ok, true);
  assert.equal(optional.steps[0].skipped, true);
});

test("a step can be meant to be refused", async () => {
  const puppet = fake({ choose: new Error("that currency was never offered") });
  const report = await run(puppet, { steps: [{ side: "server", op: "choose", expect_error: "never offered" }] });
  assert.equal(report.ok, true);
  assert.equal(puppet.calls[0].side, "server");
});

test("one step's answer feeds the next step's arguments and expectations", async () => {
  let offers = screen.offers;
  const puppet = fake({
    screen: () => ({ ...screen, offers }),
    select_trade: (args) => { offers = [{ ...offers[0], buy: { id: "saros:euro", count: args.index + 5 } }]; return offers[0]; },
  });
  const report = await run(puppet, {
    steps: [
      { op: "screen", save: "before" },
      { op: "select_trade", args: { index: "${before.offers#}" } },
      { op: "screen", show: "offers[0].buy", expect: [
        { path: "offers[0].buy.id", not: "${before.offers[0].buy.id}" },
        { path: "offers[0].buy.count", equals: 7 }] },
    ],
  });
  assert.equal(report.ok, true, JSON.stringify(report.steps));
  assert.deepEqual(puppet.calls[1].args, { index: 2 });
  assert.deepEqual(report.steps[2].shown, { id: "saros:euro", count: 7 });
});

test("a sum over saved values, read by hand and never by eval", () => {
  const saved = { before: { count: 40, offers: [{ price: 7 }] }, paid: "3" };
  assert.equal(evaluate("before.count - 3 * before.offers[0].price", saved), 19);
  assert.equal(evaluate("(before.count - 4) / paid", saved), 12);
  assert.equal(evaluate("-before.count % 7", saved), -5);
  assert.equal(evaluate("min(before.count, 9) + floor(7 / 2) + abs(-1)", saved), 13);
  assert.equal(evaluate("before.offers# + 0.1 + 0.2", saved), 1.3);
  assert.equal(substitute("${= before.count - 1}", saved), 39);
  assert.equal(substitute("has ${= before.count * 2} left", saved), "has 80 left");
  assert.deepEqual(substitute({ equals: "${=before.offers[-1].price+1}" }, saved), { equals: 8 });
  assert.throws(() => evaluate("before.count +", saved), /missing/);
  assert.throws(() => evaluate("before.offers", saved), /not a number/);
  assert.throws(() => evaluate("process.exit(1)", saved), /nothing was saved as "process"/);
  assert.throws(() => evaluate("1; 2", saved), /cannot read/);
});

/** A game that answers from a script, one answer per call. */
function scripted(answers) {
  const asked = [];
  return {
    asked,
    call: async (side, op, args) => {
      asked.push({ side, op, args });
      const next = answers[op];
      const answer = Array.isArray(next) ? (next.length > 1 ? next.shift() : next[0]) : next;
      if (answer instanceof Error) throw answer;
      return answer;
    },
  };
}

test("teardown runs whatever happened, and setup that failed skips the steps", async () => {
  const puppet = scripted({ give: {}, look: { count: 1 }, clear: {}, never: new Error("no such thing") });
  const failedStep = await run(puppet, {
    setup: [{ op: "give" }],
    steps: [{ op: "look", expect: [{ path: "count", equals: 2 }] }, { op: "look" }],
    teardown: [{ op: "never" }, { op: "clear" }],
  });
  assert.equal(failedStep.ok, false);
  assert.deepEqual(puppet.asked.map((each) => each.op), ["give", "look", "never", "clear"]);
  assert.equal(failedStep.steps[3].phase, "teardown");
  assert.equal(failedStep.of, 5);

  const second = scripted({ clear: {}, never: new Error("no such thing") });
  const failedSetup = await run(second, { setup: [{ op: "never" }], steps: [{ op: "look" }], teardown: [{ op: "clear" }] });
  assert.equal(failedSetup.ok, false);
  assert.deepEqual(second.asked.map((each) => each.op), ["never", "clear"]);
});

test("eventually asks again until the expectations hold, and gives up in time", async () => {
  const puppet = scripted({ look: [{ count: 0 }, { count: 0 }, { count: 5 }] });
  const report = await run(puppet, { steps: [{ op: "look", eventually: 2000, save: "seen",
    expect: [{ path: "count", gte: 5 }] }] });
  assert.equal(report.ok, true);
  assert.equal(report.steps[0].asked, 3);
  assert.equal(report.saved.seen.count, 5);

  const never = await run(scripted({ look: { count: 0 } }),
    { steps: [{ op: "look", eventually: 250, expect: [{ path: "count", gte: 5 }] }] });
  assert.equal(never.ok, false);
  assert.match(never.steps[0].problems[0], /expected gte 5/);
});

test("a step that should be refused fails when it is answered", async () => {
  const report = await run(scripted({ look: {} }), { steps: [{ op: "look", expect_error: "no screen" }] });
  assert.equal(report.ok, false);
});

test("the lines of a log that say something went wrong", () => {
  const log = [
    "[12:00:01] [Render thread/INFO] (Minecraft) Stopping!",
    "[12:00:02] [Server thread/ERROR] (get_rich) Could not price minecraft:cake",
    "[12:00:03] [Render thread/WARN] (Minecraft) Something about an Error in a name",
    "java.lang.IllegalStateException: boom",
    "\tat com.example.Thing.run(Thing.java:12)",
    "[12:00:04] [main/FATAL] (mixin) Mixin apply failed",
  ].join("\n");
  assert.deepEqual(problemLines(log), [
    "[12:00:02] [Server thread/ERROR] (get_rich) Could not price minecraft:cake",
    "java.lang.IllegalStateException: boom",
    "[12:00:04] [main/FATAL] (mixin) Mixin apply failed",
  ]);
});

test("a log is read from where it ended when the run began, and known errors can be let by", () => {
  const files = { "a.log": "[x] [t/ERROR] old\n" };
  const fake = {
    statSync: (file) => ({ size: Buffer.byteLength(files[file]) }),
    openSync: (file) => file,
    readSync: (file, buffer, offset, length, position) => Buffer.from(files[file]).copy(buffer, 0, position, position + length),
    closeSync: () => {},
  };
  const watch = new LogWatch(["a.log"], fake);
  assert.deepEqual(watch.problems(), []);
  files["a.log"] += "[x] [t/INFO] fine\n[x] [t/ERROR] new one\n[x] [t/ERROR] known: narrator\n";
  assert.deepEqual(watch.problems().map((each) => each.line), ["[x] [t/ERROR] new one", "[x] [t/ERROR] known: narrator"]);
  assert.deepEqual(watch.problems(["narrator"]).map((each) => each.line), ["[x] [t/ERROR] new one"]);
  // Shorter than it was: the game restarted, and the whole of the new log counts.
  files["a.log"] = "[x] [t/ERROR] after restart\n";
  assert.deepEqual(watch.problems().map((each) => each.line), ["[x] [t/ERROR] after restart"]);
});

test("a PNG survives being written and read, whatever filter its lines use", () => {
  const png = require("./png");
  const zlib = require("zlib");
  const image = { width: 3, height: 2, data: Buffer.from([
    255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255,
    10, 20, 30, 255, 40, 50, 60, 255, 70, 80, 90, 128]) };
  assert.deepEqual(png.decode(png.encode(image)), image);
  assert.throws(() => png.decode(Buffer.from("not a png at all")), /not a PNG/);

  // A hand-made RGB image whose second line uses the Paeth filter against the first.
  const lines = Buffer.from([0, 10, 20, 30, 40, 50, 60, 4, 1, 1, 1, 1, 1, 1]);
  const header = Buffer.alloc(13);
  header.writeUInt32BE(2, 0);
  header.writeUInt32BE(2, 4);
  header[8] = 8;
  header[9] = 2;
  const chunkOf = (type, body) => {
    const out = Buffer.alloc(12 + body.length);
    out.writeUInt32BE(body.length, 0);
    out.write(type, 4, "ascii");
    body.copy(out, 8);
    return out; // the decoder does not check a CRC: the game wrote the file a moment ago
  };
  const file = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunkOf("IHDR", header), chunkOf("IDAT", zlib.deflateSync(lines)), chunkOf("IEND", Buffer.alloc(0))]);
  const decoded = png.decode(file);
  assert.deepEqual([...decoded.data], [10, 20, 30, 255, 40, 50, 60, 255, 11, 21, 31, 255, 41, 51, 61, 255]);
});

test("two screenshots are compared within a tolerance, in a region, and the difference is drawn", () => {
  const png = require("./png");
  const blank = (width, height, value) => ({ width, height, data: Buffer.alloc(width * height * 4, value) });
  const first = blank(10, 10, 100);
  const second = blank(10, 10, 104);
  assert.equal(png.diff(first, second).different, 0, "a dither is not a difference");
  for (let pixel = 0; pixel < 5; pixel++) second.data[pixel * 4] = 255;
  const outcome = png.diff(first, second);
  assert.equal(outcome.different, 5);
  assert.equal(outcome.percent, 5);
  assert.deepEqual([...outcome.image.data.subarray(0, 4)], [255, 0, 0, 255]);
  assert.equal(png.diff(first, second, { region: { x: 0, y: 1, w: 10, h: 9 } }).different, 0);
  assert.equal(png.diff(first, second, { tolerance: 200 }).different, 0);
  assert.equal(png.diff(first, blank(10, 11, 100)).same_size, false);
});

test("a golden is written the first time and compared from then on", () => {
  const fsReal = require("fs");
  const os = require("os");
  const pathOf = require("path");
  const png = require("./png");
  const { compareGolden } = require("./scenario");
  const dir = fsReal.mkdtempSync(pathOf.join(os.tmpdir(), "puppet-golden-"));
  try {
    const shot = pathOf.join(dir, "shot.png");
    const image = { width: 8, height: 8, data: Buffer.alloc(8 * 8 * 4, 50) };
    fsReal.writeFileSync(shot, png.encode(image));
    const golden = { file: "golden/shot.png", max_percent: 1 };
    const first = {};
    assert.equal(compareGolden(golden, { path: shot }, { baseDir: dir }, first), null);
    assert.ok(first.golden.written.endsWith("shot.png"));
    assert.equal(compareGolden(golden, { path: shot }, { baseDir: dir }, {}), null);

    image.data.fill(250, 0, 8 * 4 * 4);
    fsReal.writeFileSync(shot, png.encode(image));
    const entry = {};
    const problem = compareGolden(golden, { path: shot }, { baseDir: dir }, entry);
    assert.match(problem, /differs from golden\/shot.png in 50% of pixels/);
    assert.ok(fsReal.existsSync(entry.golden.diff));
    assert.equal(compareGolden(golden, { path: shot }, { baseDir: dir, updateGolden: true }, {}), null);
    assert.equal(compareGolden(golden, { path: shot }, { baseDir: dir }, {}), null);
    assert.match(compareGolden(golden, {}, { baseDir: dir }, {}), /answers with a "path"/);
  } finally {
    fsReal.rmSync(dir, { recursive: true, force: true });
  }
});

test("reports become JUnit XML that a CI can show, whatever the game said", () => {
  const { junitXml } = require("./junit");
  const xml = junitXml([{ name: "buy <apples> & pay", failed: 1, steps: [
    { step: 1, side: "client", op: "screen", ok: true, ms: 12 },
    { step: 2, side: "client", op: "click_widget", ok: true, skipped: true, ms: 1 },
    { step: 3, side: "server", op: "count", ok: false, ms: 5, note: "the server agrees",
      problems: ["the answer is 3, expected 20", "second \u0007 problem"], phase: "teardown" }],
    log_problems: [{ file: "latest.log", line: "[x] [Server thread/ERROR] boom" }] }]);
  assert.match(xml, /<testsuite name="buy &lt;apples&gt; &amp; pay" tests="4" failures="2" skipped="1"/);
  assert.match(xml, /name="2\. client click_widget"[^>]*><skipped\/>/);
  assert.match(xml, /name="3\. server count \(teardown\) - the server agrees"/);
  assert.match(xml, /<failure message="the answer is 3, expected 20">/);
  assert.match(xml, /the game&apos;s log|the game's log/);
  assert.ok(!xml.includes("\u0007"));
});

test("a game can be called by name, and a Windows drive is not a name", () => {
  const { parseSide, named } = require("./lib");
  assert.deepEqual(parseSide("client"), { side: "client", game: undefined });
  assert.deepEqual(parseSide("server@second"), { side: "server", game: "second" });
  assert.deepEqual(named("second=E:/games/two"), { game: "second", root: "E:/games/two" });
  assert.deepEqual(named("E:/games/two"), { root: "E:/games/two" });
  assert.deepEqual(named("C:\\games\\a=b"), { root: "C:\\games\\a=b" });
});

test("launch knows a multi-loader project from a single-loader one", () => {
  const { plan } = require("./launch");
  const here = require("path").resolve(__dirname, "..", "..");
  assert.equal(plan("client", { project: here, loader: "fabric" }).task, ":fabric:runClient");
  assert.equal(plan("server", { project: here, loader: "neoforge" }).task, ":neoforge:runServer");
  assert.equal(plan("client", { project: __dirname, loader: "fabric" }).task, "runClient");
  assert.throws(() => plan("both", { project: here, loader: "fabric" }), /client or server/);
});

test("a step can compare two parts of its own answer", async () => {
  const button = { widgets: [{ text: "Pay in: Coins", w: 76, text_w: 70 }] };
  const step = { op: "screen", save: "fit", expect: [{ path: "widgets[0].text_w", lt: "${fit.widgets[0].w}" }] };
  assert.equal((await run(scripted({ screen: button }), { steps: [step] })).ok, true);
  button.widgets[0].text_w = 104;
  const clipped = await run(scripted({ screen: button }), { steps: [step] });
  assert.equal(clipped.ok, false);
  assert.match(clipped.steps[0].problems[0], /is 104, expected lt 76/);
});

test("references nest, and let gives a name to what the steps after it keep saying", async () => {
  const saved = { counter: { offers: [{ buy: { id: "mod:euro_2", count: 4 } }] },
    wallet: { coins: [{ item: "mod:euro_1", units: 100 }, { item: "mod:euro_2", units: 200 }] } };
  assert.equal(substitute("${wallet.coins[item=${counter.offers[0].buy.id}].units}", saved), 200);
  assert.equal(substitute("costs ${= counter.offers[0].buy.count * wallet.coins[item=${counter.offers[0].buy.id}].units} units", saved),
    "costs 800 units");

  const puppet = scripted({ screen: saved.counter, wallet: saved.wallet, pay: {} });
  const report = await run(puppet, { steps: [
    { op: "screen", save: "counter" },
    { op: "wallet", save: "wallet" },
    { let: { coin: "${wallet.coins[item=${counter.offers[0].buy.id}].units}" } },
    { let: { price: "${= counter.offers[0].buy.count * coin}", trades: "${= min(floor(2000 / (counter.offers[0].buy.count * coin)), 16)}" }, show: true },
    { op: "pay", args: { units: "${price}" } },
    { let: { broken: "${= nothing.here}" } } ] }, { keepGoing: true });
  assert.equal(report.saved.price, 800);
  assert.equal(report.saved.trades, 2);
  assert.deepEqual(puppet.asked[2].args, { units: 800 });
  assert.deepEqual(report.steps[3].shown, { price: 800, trades: 2 });
  assert.equal(report.steps[5].ok, false);
  assert.match(report.steps[5].problems[0], /nothing was saved as "nothing"/);
});

test("what let works out can be expected of, and a failure shows the sums", async () => {
  const report = await run(scripted({}), { steps: [
    { let: { before: 2000, after: 400, price: 800 } },
    { let: { spent: "${= before - after}" }, expect: [{ path: "spent", equals: "${= 2 * price}" }] },
    { let: { lost: "${= before - after - 2 * price - 1}" }, expect: [{ path: "lost", gte: 0 }] } ] }, { keepGoing: true });
  assert.equal(report.steps[1].ok, true);
  assert.equal(report.steps[2].ok, false);
  assert.match(report.steps[2].problems[0], /"lost" is -1, expected gte 0/);
  assert.deepEqual(report.steps[2].shown, { lost: -1 });
});
