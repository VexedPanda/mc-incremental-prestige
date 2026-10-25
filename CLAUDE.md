# Mc-incremental Prestige

NeoForge 1.21.1 mod for the [Mc-incremental](https://github.com/VexedPanda/Mc-incremental) modpack: the Prestige controller (change tracking, Prestige sweep, new Run area, world border, teleport, inventory wipe) and the EMC tank.

Design, vocabulary and decisions live in the pack repo: read its `GLOSSARY.md`, `docs/adr/` and `docs/pocs/poc-1.md` before changing behaviour, and use the glossary's terms in code and docs. This repo exists because of the pack's ADR-0003 (one repo per custom mod).

## Building

`./gradlew build` produces the jar in `build/libs/`. Requires Java 21 and access to `maven.neoforged.net` (blocked in some Claude Code cloud environments; CI builds it on every push and PR).

## Releases

Pushing a `v*` tag builds the jar and attaches it to a GitHub release; the pack consumes it from there.

## Pull requests

This is a vibe-coded project: merge PRs yourself as soon as they're pushed and CI is green, without waiting for review. Open them ready for review (not draft) and squash-merge them into `main`.
