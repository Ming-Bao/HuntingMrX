# Backend readability refactor

Date: 2026-09-23 (commit `b17d611`). Scope: `backend/src/main/java`, the tests that call it, and the backend class diagrams.

## Starting point

The 2026-09-21 rewrite had already made the backend small, about 970 lines in 22 files. The aim of this pass was readability, not size: clearer names, a clearer package layout, and documentation on every class and method.

- **Mixed package:** `game/` held 12 files: the rules (`Game`, `Player`, `MapGraph`), the records sent to clients, and the enums. The three exceptions the rules throw sat in a separate top-level `exception/` package, so the "plain Java" core depended on a sibling package.
- **Misleading names:**
  - `GameNotFoundException` was also thrown for "Player not found".
  - The controller's `GameService` field was called `games`, so `games.createGame(...)` read like a call on a map.
- **Sentinels and helpers:**
  - Turn order was passed on with `giveTurnToNextDetective(-1)`.
  - `Game.leave` returned a "nobody left" boolean that only one caller used.
  - `checkCanStart` was a three-line private helper with one caller.
- **Sparse docs:** 41 doc comments across the code and no `@param` tags.

## What changed

### Package layout
`game/` is split into sub-packages, and the top-level `exception/` package is gone.

| Package | Classes | Responsibility |
|---|---|---|
| `game` | `Game`, `Player`, `MapGraph` | The rules. They stay together because `Game` uses `Player`'s package-private constructor and mutators. |
| `game.view` | `GameState`, `PlayerView`, `MrXMove`, `ValidMove` | Records sent to clients |
| `game.enums` | `GamePhase`, `TurnPhase`, `Role`, `Winner`, `TicketType` | The game's fixed values |
| `game.exception` | `NotFoundException`, `ForbiddenException`, `ConflictException` | Errors the rules throw, mapped to 404, 403 and 409 |

The whole rules core, including its errors, now lives under `game` and still has no Spring imports.

### Renames
- `GameNotFoundException` → `NotFoundException`.
- `GameController.games` → `gameService`.
- `GameService.find` → `findGame`, and `player` → `findPlayer`.
- `Game.giveTurnToNextDetective(int after)` → `giveTurnToDetectiveFrom(int index)`. After Mr X it's called with `0` and after a detective with `index + 1`, so the `-1` sentinel is gone.
- `Game.legTicket` → `parseLeg`. A `DOUBLE_PREFIX` constant replaces the two `"DOUBLE_"` literals in `move`.

### Removed
- **`leave` return value:** `Game.leave` now returns `void`. `GameService.removePlayer` checks `game.players().isEmpty()` to decide whether to forget the game.
- **`checkCanStart`:** its three checks are inlined at the top of `start`.

### Documentation
- **Javadocs:** every class, enum, record, constructor and method now has a Javadoc. Methods and constructors list each parameter with `@param` and the result with `@return`. `@throws` is used where an error is part of the contract. Records document their components with `@param`. Getters use the one-line `/** @return ... */` form.
- **Formatting:** `HuntingMrXApplication` was the only file indented with tabs. It now uses 4 spaces like the rest.
- **Diagrams and guide:**
  - `class-graph.md` uses Mermaid `namespace game.view { ... }` blocks.
  - `class-graph.drawio` page 1 says which sub-package each class is in, and its page 2 package folders are renamed.
  - In `classdiagram.md` (PlantUML) only the section labels name the sub-packages, because the file declares its classes at the top and `package` blocks would duplicate them.
  - The code layout in `backend/doc.md` lists the new packages.

### Kept on purpose
- **No API change:** JSON field names, endpoints, STOMP topics and error bodies are unchanged, so `openapi.yaml` and the frontend didn't change. Jackson doesn't serialise package names.
- **Wire fields:** `MrXMove.nodeId` → `revealedNodeId` would read better, but it's a wire field, so renaming it would be an API change.
- **Config keys:** `GameSettings` keeps its four ticket-count properties rather than one bound map, so the `game.*` config keys stay the same.
- **Spring layout:** `controller` / `service` / `config` keep the conventional Spring layout.

## Size

| | Before (`3781b9f`) | After (`b17d611`) |
|---|---|---|
| Java files in `backend/src/main` | 22 | 22 |
| Files directly in `game/` | 12 (+3 in `exception/`) | 3 (+12 across 3 sub-packages) |
| Code lines (no comments or blanks) | 758 | 770 |
| Total lines | 972 | 1,463 |
| Doc comments | 41 | 124 |
| `@param` tags | 0 | 144 |

The 12 extra code lines are imports that now cross package boundaries. All the other growth is documentation.

## Verification

1. **Tests:** before the change, `mvn clean test` ran 134 tests with 0 failures. It ran the same 134 with 0 failures after each step: the renames, the package split and the Javadocs. The counts come from the surefire XML reports, and I ran `clean` each time because the IDE leaves stale compiled classes in `target/`.
2. **Stale references:** a grep for the old class names and package paths (`GameNotFoundException`, `huntingmrxwellington.exception`, `game.TicketType` and similar) in `backend/src` and `documentation/plans` returned nothing.
3. **Javadoc:** `javadoc -Xdoclint:all,-missing -private` over every main source file reported no errors, so every `@param` matches a real parameter.
4. **Diagrams:**
   - All three Mermaid blocks in `class-graph.md` passed Mermaid's own parser, run in Node with jsdom. As a check on the checker, a deliberately broken block failed.
   - `class-graph.drawio` still parses as XML.
