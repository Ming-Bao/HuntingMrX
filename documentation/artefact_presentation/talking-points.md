# Artefact demo: talking points and likely questions

Plan: 4-minute live demo, about 6 minutes of slides, then questions. The examiners won't have read
the report, so start from zero. They mark on five criteria (Handbook Vol. IV, ch. 6): functionality,
usability and behaviour, testing and quality assurance, performance, innovation and creativity.

Every fact below is from the final report, `spec.md` or the code. Lines marked **[you]** need your
own answer.

---

## 1. Live demo (4 minutes)

**Setup before the slot:** open two browser tabs on the live site, plus a third if you can. Each tab
is a separate player: the player's ids sit in `sessionStorage`, which is per tab and survives a
refresh. Put two of them side by side so Mr X and a detective are visible at once. Have a backup
game already in the lobby in case the network is slow.

| Time | Show | Say |
|---|---|---|
| 0:00 | Home page | "Hidden-movement board game, now online on a real map of Wellington. No install, no account." |
| 0:20 | Create a game | Lobby shows the six-character code, a join link, and a QR code. |
| 0:40 | Join from the second tab with the code | The lobby updates live on the host's screen: server push, no refresh. |
| 1:00 | Host starts | One player is picked at random as Mr X; everyone starts on a different random node. |
| 1:15 | Board, Mr X's tab | 216 real locations, routes coloured by mode: e-scooter, bus, train, ferry. Node pie icons show which modes stop there. Mr X has unlimited ordinary tickets, plus Invisible and Double tickets. |
| 1:45 | Mr X moves | Click a reachable node; a button for each ticket that can pay for it. |
| 2:00 | Detective's tab | Mr X shows as "?". The log shows only which ticket he used. Detective tickets: 12 / 8 / 6 / 2. |
| 2:30 | Detective moves, then Mr X moves in round 2 | Round 2 is a reveal round, so his node now shows for everyone. Next reveals: 8, 13, 18, 24. |
| 3:10 | Refresh the detective's tab | The player keeps their place and catches up. A dropped connection isn't a game event. |
| 3:30 | (Optional) Browser dev tools on the detective's tab | The state message from the server has Mr X's position as null. It isn't hidden by the screen; the server never sent it. |
| 3:50 | Wrap up | "That's the game; now how it's built and how I know it works." |

If something breaks live: say what it should do, and switch to the backup game or the slides. The
cover has a board screenshot, and slides 4 and 6 show what each player receives.

---

## 2. Slide talking points (about 6 minutes, 35 seconds a slide)

The full detail is in each slide's speaker notes. These are the lines to actually say.

1. **Cover.** "Quick reset: one player moves in secret, the rest hunt them, on real Wellington
   transport. Built as a new Open Day demo, because the old one was over five years old."
2. **State machine.** "Every request is checked against this state machine. A game starts in the
   lobby; once the host starts it, Mr X and the detectives take turns for up to 24 rounds. It ends
   one of three ways: the detectives catch or box in Mr X, Mr X survives round 24, or the game is
   aborted when someone leaves or sits idle for 15 minutes. A request in the wrong state is rejected
   and changes nothing."
3. **Architecture.** "The browser never decides anything. It sends an intention as a REST call
   ('move to 42 by bus'), and the server checks it and pushes each player their own view over STOMP.
   The rules sit in plain Java with no framework, so I can test every rule directly. Each game has
   its own lock."
4. **Secret.** "Hiding Mr X on screen isn't enough. A detective must be unable to *get* his
   position. Late in the project, testers used AI on our open-source code to find him through the
   API. Three holes: IDs worked as passwords, the broker allowed wildcard subscriptions, and clients
   could publish. I fixed them with secret per-player tokens and exact-match private channels, and
   the attacks are now tests."
5. **The map file.** "You've seen the map; this is the file behind it. One JSON file: nodes with
   a position, and edges with their transport modes and a road path for drawing. The server only
   reads the ids and modes, and refuses to start if an edge names a node or mode that doesn't
   exist. The paths were computed once, offline, from OpenStreetMap by my own map-creator tool, so
   there's no paid routing API."
6. **REST and STOMP.** "These are real messages from a local game. Alice moves with a REST POST:
   her secret token in a header and a two-field body. A bad move gets a clear error like 'Not your
   turn'. Straight after, Carol's private STOMP topic gets the whole game state. Mr X's node is null
   because she's a detective. Every message is a whole snapshot built for that player, so a missed
   one can't leave anyone out of sync. There's no database: games live in memory, and an OpenAPI
   file documents every endpoint and topic."
7. **Testing.** "135 backend tests in six layers, none needing a browser. Coverage is 99.2%, but
   coverage only shows what runs, so I also used mutation testing: 199 of 199 mutants killed. A late
   review found 8 bugs, and each fix has a test that failed before it. The weak spot is the
   frontend, which I tested by hand."
8. **Performance and usability.** "A move reaches every player in 8.3 ms at the 95th percentile,
   across 10 games at once, against a 500 ms target. The usability score is 72.5, above the average
   of 68. MapLibre wasn't the fastest map library in the benchmark, but it was the most consistent,
   and it's free."
9. **AI.** "I used Claude Code throughout. It was good at reviews and big renames; it found two of
   the worst bugs. It also wrote confident wrong docs and tests that checked nothing. Because I
   didn't read every line, the checks are automatic: tests, mutation testing, and a hook that blocks
   API changes without the spec."
10. **Limits.** "The weak points are a small user study, no frontend tests, Safari untested, and a
   restart ending games. Next steps: a tutorial, computer players to test balance, and other cities.
   Questions?"

---

## 3. Questions the markers might ask

### Hui (databases and AI). From the poster feedback: expect data management, the JSON schema, AI use and hallucinations.

**Why no database?**
A game lasts under an hour and only exists while its players are online, so games live in a
`ConcurrentHashMap` in the server. The constraint was one self-hosted container with no database
(C2). The cost is that a restart ends every game. That's accepted for short games among friends,
but it rules out long or scheduled games. Persistence and accounts are future work.

**Walk me through the data model.**
- `map.json` holds `nodes` (`id`, `lat`, `lng`, `label`, `offRoad`) and `edges` (`from`, `to`,
  `modes[]`, `coordinates[]`).
- `offRoad` marks the 8 train-station nodes, which sit on the rail line rather than a road. The
  map creator draws an edge from a station to a road node as a straight line instead of routing
  it along roads. The game ignores the flag.
- Edges are undirected.
- `MapGraph.parse` reads only ids, ends and modes into an adjacency map:
  node → neighbour → set of modes. Modes on a repeated pair are merged.
- Coordinates are only for drawing; the server serves the raw file at `GET /api/map`.
- Game state is plain Java objects: `Game` and `Player`. Unlimited tickets are stored as −1, so
  Mr X and the detectives share one ticket model.

**How do you know the map data is valid?**
An edge naming an unknown node or an unknown mode throws at load, so the server won't start. The
map creator has a connected-components view to make sure every node is reachable. Its output was
checked byte for byte against the original after it was refactored.

**How was the map data made? Is it accurate?**
- Road data was exported from OpenStreetMap with Overpass Turbo (about 5.7 MB of GeoJSON), and
  train lines were traced separately.
- The map creator snaps nodes to roads and routes each edge with Dijkstra.
- It generates the network in layers: train first, then bus clusters, then e-scooter edges of up
  to 900 m.
- A road-use registry stops two edges claiming the same street.
- Honest caveat: train and ferry edges follow the real lines, but bus and e-scooter edges aren't
  checked against real bus routes or e-scooter zones.

**Why is the map file 1.86 MB? Isn't that wasteful?**
It holds about 28,000 coordinate pairs at full precision and is sent uncompressed. HTTP compression
plus rounding to about one-metre precision would shrink it several-fold with no visible change.
This is listed as the remaining inefficiency.

**How do the Java and TypeScript sides agree on the data format?**
They can't share types. The answer is one written contract: `openapi.yaml` (OpenAPI 3.1) documents
every REST endpoint and every STOMP topic. A pre-commit hook rejects any commit that changes the
API without updating it.

**How did you use AI?**
Claude Code drafted code and tests from written specs, reviewed code, did repetitive changes across
many files, and kept docs and diagrams in step with the code.

**How did you deal with hallucinations?**
Concrete failures:
- It wrote plausible but wrong docs, describing the pause-on-disconnect feature after it was
  removed.
- It added layers and abstractions nobody needed.
- Its early tests checked getters, not behaviour.

Since not every line was reviewed, the checks are automatic: the test suite, mutation testing (it
catches tests that check nothing), and the OpenAPI pre-commit hook.

**Did the code and the tests come from the same prompts? Isn't that circular?**
**[you]** Answer from how you actually worked. Whatever the answer, the independent checks are
mutation testing, the attack tests, and the random-game rule checks. Those test what the system
must do, not what the code happens to do.

**How do you know the AI didn't introduce bugs you missed?**
The same checks. Also, an AI review is what found two of the worst bugs, the Mr X leak and the
boxed-in freeze. Each bug fix got a test that failed before the fix.

**Was AI used to write the report?**
**[you]**

### Thomas (general software engineering)

**Why Java and not Node, when the frontend is TypeScript?**
Spring has a STOMP broker built in, and Java has mature coverage (JaCoCo) and mutation (PIT) tools,
which the proposal promised. The cost is no shared types, which the OpenAPI contract handles.

**Why REST for actions and WebSockets for updates? Why not one or the other?**
REST gives simple error handling: an illegal move or a move out of turn gets an error code and a
message back, and it's easy to test. Updates need push. Polling alone would delay every move by up
to one interval. Server-Sent Events would work, but STOMP gives one channel per game and per player
for free, and SockJS falls back to HTTP where WebSockets are blocked. Polling every 6 s remains as a
backup.

**How do you handle concurrency?**
Each game has its own lock (`synchronized` on the game in `GameService`). Requests to one game run
one at a time, and different games run in parallel. Games are stored in a `ConcurrentHashMap`. A
game only gets a few requests a second, so serialising them costs little.

**What happens if two players act at the same moment?**
The lock serialises them. The second request is checked against the state the first one left. If
it's now out of turn or illegal, it's rejected, and a rejected move changes nothing.

**How does it scale?**
Load tested with 10 concurrent six-player games: p95 8.3 ms and worst 36.6 ms from move to every
client. It's single-instance by design (C2). Running across several servers is future work, and
would make the project a test bed for keeping distributed state consistent while keeping each
player's secret.

**What if the server crashes mid-game?**
Every game ends. That's accepted for this version. It's the trade-off of no database.

**What happens when a player's connection drops?**
Nothing happens to the game. The client reconnects, re-subscribes and fetches the full state over
REST, and a REST refresh runs every 6 s anyway. Every message is a whole snapshot rather than a
diff, so a missed message can never leave a client inconsistent.
The original design paused the game with a 60 s grace period. User testing on uni and home Wi-Fi
showed WebSockets dropping far more than expected, so that design was dropped.

**Why does one player leaving end the game for everyone?**
Without Mr X there's no game. Without a detective it's unbalanced, because Mr X's Invisible tickets
are set by the number of detectives at the start. Handing the role to someone else doesn't work
either, and ending the game is the simplest rule to make correct.

**Why a 15-minute idle limit and no auto-skip?**
Friends discuss moves for minutes, so 15 minutes only catches abandoned tabs. Without it, a closed
tab would leave the other players waiting and the game in memory forever. The server checks every
30 s.

**How are the tokens generated? Could someone guess one?**
Each player gets a random UUID token from `UUID.randomUUID()`, which uses a secure random source.
It's only ever sent in that player's own create or join response. Join codes are six characters
from A–Z and 0–9, picked with `SecureRandom`. The public player id grants nothing.

**Why no user accounts or logins?**
They were considered and rejected. They add friction to a casual game, and they protect nothing a
per-game secret doesn't. No accounts also means no personal data stored.

**Why did coverage jump from 65% to 99%?**
Mostly from moving the rules out of Spring into plain classes, not from writing more tests. Before
that, the rules engine was mocked out of most tests.

**What's mutation testing, and why bother when coverage is 99%?**
PIT makes small faults, such as flipping a comparison or deleting a call, and checks that some test
fails. Coverage says what code runs; mutation testing says whether the tests would notice it
breaking.

**Why no frontend tests?**
Honest answer: time. Effort went into the backend, where the rules and the secret live. The browser
holds no game logic, since the server decides everything, so a frontend bug is a display bug, not
a rules or secrecy bug. It was tested by hand after every change, and Playwright was used for
scripted browser checks. Selenium was tried first and was flaky.

**What was the hardest part?**
Building the map. Real road networks are messy. Automatic generation connected every node to every
neighbour or produced loops and doubled lines, and a fun graph needs judgement about density and
balance that no generator captured on its own.

**What would you do differently?**
- Treat connectivity as unreliable from the start, not as an exception.
- Write attack tests from day one when a secret is a core requirement. The existing tests only
  checked normal use.
- Don't trust a framework's defaults to be secure.

**How is it deployed?**
One Docker image (231 MB) runs one Java process, which serves the pages, the REST API and the
WebSocket. It runs on a home server, published over HTTPS through a Cloudflare Tunnel without
opening ports. A deploy script rebuilds from a Git tag and swaps the container. nginx was removed
because Spring serves the static files.

### By marking criterion

**Functionality: does it do everything a real game needs?**
Requirements F1–F7 are all met: lobby, random roles, movement and tickets, hidden information,
special tickets, every ending, and the real map. The report's traceability table maps each one to
its tests.

**Functionality: are any rules different from the board game?**
- The taxi is replaced by an e-scooter.
- Two rules were added because players aren't sitting at one table: leaving ends the game, and
  15 minutes idle ends it.
- If Mr X is boxed in, the detectives win.

**Usability: how do you know it's usable?**
A SUS score of 72.5 from 7 players (range 60–95), above the average of 68. There were two
play-test rounds: round 1 found 7 usability issues and round 2 found only 4 presentation issues,
all fixed.

**Usability: what did users complain about?**
Three of seven asked for a tutorial, which is now top of the future work. Others found the Mr X log
hard to read at first, and the reachable-node list cramped on small screens.

**Usability: does it work on phones?**
Joining works, but the board was designed for desktop, and on a phone the map and side panel don't
fit well enough to play. So at Open Day the QR code was of little use. Mobile was out of scope.

**Testing: is seven users enough?**
No, it's too few for a precise score; one person moves the mean several points. Only one played
Mr X, and all were students. Students are the target audience, though. A bigger study, ideally at
an Open Day, is future work.

**Performance: how was latency measured?**
`MultiplayerPerfTest`, an opt-in test, runs 10 concurrent six-player games and times move
submission to every client receiving the update.

**Performance: is the map benchmark fair?**
Its limits: one browser on one machine, 10 runs, and synthetic scenarios (200 nodes, 285 edges)
rather than the final board. Note that MapLibre was chosen over the faster Leaflet for consistency
(SD 0.030 s vs 0.055 s), GPU drawing, and no API key.

**Innovation: what's actually new here?**
No existing version combines a real city's network as the board, online play from a link in an
ordinary browser, and a server that keeps the hidden player's secret. SSY is one room and one
screen. Tabletop Simulator enforces no rules. The official PC game is a paid download on the
fictional board.

**Innovation: is it just a copy of a board game?**
The mechanics come from the board game, with permission. The engineering is what's new: a real
regional transport graph, real-time per-player state over the internet, and secrecy enforced
against players who can read the source code.

**Sustainability, if asked.**
Only low-emission modes are in the game. The infrastructure is one container on existing hardware,
with no cloud services or new devices. Pre-computed routes replace hundreds of routing requests per
game with one static file.

**Privacy and ethics, if asked.**
The game stores no accounts, emails or locations, and names live in memory only until the game
ends. User testing was voluntary and covered by the coordinator's approval. Responses were
anonymised by dropping the email column.

---

## 4. Handling questions (from the poster feedback)

- It's fine to say: "Good point, that wasn't considered."
- "That was declared out of scope because …" is a real answer. Out of scope from the start:
  accounts and saved games, a mobile-first layout, computer opponents, in-game chat, and any use of
  players' real locations.
- You can reframe a question slightly toward what you can answer well.
- Don't overclaim. The limits slide is there so you raise the weak points before they do.
