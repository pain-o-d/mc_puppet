# Security

MC Puppet is remote control of a game. Its README says what it promises under
**Safety first**; this page is for when a promise does not hold.

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private report instead:
the **Security** tab of this repository, **Report a vulnerability**. It reaches
the maintainer alone, and stays private until there is a fix to point at.

Say what you did, what happened, and which jar and which version of the tools
it was (`mc-puppet version`, and `info` from the game, say both). A scenario
file or a few lines of script that show it are worth more than a description.

You will have an answer within a week. This is one person's project, so a fix
may take longer than that; you will be told how long, and credited in the
changelog unless you would rather not be.

## What counts

Anything that breaks one of these, which the mod claims:

- nothing listens anywhere but `127.0.0.1`;
- nothing runs before the token has been shown;
- outside a development environment, a game is not driven until its directory
  has been allowed from the user's home, and nothing that arrives with a
  download — a config folder, a modpack, a world — can give that consent;
- the audit log says what was asked, and whoever is written about cannot make
  it say otherwise;
- a scenario file cannot reach the machine it is run on: no code of its
  author's runs, and nothing is written outside its own folder;
- the tools on npm and the jars on Modrinth are what this repository's
  workflow built from the tag of the same version (`SHA256SUMS` in each
  release is there to check that).

## What does not

- A program that already runs as you on the same machine can read the token
  file and drive the game. It could also edit your saves; the token is not a
  defence against it and does not pretend to be.
- A modpack that can set JVM arguments, or that carries a mod of its own, is
  code running as you already. Consent keeps out *files* that arrive with a
  download, and says so.
- On Windows the token file is as readable as the folder the game is in. The
  README says where that matters.
- A scenario can run any command at operator level in the game it is pointed
  at. That is what it is for; read one before you run it.

## Supported versions

The latest release. Before 1.0.0 there are no backports.
