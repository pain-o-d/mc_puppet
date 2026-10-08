const { test } = require("node:test");
const assert = require("node:assert/strict");
const { junitXml } = require("./junit");

const step = (over) => ({ step: 1, side: "client", op: "screen", ok: true, ms: 0, ...over });
const report = (over) => ({ name: "a scenario", failed: 0, steps: [], log_problems: [], ...over });

test("a passing step is an empty testcase named by number, side and operation, timed in seconds", () => {
  const xml = junitXml([report({ steps: [step({ ms: 1500 })] })]);
  assert.match(xml, /^<\?xml version="1\.0" encoding="UTF-8"\?>\n<testsuites>\n/);
  assert.match(xml, /<testcase classname="a scenario" name="1\. client screen" time="1\.500"\/>/);
  assert.match(xml, /<testsuite name="a scenario" tests="1" failures="0" skipped="0" time="1\.500">/);
  assert.ok(xml.endsWith("</testsuites>\n"));
});

test("phase and note are part of the case's name", () => {
  const xml = junitXml([report({ steps: [step({ step: 3, phase: "teardown", note: "leave" })] })]);
  assert.ok(xml.includes('name="3. client screen (teardown) - leave"'), xml);
});

test("a failed step carries its first problem as the message and all of them as the body", () => {
  const xml = junitXml([report({ failed: 1, steps: [step({ ok: false, problems: ["first problem", "second problem"] })] })]);
  assert.ok(xml.includes('<failure message="first problem">first problem\nsecond problem</failure>'), xml);
  assert.match(xml, /tests="1" failures="1" skipped="0"/);
});

test("a failed step with no problems listed still says it failed", () => {
  const xml = junitXml([report({ failed: 1, steps: [step({ ok: false })] })]);
  assert.ok(xml.includes('<failure message="failed">failed</failure>'), xml);
});

test("a skipped step is skipped, not failed, and is counted", () => {
  const xml = junitXml([report({ steps: [step({ skipped: true }), step({ step: 2 })] })]);
  assert.ok(xml.includes("><skipped/></testcase>"), xml);
  assert.match(xml, /tests="2" failures="0" skipped="1"/);
});

test("errors the game logged are a case of their own, and count as one more failure", () => {
  const xml = junitXml([report({ steps: [step()], log_problems: [{ line: "[ERROR] boom" }, { line: "[ERROR] bang" }] })]);
  assert.ok(xml.includes(`name="the game's log"`), xml);
  assert.ok(xml.includes('<failure message="2 error(s) logged while this ran">[ERROR] boom\n[ERROR] bang</failure>'), xml);
  assert.match(xml, /tests="2" failures="1"/);
});

test("failures add the failed steps and the log case together", () => {
  const xml = junitXml([report({
    failed: 2,
    steps: [step({ ok: false, problems: ["x"] }), step({ step: 2, ok: false, problems: ["y"] })],
    log_problems: [{ line: "e" }],
  })]);
  assert.match(xml, /tests="3" failures="3"/);
});

test("markup characters in names and messages are escaped, in attributes and in text", () => {
  const xml = junitXml([report({
    name: 'a & "b" <c>',
    failed: 1,
    steps: [step({ ok: false, note: "<x>", problems: ['1 < 2 && "q" > \'r\''] })],
  })]);
  assert.ok(xml.includes('name="a &amp; &quot;b&quot; &lt;c&gt;"'), xml);
  assert.ok(xml.includes('name="1. client screen - &lt;x&gt;"'), xml);
  assert.ok(xml.includes('<failure message="1 &lt; 2 &amp;&amp; &quot;q&quot; &gt; \'r\'">'), xml);
  assert.ok(!/<c>|<x>/.test(xml), "no raw markup from the data survives");
});

test("characters XML 1.0 cannot hold become a question mark, and the usual whitespace stays", () => {
  const xml = junitXml([report({
    failed: 1,
    steps: [step({ ok: false, problems: ["tab\there\nnewline \u0000nul \u001bescape ￾nonchar ok é ☃"] })],
  })]);
  assert.ok(xml.includes("tab\there\nnewline ?nul ?escape ?nonchar ok é ☃"), JSON.stringify(xml));
  assert.ok(!/[\u0000-\u0008\u000b\u000c\u000e-\u001f￾￿]/.test(xml));
});

test("each scenario is a suite of its own, and no scenarios is an empty document", () => {
  const xml = junitXml([report({ name: "one", steps: [step()] }), report({ name: "two", steps: [step(), step({ step: 2 })] })]);
  assert.equal(xml.match(/<testsuite /g).length, 2);
  assert.match(xml, /<testsuite name="one" tests="1"/);
  assert.match(xml, /<testsuite name="two" tests="2"/);
  assert.equal(junitXml([]), '<?xml version="1.0" encoding="UTF-8"?>\n<testsuites>\n\n</testsuites>\n');
});
