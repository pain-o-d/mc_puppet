/**
 * Scenario reports as JUnit XML, which every CI knows how to show.
 *
 * A scenario is a suite and a step is a case, so that what failed is named
 * where a person looks first. A step that was tried and not needed is
 * skipped; errors the game logged are a case of their own at the end.
 */

const escape = (text) => String(text)
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;")
  // What XML 1.0 cannot hold at all, which a game's chat may well contain.
  .replace(/[^\x09\x0A\x0D\x20-퟿-�]/g, "?");

/** @param {object[]} reports what scenario.run returned, one a scenario */
function junitXml(reports) {
  const suites = reports.map((report) => {
    const cases = report.steps.map((step) => {
      const name = `${step.step}. ${step.side} ${step.op}${step.phase ? ` (${step.phase})` : ""}${step.note ? ` - ${step.note}` : ""}`;
      const open = `    <testcase classname="${escape(report.name)}" name="${escape(name)}" time="${((step.ms || 0) / 1000).toFixed(3)}"`;
      if (step.skipped) return `${open}><skipped/></testcase>`;
      if (step.ok) return `${open}/>`;
      const problems = (step.problems || ["failed"]).join("\n");
      return `${open}><failure message="${escape((step.problems || ["failed"])[0])}">${escape(problems)}</failure></testcase>`;
    });
    const logged = report.log_problems || [];
    if (logged.length) {
      const lines = logged.map((each) => each.line).join("\n");
      cases.push(`    <testcase classname="${escape(report.name)}" name="the game's log"><failure message="${escape(`${logged.length} error(s) logged while this ran`)}">${escape(lines)}</failure></testcase>`);
    }
    const failures = report.failed + (logged.length ? 1 : 0);
    const skipped = report.steps.filter((step) => step.skipped).length;
    const seconds = report.steps.reduce((sum, step) => sum + (step.ms || 0), 0) / 1000;
    return `  <testsuite name="${escape(report.name)}" tests="${cases.length}" failures="${failures}" skipped="${skipped}" time="${seconds.toFixed(3)}">\n${cases.join("\n")}\n  </testsuite>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuites>\n${suites.join("\n")}\n</testsuites>\n`;
}

module.exports = { junitXml };
