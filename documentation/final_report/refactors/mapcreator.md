# Map creator refactor

Date: 2026-09-27. Scope: `mapCreator/`, the standalone browser tool that builds the game board (`map.json`) on top of the Wellington road network.

## Starting point

- **One big file:** all the logic was in a single 2,496-line `map-creator.js`.
- **Headless harness:** `mapCreator/headless/` held a Python + Selenium harness (`generate.py`, `evaluate_map.py`), 14 parameter files and about 100 committed output files (JSON, screenshots, `__pycache__`). It drove the page in headless Firefox and passed tuning values in through a `window.MAPGEN_PARAMS` hook. It also read diagnostics from `window.__mcStats`.
- **Unused data:** the page loaded two data files that no code read: `wellington-buses.js` (~400 KB) and `wellington-trains.js` (~60 KB).

## What changed

### Removed
- **Harness:** the headless harness, its parameters and outputs, and the `MAPGEN_PARAMS` and `__mcStats` hooks it used.
- **Dead code:** 10 functions that were never called: `placeAlongRoutes`, `splitLineAtInterval`, `upsertNode`, `connectTrainComponents`, `sortWaysFromOrigin`, `sampleWayPts`, `deduplicateParallelWays`, `inTrainGameArea`, `nearestRoadVertex` and `nearbyVertexKeys`. Their constants went too.
- **Dead options:**
  - `ANCHOR_HUB` was never set, so the "hub" branch of the router could never run.
  - Two routing options were passed in but never read.
  - The non-exclusive routing branch only ran when a harness setting turned exclusivity off.
- **Unused data:** the two data files above.
- **Old generator:** the Auto-Generate button, an older random E-scooter-only generator that the Train / Bus / Escooter buttons had replaced.

### Split into files
The code is now 9 plain `<script>` files in `mapCreator/js/` that share one global scope. There's no build step, and the page still opens straight from disk.

| File | Responsibility |
|---|---|
| `geo.js` | Distance maths and a min-heap |
| `roads.js` | Road index, snapping, Dijkstra routing, road-use registry |
| `graph.js` | Nodes and edges, undo, manual edits, save/load |
| `render.js` | MapLibre layers, node icons, connected-component view |
| `gen-shared.js` | Generator settings and shared helpers |
| `gen-train.js`, `gen-bus.js`, `gen-escooter.js` | One file per generator button |
| `ui.js` | Mouse and button wiring, startup |

The data files moved to `mapCreator/data/`.

### Merged duplicates
- **Bus and E-scooter generators:** they had identical copies of the node creator, the edge adder and the whole per-cluster mesh routine (spanning tree, extra neighbour links, minimum degree 2, orphan removal). Each now exists once, with the mode passed in: `genNode`, `addRoadEdge` and `meshCluster`.
- **Repeated snippets:** "find the edge between two nodes", degree counting, "which nodes are stations" and connected-component search were repeated 5–7 times each. They're now `findEdge`, `degreeMap`, `trainNodeIds` and `computeComponents`.
- **Generator split recursion:** all three generators split routes the same way, now through one `splitVia`.

### Tuning made the default
The harness had tuned the generator well beyond the code's defaults. Those tuned values (`r3-iter1.json`) are now constants in `gen-shared.js`:
- 5 areas, adding Upper Hutt, with 2–3× the node counts
- more neighbour links per node
- a 900 m limit on which bus edges also get E-scooter
- wider limits for stitching stations in, bridging disconnected groups and repairing low-degree nodes

### Behaviour changes
- **Random generation:** each area's node sampling used to start from the road vertex nearest the area's centre, so every run produced the same map. It now starts from a random vertex, and each run gives a different board.
- **Used-roads fix:** when a cluster dropped nodes it couldn't connect, their removed edges still counted as using their roads. That blocked other edges from those streets for the rest of the run. The road-use registry is now rebuilt after the drop.
- **Edge-panel bug:** the edge panel's mode buttons shared a CSS class with the toolbar's mode buttons. So changing a selected edge's modes also changed the modes given to new edges, and reset the panel's highlight. The toolbar handler is now scoped to the toolbar.
- **Save Map:** the button is always enabled.

### Comments
Long comments were cut to one or two lines, and no comment sits at the end of a line of code.

## Size

| | Before | After |
|---|---|---|
| Main JS | 1 file, 2,496 lines | 9 files, ~1,500 lines |
| Loaded data | 4 files | 2 files (in `data/`) |
| Harness | 2 scripts + ~115 parameter/output files | none |

## Verification

1. **Same output:** before any edits, I generated a map with the old code and the tuned parameters, using Playwright and headless Chromium. Generation was deterministic at that point (two runs gave identical files). The refactored code with no parameters produced a byte-identical map: 261 nodes, 436 edges.
2. **Randomness and fix:** after adding randomness and the used-roads fix, two runs gave different maps (255/427 and 262/423 nodes/edges). Each was a single connected component with no page errors.
3. **Manual editing:** a scripted browser test covered adding nodes, drag-to-connect, editing an edge's modes (confirming the toolbar no longer changes), undo/Ctrl+Z, Show Components, search, renumber, save/load, rename and delete. All passed with no console errors.
4. **Syntax:** every script passes `node --check`, and all of them parse together with no duplicate global names.
