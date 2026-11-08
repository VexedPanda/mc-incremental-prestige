# Candidate areas: a two-step Prestige

Pack `GLOSSARY.md`: **Candidate area**; ADR-0008; spec VexedPanda/Mc-incremental#74, ticket #75. A Prestige no longer picks the next Run area by itself: it offers 3 Candidate areas and the players choose one.

## The two steps

1. **Prestige** (`/prestige`, or the Prestige item): `controller.Prestige.run` sweeps the old Run area and converts every online player's inventory and ender chest into EMC, as before. Instead of starting a Run, it offers `CandidateAreas.COUNT` (3) Candidate areas from `run.RunAreas.candidates`, lists them in every player's chat, and the world is now **choosing**. No Run area is added, and the border and world spawn stay on the old area.
2. **Choose** (`/prestige choose <1-3>`, op level 2 like `/prestige`): `Prestige.choose` takes the candidate and starts the Run there with the usual Run start: `RunAreas.beginRun` (record, border, world spawn), the Run snapshot, the starting items (topped up, so a player who joined mid-choice and already got them isn't given a second set), then the teleport. The first choice wins: once chosen nothing is offered, and later choices fail with "No Candidate areas are offered".

The world's first Run stays automatic: `Prestige.startFirstRun` takes one candidate and starts it with no choice.

## Candidates

`RunAreas.candidates(server, n)` uses the existing Run-area rules: GT-grid centres 1 to 2 minimum distances from the current area, the config radius, at least `runArea.minDistanceChunks` from every earlier Run area, and no ocean centre (with the existing fallback to an ocean centre when 1000 attempts all fail). Each candidate is also kept that far from the other candidates, so the three are distinct places. Unchosen candidates are never recorded as visited, so a later Prestige can offer them again. For now they are random: previews (#77) and the bias towards undiscovered resources (#79) come later.

## While candidates are offered

- **Frozen and invulnerable:** `controller.ChoosingFreeze` holds every player where it first sees them during the choice (where they stood at the Prestige, or where they joined), putting them back after every server tick, and cancels all incoming damage. It runs on the server tick rather than the player tick so it covers every player, needs nothing on the client, and catches players who join mid-choice. The holds are in memory: after a restart, players are held where they rejoin.
- **Re-roll:** Prestiging again (item or command) offers 3 fresh candidates and does nothing else: the old area is already swept and the players already converted, and there is no new summary.
- **Joining mid-choice:** `MissedPrestige` converts a returning player the pending Prestige hasn't converted yet (inventory and ender chest, credited at full value), then lists the candidates. A first-time player joins as they are. Either way they are moved by the choice like everyone else.
- **Portals stay closed:** the old Run is still the current one until the choice.

## Saved state

`controller.CandidateAreaData` (`mcincremental_prestige_candidate_areas`, on the overworld): the candidates in order, and the UUIDs of the players the pending Prestige has converted. Both survive a restart, so a restart mid-choice neither loses the candidates nor picks one. The list is overworld-only for now; #77 groups it by dimension.

## Tests

`CandidateAreaGameTests` go through the command: offering 3 and starting no Run, choosing candidate n, a second choice ignored, a re-roll that converts nothing, the candidates surviving `RunRestartGameTests.simulateRestart`, the distance rule, and a player who joins mid-choice being listed the candidates, held and unhurt, then moved and released by the choice. The ocean rule uses the `RunAreas.candidates(server, n, unsuitable)` seam, as the GameTest world is superflat. `MissedPrestigeGameTests` covers a player who missed the sweep and joins mid-choice. Every other Prestige test runs `/prestige` then `/prestige choose 1` (`PrestigeGameTests.runPrestigeAndChoose`), and `PrestigeGameTests.saveWorld` withdraws any candidates left offered, so one failed test can't leave the shared test world mid-choice.
