# Singleplayer simulation distance

`run/SingleplayerSimulationDistance` caps the integrated server's simulation distance to the current Run area: `RunArea.simulationDistanceChunks()`, which is `2 * radiusChunks` (2 for the default 3×3 area, 4 for 5×5). From a corner chunk the far edge is that many chunks away, so the whole area keeps ticking wherever the player stands. Before the first Run it uses the configured `runArea.radiusChunks`. Larger areas get a larger cap, but it never raises a lower client value. Below radius 1 the cap is 0, which ticks only the player's own chunk, and that is the whole area.

- **Why:** the integrated server copies the client's simulation distance option, and the slider can't go below 5. Dedicated servers use `server.properties` (`simulation-distance=2` in the Pack) and are left alone.
- **Every tick, on `ServerTickEvent.Post`:** `IntegratedServer.tickServer` re-applies the client option only when the slider changes (it tracks `previousSimulationDistance`), and it does so after `super.tickServer`, so after the event. A slider change therefore lasts one tick before the cap returns. The handler calls `PlayerList.setSimulationDistance` only when the value is above the cap, because that call broadcasts a packet and updates every level.
- **Gate:** `MinecraftServer.isSingleplayer()` (the server has a singleplayer owner). `!isDedicatedServer()` would also match the GameTest server. Don't reference `IntegratedServer` from common code, because it's client-only.
- **Render distance** stays the player's choice.
- **Tests** (`SimulationDistanceGameTests`) give the GameTest server a singleplayer owner with `setSingleplayerProfile` to stand in for singleplayer. Without one, it stands in for a dedicated server.
