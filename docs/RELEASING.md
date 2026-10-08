# Releasing MC Puppet

How a version gets from `develop` to the three places people find it, written
down after doing it three times in one day (0.1.0, 0.1.1, 0.1.2 on 2026-09-21)
and getting something wrong each time. Every rule here has a reason beside it.

Where it lives:

| | | who publishes |
|---|---|---|
| the mod, four jars | [modrinth.com/mod/mc-puppet](https://modrinth.com/mod/mc-puppet) | the owner, by hand |
| the tools | [npmjs.com/package/mc-puppet](https://www.npmjs.com/package/mc-puppet) | the owner, by hand |
| source, releases, issues | [github.com/pain-o-d/mc_puppet](https://github.com/pain-o-d/mc_puppet) | a tag does it |

A release on GitHub is a record tied to the commit it was built from. Modrinth
and npm are where people install from. Nothing is published to either by
automation yet, on purpose: see the end.

## 1. Before

On a `release/<version>` branch off `develop` (a `hotfix/<version>` off `main`
for an urgent one):

- **One version, in three places**: `mod_version` in `gradle.properties` and in
  `mc1.20.1/gradle.properties` (each keeps its `+mc<game>` suffix), and
  `version` in `tools/puppet/package.json`. The workflow refuses a tag that
  disagrees with any of them.
- **`CHANGELOG.md` has a section headed exactly `## <version>`.** The workflow
  cuts the release notes out of it by that heading, and fails without it.
- `tools/build-all.sh` — all four jars, the Java tests, the Node tests.
- `node tools/prod-check.js --eula` — the built jar in a real server: off
  without consent, open once allowed, off again. Fourteen checks.
- If anything about connections, the token, the audit or consent changed:
  start a dev client, load a world, and `node tools/attack-check.js <gameDir>`.
  Nineteen checks against the live bridge.
- If anything a scenario touches changed: run a real suite against it. The
  one next door is `../get_rich/tools/run-client-scenarios.sh`. **In a fresh
  world** (`WORLD=<new name>`): its usual test world wears out, and a worn
  world fails on prices, which looks exactly like a regression here.

Before 1.0.0 every release is a beta. `docs/ROADMAP.md` says what 1.0.0 waits for.

## 2. The tag

```bash
git switch main && git merge --no-ff release/<version> -m "Release <version>"
git tag -a v<version> -m "MC Puppet <version> (beta)"
git switch develop && git merge --no-ff main -m "Merge main into develop after <version>"
git push origin main develop
git push origin v<version>
```

The tag starts `.github/workflows/build.yml`, which builds on Linux, runs
`prod-check` there too, checks the three versions against the tag, checks that
each sources jar really holds the sources, and writes the release: four jars,
four sources jars, the tools' tarball, `SHA256SUMS`. Marked pre-release for
every `0.x`. About fifteen minutes; watch the Actions tab.

**A tag is not moved and a release is not edited**, and GitHub is set to refuse
both (rulesets on `main` and `v*`; release immutability). A mistake found
after tagging is the next patch version. 0.1.0 was tagged with game version
ranges that were too wide, and became 0.1.1 rather than a moved tag.

## 3. npm, first

```
cd tools\puppet
npm.cmd publish          (PowerShell on this machine refuses npm.ps1; npm.cmd is the same program)
npx mc-puppet version    (from some other folder: the new version, and the protocol)
```

**First, because the README tells people to run `npx mc-puppet`.** Whoever
owns that name on npm owns what those people run. A version on npm cannot be
replaced, only followed by another.

Check it afterwards: `npm view mc-puppet@<version> dist.integrity` must equal
the `integrity` of `npm pack --dry-run --json` run at the tag.

## 4. Modrinth

Four versions, one for each jar, **files taken from the GitHub release** and
not from a local build folder, so that they match `SHA256SUMS`.

| Version number | Subtitle | Loader, game | Required dependencies |
|---|---|---|---|
| `<v>+mc1.21.1-fabric` | MC Puppet `<v>` — Fabric 1.21.1 | Fabric, 1.21.1 | Architectury API, Fabric API |
| `<v>+mc1.21.1-neoforge` | MC Puppet `<v>` — NeoForge 1.21.1 | NeoForge, 1.21.1 | Architectury API |
| `<v>+mc1.20.1-fabric` | MC Puppet `<v>` — Fabric 1.20.1 | Fabric, 1.20.1 | Architectury API, Fabric API |
| `<v>+mc1.20.1-forge` | MC Puppet `<v>` — Forge 1.20.1 | Forge, 1.20.1 | Architectury API |

- **The loader is part of the version number.** Modrinth's Maven finds a file
  by that number, the README tells developers to depend on it that way, and two
  files under one number is one of them at random.
- **Dependencies: "Any version".** The list offers Architectury builds for
  every game; the launcher picks the right one, and the floor is in the jar.
- **One game version each, never a range.** The jar says the same.
- Channel **Beta** until 1.0.0. Environment: client and server, optional on
  both, best on both.
- The jar is **Primary**. Its `-sources.jar` goes beside it as a supplementary
  file of type *Sources JAR* — **from 0.1.3 on**. Those of 0.1.1 and 0.1.2
  held one file of forty-four and were left off.
- Four jars are four *versions*, never one version with supplementary files:
  launchers look at a version's loader and game, not at its extra files.
- Changelog: that version's section of `CHANGELOG.md`.
- The page's own text, and the values of its fields, are kept in
  `docs/modrinth-page.md`; `build/modrinth-description.md` is the part below
  the rule, to paste whole. Disclosures switched on: *AI-generated content*
  (it is, and every commit says so) and *External system interactions* (it
  reads a consent file in the user's home, and opens a local port).

The first submission went to review on 2026-09-21 with 0.1.2.

## 5. After

- The release is on the Releases tab, marked immutable.
- `https://modrinth.com/mod/mc-puppet` shows the new versions.
- `npx mc-puppet version` from a clean folder says the new number.

## How GitHub is set up, since none of it is in the repository

Public. Default branch `develop`, because the README with the links is there
and `main` only moves at a release. Issues on; wiki, discussions and projects
off. Merge commits only. Release immutability on. Private vulnerability
reporting on, which `SECURITY.md` relies on. Rulesets: `main` — no deletion,
no force push; tags `v*` — no deletion, no force push, no update. No bypass,
the owner included. The "About" box: description, website = the Modrinth page,
topics. Social preview: `docs/social-preview.png`.

## Not automated, and why

Publishing to npm and Modrinth from the workflow would need their tokens as
repository secrets, and would give npm provenance, which is worth having for a
tool that runs on people's machines. It waits for a release or two done by
hand, so that what gets automated is a procedure known to work.
