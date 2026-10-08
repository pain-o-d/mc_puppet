# mc_puppet — Codex entrypoint

This project is a separate Git repository in the MineMods workspace.
Read [the central Codex guide](../AGENTS.md) and
[the shared development conventions](../CLAUDE.md) explicitly; discovery
can stop at this repository's Git root. The central `main/` directory owns
common rules and tools, while this project owns its implementation state.

Read this project's [CLAUDE.md](CLAUDE.md), [README.md](README.md) and
[handover](docs/handover.md), then the relevant design/API documents, ADRs
and stable backlog tasks. Follow this project's supported loaders, mappings,
dependencies and checks from those guides.

For substantial planning, implementation or a resumed milestone, read the
shared skill [minemods-sdd](../.agents/skills/minemods-sdd/SKILL.md).
Use parallel subagents for independent substantial work as directed by the
central guide; give each one a disjoint scope and coordinate shared resources.
Preserve existing changes and distinguish current evidence from old runs.
