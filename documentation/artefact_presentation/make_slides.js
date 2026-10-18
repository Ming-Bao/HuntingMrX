// Builds slides.pptx for the ENGR489 artefact demonstration (Handbook 2026 Vol. 4, ch. 6).
// Plan: a 4-minute live demo, then about 6 minutes on these slides, then questions. Each content
// slide's footer names the handbook criteria it covers (functionality, usability and behaviour,
// testing and quality, performance, innovation). One dark look throughout, in the game's own dark
// UI colours. Every number is from the final report, spec.md or the map file; the speaker notes
// carry the detail and the likely examiner questions.
//
//   NODE_PATH=<dir with pptxgenjs> APPLY_THEME=<pptx skill>/scripts/apply_theme.js node make_slides.js
//
// Uses ../final_report/figures/board-mrx.jpg,
// states.png and sequence.png (the report's TikZ figures recoloured, sources in diagrams/:
// pdflatex X.tex, then pdftocairo -png -transp -r 600 -singlefile X.pdf ../X)
// and the poster's Lucide icons in ../poster/icons/.
const fs = require("fs");
const path = require("path");
const pptxgen = require("pptxgenjs");
const { applyTheme } = require(process.env.APPLY_THEME);

const THEME = {
  name: "Hunting Mr X Dark",
  headFontFace: "Arial",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "111821",     // slide background (the game's dark UI)
    lt1: "F3F1EA",     // main text
    dk2: "1A2430",     // cards and panels
    lt2: "C9D2DC",     // secondary text
    accent1: "F5A524", // amber (the Double ticket): headings, big numbers
    accent2: "5FB3D9", // blue: second accent
    accent3: "22C55E", // e-scooter
    accent4: "EF4444", // bus
    accent5: "8B5CF6", // train
    accent6: "06B6D4", // ferry
    hlink: "F5A524",
    folHlink: "5FB3D9",
  },
};
const HEX = THEME.colors;
const BORDER = "2E3B4A";
const PILL = "243142";
const MUTED = "8C98A5";

const here = (f) => path.join(__dirname, f);
const fig = (f) => path.join(__dirname, "..", "final_report", "figures", f);

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.333 x 7.5 in
pres.title = "Hunting Mr. X: Wellington Edition — artefact demonstration";
pres.author = "Ming Bao";
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
const C = pres.SchemeColor;

const W = 13.333, M = 0.6, CW = W - 2 * M;

pres.defineSlideMaster({
  title: "COVER",
  background: { color: C.text1 },
});
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: C.text1 },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: M, y: 0.4, w: CW, h: 0.8,
        fontFace: THEME.headFontFace, fontSize: 32, bold: true, color: C.background1, align: "left", valign: "middle", margin: 0 },
      text: "" } },
    { placeholder: { options: { name: "criteria", type: "body", x: M, y: 6.95, w: 9, h: 0.35,
        fontSize: 12, color: C.background2, valign: "middle", margin: 0 }, text: "" } },
  ],
  slideNumber: { x: W - M - 0.6, y: 6.95, w: 0.6, h: 0.35, fontSize: 12, color: C.background2, align: "right" },
});

// White Lucide icon recoloured, embedded as SVG.
function iconData(name, hex) {
  const svg = fs.readFileSync(path.join(__dirname, "..", "poster", "icons", `${name}.svg`), "utf8")
    .replace(/stroke="#FFFFFF"/, `stroke="#${hex}"`);
  return "image/svg+xml;base64," + Buffer.from(svg).toString("base64");
}
// Icon in an amber circle: the deck's one repeated motif.
function badge(slide, name, x, y, d) {
  slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: C.accent1 }, line: { type: "none" }, objectName: `badge-${name}` });
  slide.addImage({ data: iconData(name, HEX.dk1), x: x + d * 0.22, y: y + d * 0.22, w: d * 0.56, h: d * 0.56, altText: "" });
}
function card(slide, x, y, w, h, name, opts = {}) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.12, objectName: name,
    fill: { color: opts.fill || C.text2 }, line: { color: opts.line || BORDER, width: opts.lineWidth || 1 } });
}
function text(slide, t, o) {
  slide.addText(t, { isTextBox: true, margin: 0, valign: "top", color: C.background1, fontSize: 15, ...o });
}
function bullets(slide, items, o) {
  text(slide, items.map((t, i) => ({ text: t, options: { bullet: { indent: 18 }, breakLine: i < items.length - 1, paraSpaceAfter: 6 } })), o);
}
const CODE_BG = "0B1118";
// Monospace block with light JSON colouring: keys blue, strings amber, // comments muted.
function code(slide, lines, o) {
  const runs = [];
  const TOKEN = /("(?:[^"\\]|\\.)*")(\s*:)?|(\/\/.*$)/g;
  lines.forEach((line, i) => {
    let last = 0, m;
    const push = (t, color, extra = {}) => t && runs.push({ text: t, options: { color, ...extra } });
    while ((m = TOKEN.exec(line))) {
      push(line.slice(last, m.index), C.background1);
      if (m[3]) push(m[3], MUTED, { italic: true });
      else { push(m[1], m[2] ? C.accent2 : C.accent1); push(m[2] || "", C.background1); }
      last = TOKEN.lastIndex;
    }
    push(line.slice(last) || (last === 0 ? " " : ""), C.background1);
    runs[runs.length - 1].options.breakLine = i < lines.length - 1;
  });
  text(slide, runs, { fontFace: "Courier New", lineSpacingMultiple: 1.05, ...o });
}
function content(section, title, criteria) {
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: section });
  s.addText(title, { placeholder: "title" });
  s.addText(`Criteria: ${criteria}`, { placeholder: "criteria" });
  return s;
}

// ---------------------------------------------------------------- 1. Cover
pres.addSection({ title: "Bridge from the demo" });
{
  const s = pres.addSlide({ masterName: "COVER", sectionTitle: "Bridge from the demo" });
  text(s, "ENGR489 · ARTEFACT DEMONSTRATION", { x: M, y: 1.35, w: 5.4, h: 0.35, fontSize: 13, bold: true, color: C.accent1, charSpacing: 2 });
  text(s, "Hunting Mr. X", { x: M, y: 1.85, w: 5.6, h: 0.95, fontFace: THEME.headFontFace, fontSize: 50, bold: true });
  text(s, "Wellington Edition", { x: M, y: 2.8, w: 5.6, h: 0.6, fontFace: THEME.headFontFace, fontSize: 28, bold: true, color: C.accent1 });
  text(s, "A real-time online multiplayer hidden-movement game, played in the browser on a real map of Wellington.",
    { x: M, y: 3.65, w: 5.3, h: 1.1, fontSize: 18, color: C.background2 });
  text(s, [
    { text: "Ming Bao", options: { bold: true, color: C.background1, breakLine: true } },
    { text: "Supervised by Jens Dietrich and Stuart Marshall", options: { breakLine: true } },
    { text: "Game mechanics used with Ravensburger’s permission" },
  ], { x: M, y: 5.0, w: 5.4, h: 1.0, fontSize: 14, color: C.background2, paraSpaceAfter: 4 });
  s.addImage({ path: fig("board-mrx.jpg"), x: 6.3, y: 1.6, w: 6.43, h: 3.84, rounding: false,
    altText: "The game board in Mr X’s view: a dark map of Wellington with coloured routes and a side panel of tickets and moves" });
  s.addNotes(`SAY (about 35 seconds):
"Quick reset: one player moves in secret, the rest hunt them, on real Wellington transport. Built as a new Open Day demo, because the old one was over five years old."

DETAIL:
- After the 4-minute demo there are about 6 minutes for slides 2 to 10: roughly 35 seconds each.
- One player is Mr X and moves in secret; the rest are detectives working together.
- The board is 216 real Wellington locations joined by e-scooter, bus, train and ferry routes.
- Anyone joins from a desktop browser with a link, a six-character code or a QR code.

IF ASKED:
- The name? The mechanics come from a board game by Ravensburger, used with their permission for this non-commercial project. Their brand name isn't used, so it's called Hunting Mr. X.
- Why Wellington? A familiar map makes the game instantly recognisable to local players and Open Day visitors.
- What's new? SSY, Tabletop Simulator and the official PC game all use the fictional printed board, and none is a free online game opened from a link. GeoGuessr and Pokémon GO use real maps but have no hidden-movement strategy.`);
}

// ---------------------------------------------------------------- 2. State machine
{
  const s = content("Bridge from the demo", "The game as a state machine", "Functionality · Testing and quality");
  const dw = 7.5, dh = dw / (2006 / 1321);
  s.addImage({ path: here("states.png"), x: M, y: 1.5, w: dw, h: dh,
    altText: "Game state machine: Lobby, then in progress alternating Mr X’s turn and detectives’ turns for up to 24 rounds, ending in Detectives win, Mr X wins or Aborted" });
  const cards = [
    ["shield-check", "Checked on every request", "A move in the wrong phase or out of turn is rejected and changes nothing."],
    ["refresh-cw", "Turns within a round", "A Double keeps Mr X’s turn; detectives who can’t move are skipped."],
    ["trophy", "Three ways to end", "Mr X caught or boxed in, Mr X survives round 24, or the game is aborted."],
    ["flag", "Changed during the project", "Pause-on-disconnect removed; boxed-in win added; round end became a transition."],
  ];
  const cx = M + dw + 0.35, cw = W - M - cx, ch = 1.15;
  cards.forEach(([ic, head, body], i) => {
    const y = 1.5 + i * (ch + 0.2);
    card(s, cx, y, cw, ch, `state-${i + 1}`);
    badge(s, ic, cx + 0.22, y + 0.3, 0.5);
    text(s, head, { x: cx + 0.9, y: y + 0.12, w: cw - 1.1, h: 0.38, fontSize: 15, bold: true, color: C.accent1 });
    text(s, body, { x: cx + 0.9, y: y + 0.5, w: cw - 1.1, h: 0.58, fontSize: 13, color: C.background2 });
  });
  s.addNotes(`SAY (about 35 seconds):
"Every request is checked against this state machine. A game starts in the lobby; once the host starts it, Mr X and the detectives take turns for up to 24 rounds. It ends one of three ways: the detectives catch or box in Mr X, Mr X survives round 24, or the game is aborted when someone leaves or sits idle for 15 minutes. A request in the wrong state is rejected and changes nothing."

DETAIL:
- The rules live in one plain Java class, Game, which checks the state before every operation.
- A request in the wrong phase gets 409 (for example "Game is not in progress"); a move from the wrong player gets 403 "Not your turn". Either way nothing changes.
- Lobby: the host starts the game once there are at least 2 players. If the host leaves the lobby, it closes for everyone.
- In progress: Mr X moves first each round, then each detective in turn. The first leg of a Double keeps it Mr X's turn. A detective with no legal move is skipped. After the last detective, the next round starts.
- Before each of Mr X's turns the server checks he has a legal move; if detectives hold every node next to him, the detectives win.
- Endings: detectives catch Mr X or box him in; Mr X is still free after round 24; or the game is aborted because a player left or the current player was idle for 15 minutes.
- It changed during the project: the "paused on disconnect" state was removed because WebSockets dropped too often in user testing; "boxed in" was added as a detective win after a review found games froze there; "round end" became a transition because nothing waits there.

IF ASKED:
- What about a dropped connection? It isn't a transition at all. The player keeps their place and catches up when they reconnect.
- Why does one player leaving end the game? Without Mr X there's no game; without a detective it's unbalanced, because Mr X's Invisible tickets are set by the number of detectives. It's also the simplest rule to make correct.
- Why a 15-minute idle limit and no auto-skip? Friends discuss moves for minutes, so 15 minutes only catches abandoned tabs. Without it a closed tab leaves everyone waiting and the game in memory forever.`);
}

// ---------------------------------------------------------------- 3. Architecture
pres.addSection({ title: "How it is built" });
{
  const s = content("How it is built", "The server is the only source of truth", "Functionality · Testing and quality");
  const pw = 4.7, py = 1.45, ph = 3.35;
  const panel = (x, head, items, hi) => {
    card(s, x, py, pw, ph, head);
    text(s, head.toUpperCase(), { x: x + 0.3, y: py + 0.25, w: pw - 0.6, h: 0.35, fontSize: 13, bold: true, color: C.accent1, charSpacing: 1 });
    items.forEach((t, i) => {
      const iy = py + 0.75 + i * 0.62;
      s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: x + 0.3, y: iy, w: pw - 0.6, h: 0.5, rectRadius: 0.08,
        fill: { color: PILL }, line: i === hi ? { color: C.accent1, width: 1.5 } : { type: "none" }, objectName: `pill-${t}` });
      text(s, t, { x: x + 0.5, y: iy, w: pw - 1.0, h: 0.5, fontSize: 15, valign: "middle" });
    });
  };
  panel(M, "Browser · one per player", ["Vue 3 pages", "One reactive game store", "MapLibre GL map", "api.ts: REST calls + STOMP"], -1);
  panel(W - M - pw, "Spring Boot server · one container",
    ["REST controllers", "GameService: games, locks, tokens", "Rules core in plain Java", "STOMP broker, in memory"], 2);
  const mx = M + pw + 0.25, mw = W - 2 * M - 2 * pw - 0.5;
  text(s, "Actions as REST\n“move to 42 by bus”", { x: mx, y: 1.75, w: mw, h: 0.65, fontSize: 14, color: C.background2, align: "center" });
  s.addShape(pres.shapes.RIGHT_ARROW, { x: mx + 0.3, y: 2.45, w: mw - 0.6, h: 0.45, fill: { color: C.accent1 }, line: { type: "none" }, objectName: "arrow-actions" });
  s.addShape(pres.shapes.LEFT_ARROW, { x: mx + 0.3, y: 3.3, w: mw - 0.6, h: 0.45, fill: { color: C.accent2 }, line: { type: "none" }, objectName: "arrow-state" });
  text(s, "State pushed over STOMP:\na separate view per player", { x: mx, y: 3.85, w: mw, h: 0.65, fontSize: 14, color: C.background2, align: "center" });

  const decisions = [
    ["shield-check", "Authoritative server", "If the host’s browser ran the game, it would hold the secret."],
    ["layers", "Rules with no framework", "Every rule is unit-tested directly, with no Spring or mocks."],
    ["refresh-cw", "One lock per game", "Requests to one game wait in turn; games run in parallel."],
  ];
  const dw = (CW - 2 * 0.3) / 3, dy = 5.05, dh = 1.6;
  decisions.forEach(([ic, head, body], i) => {
    const x = M + i * (dw + 0.3);
    card(s, x, dy, dw, dh, `decision-${i + 1}`);
    badge(s, ic, x + 0.25, dy + 0.25, 0.5);
    text(s, head, { x: x + 0.9, y: dy + 0.25, w: dw - 1.1, h: 0.5, fontSize: 17, bold: true, valign: "middle" });
    text(s, body, { x: x + 0.25, y: dy + 0.85, w: dw - 0.5, h: 0.65, fontSize: 14, color: C.background2 });
  });
  s.addNotes(`SAY (about 35 seconds):
"The browser never decides anything. It sends an intention as a REST call ('move to 42 by bus'), and the server checks it and pushes each player their own view over STOMP. The rules sit in plain Java with no framework, so I can test every rule directly. Each game has its own lock."

DETAIL:
- Actions are REST because errors are simple: an illegal move or a move out of turn gets an error code and a message back. It's also easy to test.
- Updates are STOMP over a WebSocket. On the board each browser subscribes to two private topics named by its secret token: its own view and its legal moves. Anything a browser sends on the socket is dropped.
- The rules core (Game, Player, MapGraph) has no Spring. This came from a mid-project rewrite; before it, rules were spread through the Spring service and a class hierarchy and needed mocks. The random source and clock are injected, so tests can fix roles or jump 15 minutes ahead.
- Each game has its own lock. A game gets only a few requests a second, so serialising them costs little.

IF ASKED:
- Why not polling or Server-Sent Events? Polling alone delays each move by up to one interval (6 s). SSE could push as fast, but STOMP gives one topic per game and per player for free, and SockJS falls back to HTTP where WebSockets are blocked. Polling every 6 s stays as a backup.
- Why Java, not Node? Spring has a STOMP broker built in, and Java has mature coverage (JaCoCo) and mutation (PIT) tools. The cost is no shared types with TypeScript, covered by the OpenAPI contract (slide 6).
- Why not let the host's browser run the game? It would hold Mr X's position whenever the host is a detective, and the game would die when the host closed the tab.`);
}

// ---------------------------------------------------------------- 4. Secret
{
  const s = content("How it is built", "Keeping Mr X’s position secret", "Functionality · Testing and quality");
  const qh = 6.2 / (2104 / 1150);
  s.addImage({ path: here("sequence.png"), x: M, y: 1.6, w: 6.2, h: qh,
    altText: "Sequence of one move by Mr X: his browser posts the move with its token, the server checks it, then sends Mr X his full view and each detective a view with Mr X set to null" });
  text(s, "One move by Mr X. The server builds each player’s view separately; outside a reveal round only Mr X’s own view holds his node.",
    { x: M, y: 1.6 + qh + 0.15, w: 6.2, h: 0.55, fontSize: 12, color: C.background2 });
  const rx = 7.2, rw = W - M - rx;
  text(s, "Found late in the project", { x: rx, y: 1.5, w: rw, h: 0.4, fontSize: 18, bold: true, color: C.accent1 });
  bullets(s, ["Public player IDs worked as passwords", "The broker allowed wildcard subscriptions", "The broker relayed client-sent messages"],
    { x: rx, y: 1.95, w: rw, h: 1.15, fontSize: 15 });
  text(s, "Testers used AI to read the open-source code and call the API to find Mr X.",
    { x: rx, y: 3.1, w: rw, h: 0.55, fontSize: 14, italic: true, color: C.background2 });
  text(s, "The fix", { x: rx, y: 3.8, w: rw, h: 0.4, fontSize: 18, bold: true });
  bullets(s, ["A secret token per player, sent only to them", "Private channels named by the token; exact match only", "Clients can only listen on the socket"],
    { x: rx, y: 4.25, w: rw, h: 1.15, fontSize: 15 });
  card(s, rx, 5.6, rw, 0.95, "proof", { fill: C.text1, line: C.accent1 });
  text(s, [{ text: "Proof: ", options: { bold: true, color: C.accent1 } },
    { text: "both socket attacks are tests. They leaked Mr X on the old setup and pass now." }],
    { x: rx + 0.25, y: 5.6, w: rw - 0.5, h: 0.95, fontSize: 15, valign: "middle" });
  s.addNotes(`SAY (about 35 seconds):
"Hiding Mr X on screen isn't enough. A detective must be unable to *get* his position. Late in the project, testers used AI on our open-source code to find him through the API. Three holes: IDs worked as passwords, the broker allowed wildcard subscriptions, and clients could publish. I fixed them with secret per-player tokens and exact-match private channels, and the attacks are now tests."

DETAIL:
- Game.viewFor(viewer) builds a separate snapshot for each player. Mr X sees everything; anyone else sees his node only after he finishes a move on a reveal round.
- After every change the server sends the public view to the game topic, each player's own view to their private topic, and the legal moves to whoever moves next.
- The three holes: public IDs doubled as credentials; Spring's simple broker honoured wildcard subscriptions like /topic/games/{id}/players/** and delivered every private view; and it relayed messages that clients sent to a topic as if the server had sent them.
- It was a real risk because the project is open source and AI assistants make the code easy to read. A couple of testers did exactly that.
- The fix: a random token only in your own create or join response, sent in the X-Player-Token header; the public ID grants nothing. Private topics are named by the token and matched exactly. The server drops every message a client sends.

IF ASKED:
- What's the lesson? The existing tests only checked normal use. When a secret is a core requirement, tests have to try to break it. And a framework's convenient defaults aren't necessarily secure.
- Why no accounts or logins? They add friction to a casual game and protect nothing a per-game secret doesn't.
- Could someone guess a token? Tokens are random UUIDs from a secure random source, and only their owner ever receives one.`);
}

// ---------------------------------------------------------------- 5. The map file
{
  const s = content("How it is built", "The map: one static JSON file", "Functionality · Performance");
  // Real entries from backend/src/main/resources/static/map.json: node 8 and the edge 8–205.
  const cx = M, cw = 7.0, cy = 1.45, ch = 5.2;
  card(s, cx, cy, cw, ch, "map-json", { fill: CODE_BG });
  code(s, [
    '{',
    '  "nodes": [',
    '    { "id": 8, "label": "8",',
    '      "lat": -41.1377269, "lng": 174.843492,',
    '      "offRoad": true },   // map creator only',
    '    …                      // 216 nodes',
    '  ],',
    '  "edges": [',
    '    { "from": 8, "to": 205,',
    '      "modes": ["BUS", "ESCOOTER"],',
    '      "coordinates": [     // the path to draw',
    '        [174.843492, -41.1377269],',
    '        [174.851836, -41.136573]',
    '      ] },',
    '    …                      // 372 edges',
    '  ]',
    '}',
  ], { x: cx + 0.35, y: cy + 0.4, w: cw - 0.7, h: ch - 0.8, fontSize: 14, lineSpacingMultiple: 1.15 });
  const fx = cx + cw + 0.35, fw = W - M - fx;
  const facts = [
    ["216 nodes · 372 edges", "About 28,000 road points; 1.86 MB in all."],
    ["The server reads four fields", "id, from, to and modes, into node → neighbour → set of modes. Edges are undirected."],
    ["Checked at start-up", "An edge naming an unknown node or mode stops the server from starting."],
    ["Routes computed once, offline", "From OpenStreetMap, by my map-creator tool. No paid routing API."],
  ];
  const fh = (ch - 3 * 0.2) / 4;
  facts.forEach(([head, body], i) => {
    const y = cy + i * (fh + 0.2);
    card(s, fx, y, fw, fh, `map-fact-${i + 1}`);
    text(s, head, { x: fx + 0.3, y: y + 0.15, w: fw - 0.6, h: 0.4, fontSize: 16, bold: true, color: C.accent1 });
    text(s, body, { x: fx + 0.3, y: y + 0.55, w: fw - 0.6, h: fh - 0.65, fontSize: 14, color: C.background2 });
  });
  s.addNotes(`SAY (about 35 seconds):
"You've seen the map; this is the file behind it. One JSON file: nodes with a position, and edges with their transport modes and a road path for drawing. The server only reads the ids and modes, and refuses to start if an edge names a node or mode that doesn't exist. The paths were computed once, offline, from OpenStreetMap by my own map-creator tool, so there's no paid routing API."

DETAIL:
- Both entries on the slide are real: node 8 and the edge from 8 to 205.
- Nodes have id, lat, lng, label and offRoad. Edges have from, to, modes and coordinates.
- offRoad marks the 8 train-station nodes, which the map creator placed on the rail line rather than on a road. When an edge joins a station to a road node, the map creator draws it as a straight line instead of routing it along roads. Node 8 is a station, so this edge has only two points; most edges have dozens. The game itself ignores the flag.
- MapGraph.parse reads id, from, to and modes into node -> neighbour -> set of modes. Edges are undirected, and modes on a repeated pair are merged. Coordinates are only for drawing; the server serves the raw file at GET /api/map.
- The map creator snaps nodes to roads, routes each edge with Dijkstra, and generates in layers: train first, then bus clusters, then e-scooter edges of up to 900 m. A road-use registry stops two edges claiming the same street. After that come manual editing and a connected-components check so every node is reachable. Building the map was the hardest part of the project.

IF ASKED:
- Why pre-compute instead of a routing API? There are about 400 edges, billed per request beyond a small free allowance, and an early Leaflet routing plug-in became unresponsive with a few hundred routes. The network never changes during a game.
- Is it accurate? Train and ferry edges follow the real lines. Bus and e-scooter edges follow real roads but aren't checked against real bus routes or scooter zones.
- Isn't 1.86 MB wasteful? Yes: it's sent uncompressed at full precision. HTTP compression and rounding to about one metre would shrink it several-fold.`);
}

// ---------------------------------------------------------------- 6. REST and STOMP
{
  const s = content("How it is built", "REST and STOMP: what goes over the wire", "Functionality · Performance");
  // Captured from a local game (Bob is Mr X; Alice and Carol are detectives). Ids shortened.
  const y = 1.45, h = 3.85, lw = 5.3, rx = M + lw + 0.3, rw = W - M - rx;
  card(s, M, y, lw, h, "rest", { fill: CODE_BG });
  text(s, "REST · Alice, a detective, moves", { x: M + 0.3, y: y + 0.2, w: lw - 0.6, h: 0.4, fontSize: 16, bold: true, color: C.accent1 });
  code(s, [
    'POST /api/games/{gameId}/moves',
    'X-Player-Token: {Alice’s secret token}',
    '',
    '{ "toNodeId": 87, "ticket": "ESCOOTER" }',
    '',
    '200  Alice’s new view of the game',
    '400  { "error":',
    '       "Ticket type FERRY not valid',
    '        for this edge" }',
    '403  { "error": "Not your turn" }',
  ], { x: M + 0.3, y: y + 0.75, w: lw - 0.6, h: h - 0.95, fontSize: 12 });
  card(s, rx, y, rw, h, "stomp", { fill: CODE_BG });
  text(s, "STOMP · what Carol receives straight after", { x: rx + 0.3, y: y + 0.2, w: rw - 0.6, h: 0.4, fontSize: 16, bold: true, color: C.accent2 });
  code(s, [
    'MESSAGE',
    'destination:/topic/games/{gameId}/players/{Carol’s token}',
    '',
    '{ "phase": "IN_PROGRESS", "round": 1,',
    '  "players": [',
    '    { "name": "Bob", "role": "MR_X", "nodeId": null,',
    '      "tickets": { "ESCOOTER": -1, …, "BLACK": 2 } },',
    '    { "name": "Alice", "role": "DETECTIVE", "nodeId": 87,',
    '      "tickets": { "ESCOOTER": 11, "BUS": 8, … } },',
    '    { "name": "Carol", …, "nodeId": 181, … }',
    '  ],',
    '  "mrXLog": [{ "round": 1, "ticketUsed": "ESCOOTER",',
    '               "nodeId": null }], … }',
  ], { x: rx + 0.3, y: y + 0.75, w: rw - 0.6, h: h - 0.95, fontSize: 12 });
  card(s, M, 5.45, CW, 0.6, "valid-moves", { fill: CODE_BG });
  text(s, "Whoever moves next also gets:", { x: M + 0.3, y: 5.45, w: 3.0, h: 0.6, fontSize: 14, bold: true, color: C.accent2, valign: "middle" });
  code(s, ['/valid-moves  [{ "nodeId": 87, "ticketOptions": ["ESCOOTER", "BUS"] }, …]'],
    { x: M + 3.3, y: 5.45, w: CW - 3.6, h: 0.6, fontSize: 12, valign: "middle" });
  card(s, M, 6.2, CW, 0.6, "wire-note", { fill: C.text1, line: C.accent1 });
  text(s, [{ text: "Whole snapshots, built per player: ", options: { bold: true, color: C.accent1 } },
    { text: "Bob gets the same message with his own node. Games live in memory; an OpenAPI file documents every endpoint and topic." }],
    { x: M + 0.25, y: 6.2, w: CW - 0.5, h: 0.6, fontSize: 14, valign: "middle" });
  s.addNotes(`SAY (about 35 seconds):
"These are real messages from a local game. Alice moves with a REST POST: her secret token in a header and a two-field body. A bad move gets a clear error like 'Not your turn'. Straight after, Carol's private STOMP topic gets the whole game state. Mr X's node is null because she's a detective. Every message is a whole snapshot built for that player, so a missed one can't leave anyone out of sync. There's no database: games live in memory, and an OpenAPI file documents every endpoint and topic."

DETAIL:
- These are real messages from a local game: Bob was Mr X, Alice and Carol detectives. Only the long ids and tokens are shortened.
- REST: Alice's move is a POST with her token in X-Player-Token and a two-field body. A legal move returns 200 and her new view. Both errors are real: FERRY on a road edge gave 400, and a detective moving during Mr X's turn gave 403. A rejected move changes nothing.
- STOMP: straight after, Carol's private topic (named by her token) got the whole game state. Bob's nodeId is null because Carol is a detective and it isn't a reveal round. The log shows only the ticket he used. -1 means unlimited, so Mr X and the detectives share one ticket model. Alice's e-scooter tickets went from 12 to 11.
- The same move also went to Bob's topic, with his node (137), and to Alice's. The valid-moves example is Alice's own list from just before her move: node 87 by e-scooter or bus.
- This message is about 820 bytes.

IF ASKED:
- Why whole snapshots, not diffs? A client that misses one just takes the next, or the 6-second REST refresh, and can never end up inconsistent. The cost is slightly bigger messages.
- Why no database? A game lasts under an hour and only exists while its players are online, so games live in a ConcurrentHashMap. A restart ends every game; that's accepted under C2 (one container, no database) and listed as future work.
- How do Java and TypeScript agree on the format? openapi.yaml documents every REST endpoint and STOMP topic, and a pre-commit hook rejects any commit that changes the API without updating it.`);
}

// ---------------------------------------------------------------- 7. Testing
pres.addSection({ title: "How we know it works" });
{
  const s = content("How we know it works", "Testing: 135 backend tests in six layers", "Testing and quality");
  const head = (t, align) => ({ text: t, options: { bold: true, color: C.accent1, fill: { color: C.text2 }, align } });
  const rows = [
    ["Unit", "82", "Every rule in Game, Player, MapGraph"],
    ["Mock", "17", "Locking, tokens, what is published where"],
    ["Lifecycle", "8", "Scripted complete games"],
    ["Integration", "20", "HTTP errors, STOMP filtering, attacks"],
    ["Functional", "4", "Full games over real HTTP and STOMP"],
    ["Property, fuzz", "4", "Random play and junk input break no rule"],
  ];
  s.addTable([
    [head("Layer"), head("Tests", "right"), head("What it checks")],
    ...rows.map(([a, b, c]) => [{ text: a }, { text: b, options: { align: "right" } }, { text: c }]),
  ], { x: M, y: 1.5, w: 7.3, colW: [1.75, 0.9, 4.65], rowH: 0.52, fontSize: 15, fontFace: THEME.bodyFontFace,
    color: C.background1, valign: "middle", border: { type: "solid", pt: 0.75, color: BORDER }, margin: [0, 0.12, 0, 0.12] });
  const stats = [
    ["99.2%", "of lines covered (97.8% of branches), up from 65% before the rewrite"],
    ["199 / 199", "mutants killed by PIT mutation testing"],
    ["8 bugs", "found in a late review, 3 high severity; each fix has a test that failed before it"],
  ];
  const sx = 8.4, sw = W - M - sx;
  stats.forEach(([n, l], i) => {
    const y = 1.4 + i * 1.45;
    text(s, n, { x: sx, y, w: sw, h: 0.75, fontFace: THEME.headFontFace, fontSize: 44, bold: true, color: C.accent1 });
    text(s, l, { x: sx, y: y + 0.75, w: sw, h: 0.6, fontSize: 14, color: C.background2 });
  });
  card(s, M, 5.85, CW, 0.8, "bugs", { fill: C.text1, line: C.accent1 });
  text(s, [{ text: "None of the tests need a browser. " },
    { text: "Frontend: tested by hand only.", options: { bold: true, color: C.accent1 } }],
    { x: M + 0.3, y: 5.85, w: CW - 0.6, h: 0.8, fontSize: 16, valign: "middle" });
  s.addNotes(`SAY (about 35 seconds):
"135 backend tests in six layers, none needing a browser. Coverage is 99.2%, but coverage only shows what runs, so I also used mutation testing: 199 of 199 mutants killed. A late review found 8 bugs, and each fix has a test that failed before it. The weak spot is the frontend, which I tested by hand."

DETAIL:
- None of the tests need a browser. Unit tests build games on hand-drawn maps of five to eight nodes with fixed start positions, so each rule has an exact expected outcome: for example, both legs of a double move happen before any detective moves, and a detective with no legal move is skipped without spending a ticket.
- Integration tests run the real server over HTTP and STOMP. Functional tests play complete games with one HTTP and one STOMP client per player.
- Property and fuzz tests let the computer play instead of me. One plays 300 random games on the real map (random number of players, random legal moves, the odd Double) and after every move checks rules that must always be true: no ticket count goes negative, Mr X never shares a node with a detective, a detective's view never shows Mr X outside a reveal, and every game ends. "Seeded" only means the random choices come from numbered seeds 1 to 300, so any failure can be replayed exactly.
- Fuzz tests throw junk at it: garbage moves, odd names, and random requests to every endpoint. Each must be rejected cleanly and never cause a server error.
- The 8 bugs from the late review (all in documentation/backend-fixes.md, each with the tests that cover it):
  1. High: the game froze when Mr X was boxed in, because there was no rule for it. Now the detectives win. It also made an end-to-end test fail at random: 30% of four-player starts on the small test map box him in on turn 1.
  2. High: any player could see Mr X's position and act as other players. Public player IDs worked as passwords, private channels were named by those IDs, the broker accepted wildcard subscriptions, and clients could publish fake messages. Fixed with secret tokens (slide 4).
  3. High: a detective leaving mid-game froze the game (if it was their turn) or skipped the next detective. Now anyone leaving ends the game for everyone.
  4. Medium: Mr X could chain Double tickets into three moves in one turn. A second Double during a Double is now rejected.
  5. Medium: a plain "DOUBLE" ticket skipped the transport check and hid the mode, so it worked as a free Invisible ticket. The first leg must now be DOUBLE_ plus a real ticket.
  6. Medium: the host leaving the lobby deleted the game silently, stranding everyone else. Now the lobby closes for everyone with "The host left the game".
  7. Low: lobby actions raced each other; two joins at once could overfill a game. Now every operation on a game runs under that game's lock. A test fires 40 joins at once (on 20 threads) at a six-player game that already has its host, and checks exactly 5 get in.
  8. Low: missing request fields caused a 500 server error instead of a 400. Requests are now validated, and the fuzz test checks no request ever causes a server error.

IF ASKED:
- Why did coverage jump from 65% to 99%? Mostly from moving the rules out of Spring, not from writing more tests; before, the rules engine was mocked out of most tests.
- What's mutation testing? PIT flips comparisons, deletes calls and so on, and checks that some test fails. Coverage says what runs; mutation testing says whether the tests would notice it breaking.
- What's a property test? Instead of writing one expected outcome, you state a rule that must always hold, and the computer plays hundreds of random games trying to break it.
- Why no frontend tests? Time went to the backend, where the rules and the secret live. The browser holds no game logic, so a frontend bug is a display bug. It was tested by hand after every change, plus Playwright for scripted browser checks; Selenium was tried first and was flaky.`);
}

// ---------------------------------------------------------------- 8. Performance and usability
{
  const s = content("How we know it works", "Performance and usability", "Performance · Usability and behaviour");
  const stats = [
    ["8.3 ms", "95th-percentile move delivery to all players", "10 concurrent six-player games · worst 36.6 ms · target under 500 ms"],
    ["72.5", "SUS score, above the average of 68", "7 players · range 60–95 · 11 changes from two play-test rounds"],
    ["≤ 6 s", "behind after a dropped connection", "Automatic reconnect, then a full-state refresh over REST"],
  ];
  const lw = 5.4, lh = 1.6;
  stats.forEach(([n, l, sub], i) => {
    const y = 1.45 + i * (lh + 0.2);
    card(s, M, y, lw, lh, `stat-${i + 1}`);
    text(s, n, { x: M + 0.3, y: y + 0.12, w: lw - 0.6, h: 0.65, fontFace: THEME.headFontFace, fontSize: 36, bold: true, color: C.accent1 });
    text(s, l, { x: M + 0.3, y: y + 0.78, w: lw - 0.6, h: 0.35, fontSize: 15, bold: true });
    text(s, sub, { x: M + 0.3, y: y + 1.13, w: lw - 0.6, h: 0.35, fontSize: 12, color: C.background2 });
  });
  const cx = M + lw + 0.3, cw = W - M - cx, ch = 3 * lh + 2 * 0.2;
  card(s, cx, 1.45, cw, ch, "benchmark");
  s.addChart(pres.charts.BAR, [
    { name: "Google Maps", labels: ["Baseline (4 nodes)", "Stress (200 nodes)"], values: [0.70, 2.69] },
    { name: "Leaflet", labels: ["Baseline (4 nodes)", "Stress (200 nodes)"], values: [0.23, 2.21] },
    { name: "MapLibre GL", labels: ["Baseline (4 nodes)", "Stress (200 nodes)"], values: [0.40, 2.31] },
  ], {
    x: cx + 0.2, y: 1.6, w: cw - 0.4, h: ch - 0.95, barDir: "col", barGrouping: "clustered", barGapWidthPct: 60,
    chartColors: [MUTED, HEX.lt2, HEX.accent1],
    showTitle: true, title: "Map render time in seconds, mean of 10 runs", titleColor: HEX.lt1, titleFontSize: 15, titleFontFace: "+mn-lt",
    showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00", dataLabelColor: HEX.lt1, dataLabelFontSize: 12, dataLabelFontFace: "+mn-lt",
    catAxisLabelColor: HEX.lt2, catAxisLabelFontSize: 13, catAxisLabelFontFace: "+mn-lt", catAxisLineShow: false,
    valAxisHidden: true, valAxisMinVal: 0, valAxisMaxVal: 3.2, valGridLine: { style: "none" }, catGridLine: { style: "none" },
    showLegend: true, legendPos: "b", legendColor: HEX.lt2, legendFontSize: 13, legendFontFace: "+mn-lt",
    altText: "Bar chart of mean render times. Baseline: Google Maps 0.70 s, Leaflet 0.23 s, MapLibre GL 0.40 s. Stress: Google Maps 2.69 s, Leaflet 2.21 s, MapLibre GL 2.31 s.",
  });
  text(s, "Leaflet was slightly faster; MapLibre was chosen as the most consistent under load (SD 0.030 s against 0.055 s), drawing on the GPU, with no API key.",
    { x: cx + 0.3, y: 1.45 + ch - 0.8, w: cw - 0.6, h: 0.65, fontSize: 13, color: C.background2 });
  s.addNotes(`SAY (about 35 seconds):
"A move reaches every player in 8.3 ms at the 95th percentile, across 10 games at once, against a 500 ms target. The usability score is 72.5, above the average of 68. MapLibre wasn't the fastest map library in the benchmark, but it was the most consistent, and it's free."

DETAIL:
- Latency comes from MultiplayerPerfTest, an opt-in test: 10 concurrent six-player games, timing each move from submission until every client has the update. p95 8.3 ms, worst 36.6 ms, against a 500 ms target.
- The map benchmark ran before any map code: Python and Selenium driving Firefox against a local server, one warm-up, 10 runs per library per scenario. The stress scenario had 200 real road endpoints and 285 edges. Means: Google Maps 2.69 s, Leaflet 2.21 s, MapLibre 2.31 s.
- SUS: 7 players after a full game (six detectives, one Mr X). Detectives 72.9, Mr X 70.0. Three of seven asked for a tutorial.
- Play-testing round 1 fixed: a name-length limit, zoom locked to Wellington, a taller move list, pie-chart nodes, a game-over screen, more distinct colours and a selection bug. Round 2 found only presentation issues: a centred turn badge, "Next reveal: Round n", reveal rows in the log, and "Round 12" instead of "R12".
- Resilience: the game first paused on disconnect with a 60 s grace period, but WebSockets dropped far more than expected on uni and home Wi-Fi. Now a dropped connection isn't a game event: the client reconnects, re-subscribes and fetches the full state over REST, and a REST refresh also runs every 6 s.

IF ASKED:
- Why MapLibre over the faster Leaflet? It was the most consistent under load (SD 0.030 s against 0.055 s), it draws data layers on the GPU so colour per mode, offsets and pie icons are layer properties rather than thousands of page elements, and it needs no API key.
- Is the benchmark fair? Its limits are one browser on one machine, 10 runs, and synthetic scenarios rather than the final board.
- Is seven users enough? No: one person moves the mean several points, only one played Mr X, there's no before-and-after comparison, and all were students. Students are the target audience, though.`);
}

// ---------------------------------------------------------------- 9. AI
{
  const s = content("How we know it works", "How AI was used, and how it was checked", "Testing and quality");
  const cols = [
    ["gamepad-2", "What Claude Code did", C.background1, null,
      ["Drafted code and tests from written specs", "Reviewed code: found the Mr X leak and the boxed-in freeze",
        "Renames and refactors across many files", "Kept docs and diagrams in step with the code"]],
    ["flag", "Where it went wrong", C.accent1, null,
      ["Plausible but wrong docs, describing a feature already removed", "Added layers and abstractions nobody needed",
        "Early tests checked getters, not behaviour"]],
    ["clipboard-check", "The checks", C.accent1, "Not every AI change was read line by line, so the safety net is automatic:",
      ["The 135-test suite", "Mutation testing, which catches tests that check nothing", "A pre-commit hook: no API change without the OpenAPI file"]],
  ];
  const cw = (CW - 2 * 0.3) / 3, y = 1.5, h = 5.15;
  cols.forEach(([ic, head, hc, lead, items], i) => {
    const x = M + i * (cw + 0.3);
    card(s, x, y, cw, h, `ai-${i + 1}`, i === 2 ? { line: C.accent1, lineWidth: 1.5 } : {});
    badge(s, ic, x + 0.3, y + 0.3, 0.55);
    text(s, head, { x: x + 1.0, y: y + 0.3, w: cw - 1.2, h: 0.55, fontSize: 18, bold: true, color: hc, valign: "middle" });
    let by = y + 1.15;
    if (lead) {
      text(s, lead, { x: x + 0.3, y: by, w: cw - 0.6, h: 0.75, fontSize: 14, italic: true, color: C.background2 });
      by += 0.85;
    }
    bullets(s, items, { x: x + 0.3, y: by, w: cw - 0.6, h: y + h - by - 0.25, fontSize: 17, paraSpaceAfter: 14 });
  });
  s.addNotes(`SAY (about 35 seconds):
"I used Claude Code throughout. It was good at reviews and big renames; it found two of the worst bugs. It also wrote confident wrong docs and tests that checked nothing. Because I didn't read every line, the checks are automatic: tests, mutation testing, and a hook that blocks API changes without the spec."

DETAIL:
- Claude Code, a command-line AI coding assistant, was used throughout. It was most useful for repetitive changes across many files (renaming something in 20 places) and for reviewing code.
- An AI-assisted review found two of the most serious bugs: the security weakness that let players see Mr X's position, and the freeze when Mr X had no legal move.
- Where it went wrong: plausible but wrong documentation, such as describing the pause-on-disconnect feature after it was removed; layers and abstractions nobody needed; and early tests that checked getters rather than behaviour, which a coverage number alone wouldn't reveal.
- The checks: the test suite; PIT mutation testing, with all 199 mutants killed, so the tests really check behaviour; and a local pre-commit hook that rejects any commit changing the API without updating openapi.yaml.

IF ASKED:
- Did code and tests come from the same prompt? [fill in honestly from how you actually worked; the report says it drafted code and tests from written specifications.] Either way, mutation testing, the attack tests and the random-game rule checks are the independent check that the tests aren't just mirroring the code.
- Was AI used to write the report? [your answer]
- Any tool problems? Spring's simple broker was quick to adopt, but its permissive defaults caused the worst bug. MapLibre 4.7 ignored the first resize in background tabs (fixed with a resize observer). SockJS is no longer actively developed.`);
}

// ---------------------------------------------------------------- 10. Limits and questions
pres.addSection({ title: "Wrap-up" });
{
  const s = content("Wrap-up", "Limits, and what comes next", "All criteria");
  const cw = (CW - 0.3) / 2, y = 1.5, h = 3.6;
  [
    ["target", "Known limits", C.accent2, ["Only seven SUS players, one as Mr X", "No automated frontend tests", "Safari not tested",
      "A server restart ends every game", "Desktop only, so the lobby’s QR code was little use at Open Day"]],
    ["trophy", "Next steps", C.accent1, ["A tutorial, the most requested feature", "Computer players, to measure game balance",
      "Boards for any city from open transport data", "Saved games and several servers", "Mobile layout and a spectator view for Open Days"]],
  ].forEach(([ic, head, hc, items], i) => {
    const x = M + i * (cw + 0.3);
    card(s, x, y, cw, h, `wrap-${i + 1}`);
    badge(s, ic, x + 0.3, y + 0.3, 0.55);
    text(s, head, { x: x + 1.0, y: y + 0.3, w: cw - 1.2, h: 0.55, fontSize: 20, bold: true, color: hc, valign: "middle" });
    bullets(s, items, { x: x + 0.3, y: y + 1.1, w: cw - 0.6, h: h - 1.3, fontSize: 16, paraSpaceAfter: 8 });
  });
  text(s, "Questions?", { x: M, y: 5.45, w: CW, h: 1.2, fontFace: THEME.headFontFace, fontSize: 54, bold: true, color: C.accent1, valign: "middle" });
  s.addNotes(`SAY (about 35 seconds):
"The weak points are a small user study, no frontend tests, Safari untested, and a restart ending games. Next steps: a tutorial, computer players to test balance, and other cities. Questions?"

DETAIL:
- Close with the limits: raising them yourself is better than having them found.
- Out of scope from the start: user accounts and saved games, a mobile-first layout, computer-controlled opponents, in-game chat, and any use of players' real locations.
- Handling questions: it's fine to say "Good point, that wasn't considered" or "That was declared out of scope because ...". You can reframe a question slightly toward what you can answer well.

IF ASKED:
- What if the server crashes mid-game? Every game ends. That's the trade-off of no database, accepted for this version.
- How would it scale? Each game has its own lock and games run in parallel. Running across several servers is future work, and would be a test bed for distributed state that keeps each player's secret.
- Why not WebSockets for actions too? REST gives simple error codes and is easy to test.
- Sustainability? Only low-emission modes, and one container on existing hardware with no cloud services.`);
}

(async () => {
  const out = here("slides.pptx");
  await pres.writeFile({ fileName: out });
  await applyTheme(out, THEME);
  console.log("wrote", out);
})();
