# Mc-incremental Prestige

NeoForge 1.21.1 mod for the [Mc-incremental](https://github.com/VexedPanda/Mc-incremental) modpack: the Prestige controller (change tracking, Prestige sweep, new Run area, world border, teleport, inventory wipe) and the EMC barrel.

Design, vocabulary and decisions live in the pack repo: read its `GLOSSARY.md`, `docs/adr/` and `docs/pocs/poc-1.md` before changing behaviour, and use the glossary's terms in code and docs. This repo exists because of the pack's ADR-0003 (one repo per custom mod).

## Building

`./gradlew build` produces the jar in `build/libs/`. Requires Java 21 and access to `maven.neoforged.net` (blocked in some Claude Code cloud environments; CI builds it on every push and PR).

## Releases

Run the manual `Release` workflow (inputs: `version`, e.g. `v0.2.0`, and `ref`); it tags the ref, builds the jar and attaches it to a GitHub release, which the pack consumes. Cloud sessions can't push tags or create releases directly.

## Pull requests

This is a vibe-coded project: merge PRs yourself as soon as they're pushed and CI is green, without waiting for review. Open them ready for review (not draft) and squash-merge them into `main`.
