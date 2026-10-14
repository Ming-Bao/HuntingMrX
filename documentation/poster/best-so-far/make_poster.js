// Builds poster.pptx for the ENGR489 Poster Presentation Day (Handbook 2026 Vol. 4, ch. 7):
// A1 portrait (594 x 841 mm trim + 3 mm bleed = 600 x 847 mm), vector art where possible and
// raster at 300 DPI or more, laid out to "ENGR489 Poster Guidelines.pdf" (300-800 words, key
// findings near the top). The story is building a demo for the university's Open Day, then the
// technical challenges and the tech stack behind it. The look follows an academic-poster template
// (outlined section boxes with icon headings, in the template's teal) under a dark header in the
// game's own dark UI colour, with the game's ticket colours (frontend/src/shared/tickets.ts) as
// accents. Every number is from the final report.
//
//   NODE_PATH=<dir with pptxgenjs> APPLY_THEME=<pptx skill>/scripts/apply_theme.js node make_poster.js
//
// Beside this file: board-mrx.jpg (Mr X's screen in dark mode, the game's default, captured at
// 2.5x, 3600 x 2150 px), hero-graph.svg (pdftocairo -svg of the report's Wellington board figure,
// without its background, legend and scale bar, with white nodes, for the dark header), qr.svg (link to the submitted source tag) and icons/ (Lucide,
// the game's icon set, in white).
const path = require("path");
const pptxgen = require("pptxgenjs");
const { applyTheme } = require(process.env.APPLY_THEME);

const THEME = {
  name: "Hunting Mr X",
  headFontFace: "Arial",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "111827",     // gray-900: text, and the game's dark UI (header, screenshot panel)
    lt1: "FFFFFF",     // page
    dk2: "4B5563",     // gray-600 muted text
    lt2: "EAF3F1",     // teal tint: cards and chips
    accent1: "3E7B74", // template teal: titles, borders, headers
    accent2: "BFE0DA", // light teal: text on dark or teal, dividers
    accent3: "22C55E", // e-scooter
    accent4: "EF4444", // bus, and Mr X
    accent5: "8B5CF6", // train
    accent6: "06B6D4", // ferry
    hlink: "3E7B74",
    folHlink: "8B5CF6",
  },
};
const HEX = THEME.colors;
const HEAD = THEME.headFontFace;
const AMBER = "F59E0B";      // double ticket
const SLATE = "64748B";      // Invisible ticket
const DETECTIVE = "2563EB";

const mm = (v) => v / 25.4;
const W = 600, H = 847, M = 30, COL = W - 2 * M, HERO = 166;
const here = (f) => path.join(__dirname, f);

const pres = new pptxgen();
pres.defineLayout({ name: "A1_BLEED", width: mm(W), height: mm(H) });
pres.layout = "A1_BLEED";
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = "Hunting Mr. X: Wellington Edition";
pres.author = "Ming Bao";
const C = pres.SchemeColor;
const P = C.accent1;

pres.defineSlideMaster({
  title: "POSTER",
  background: { color: HEX.lt1 },
  objects: [
    { rect: { x: 0, y: 0, w: mm(W), h: mm(HERO), fill: { color: C.text1 } } },
    { image: { path: here("hero-graph.svg"), x: mm(400), y: mm(6), w: mm(156 * 457.5 / 431.95), h: mm(156) } },
    { placeholder: { options: { name: "title", type: "title", x: mm(M), y: mm(26), w: mm(380), h: mm(56),
        fontSize: 84, bold: true, color: C.background1, align: "left", valign: "top", margin: 0,
        fontFace: HEAD, lineSpacingMultiple: 0.9 }, text: "" } },
    { placeholder: { options: { name: "body", type: "body", x: mm(M), y: mm(91), w: mm(368), h: mm(24),
        fontSize: 30, color: C.background1, align: "left", valign: "top", margin: 0 }, text: "" } },
    { image: { path: here("qr.svg"), x: mm(W - M - 26), y: mm(806), w: mm(26), h: mm(26) } },
    { text: { text: "Source code: scan the QR code.\n"
        + "Board-game mechanics used with permission from Ravensburger, for non-commercial academic use.",
        options: { x: mm(M), y: mm(808), w: mm(COL - 34), h: mm(22), fontSize: 22, color: C.text2,
          valign: "middle", margin: 0 } } },
  ],
});

pres.addSection({ title: "Poster" });
const slide = pres.addSlide({ masterName: "POSTER", sectionTitle: "Poster" });
let n = 0;
const name = (s) => `${s}-${++n}`;

function text(x, y, w, h, t, o = {}) {
  slide.addText(t, { x: mm(x), y: mm(y), w: mm(w), h: mm(h), isTextBox: true, margin: 0,
    fontSize: 26, color: C.text1, valign: "top", objectName: name("text"), ...o });
}

function bullets(x, y, w, h, items, o = {}) {
  text(x, y, w, h, items.map((t, i) => ({ text: t,
    options: { bullet: { indent: 20 }, paraSpaceAfter: 6, breakLine: i < items.length - 1 } })), o);
}

function rect(x, y, w, h, o) {
  slide.addShape(o.rectRadius ? pres.shapes.ROUNDED_RECTANGLE : pres.shapes.RECTANGLE,
    { x: mm(x), y: mm(y), w: mm(w), h: mm(h), line: { type: "none" }, objectName: name("shape"), ...o });
}

function icon(file, x, y, d) {
  slide.addImage({ path: here(`icons/${file}.svg`), x: mm(x), y: mm(y), w: mm(d), h: mm(d),
    altText: "", objectName: name("icon") });
}

function circleIcon(file, x, y, d, color) {
  slide.addShape(pres.shapes.OVAL, { x: mm(x), y: mm(y), w: mm(d), h: mm(d), fill: { color },
    line: { type: "none" }, objectName: name("icon-circle") });
  icon(file, x + d * 0.22, y + d * 0.22, d * 0.56);
}

// A rounded pill sized to its label, like the game's ticket chips. Returns its width.
function chip(x, y, label, fill, color = C.background1, fontSize = 22) {
  const w = label.length * fontSize * 0.19 + 10;
  slide.addText(label, { shape: pres.shapes.ROUNDED_RECTANGLE, x: mm(x), y: mm(y), w: mm(w), h: mm(11),
    rectRadius: mm(5.5), fill: { color: fill }, line: { type: "none" }, fontSize, bold: true, color,
    align: "center", valign: "middle", margin: 0, objectName: name("chip") });
  return w;
}

// A section box. "plain": outlined, with an icon tile, title and rule. "band": outlined, with a
// filled heading band. "dark": the game's dark UI colour. "solid": filled teal. Returns the y
// where content starts.
function section(x, y, w, h, iconFile, title, style = "plain") {
  const fill = { plain: C.background1, band: C.background1, dark: C.text1, solid: P }[style];
  const onFill = style === "dark" || style === "solid" ? C.background1 : P;
  rect(x, y, w, h, { fill: { color: fill }, rectRadius: mm(3),
    line: style === "plain" || style === "band" ? { color: P, width: 3 } : { type: "none" } });
  if (style === "band") {
    rect(x, y, w, 18, { fill: { color: P }, rectRadius: mm(3) });
    rect(x, y + 9, w, 9, { fill: { color: P } });
    icon(iconFile, x + 7, y + 4, 10);
  } else {
    rect(x + 6, y + 4, 13, 13, { fill: { color: style === "solid" ? C.text1 : P }, rectRadius: mm(2) });
    icon(iconFile, x + 8.5, y + 6.5, 8);
  }
  text(x + (style === "band" ? 22 : 23), y + (style === "band" ? 2 : 3), w - 30, 14, title.toUpperCase(),
    { fontSize: 40, bold: true, fontFace: HEAD, color: style === "band" ? C.background1 : onFill,
      valign: "middle" });
  if (style === "plain") slide.addShape(pres.shapes.LINE, { x: mm(x), y: mm(y + 21), w: mm(w), h: 0,
    line: { color: P, width: 2 }, objectName: name("rule") });
  return y + 26;
}

// ---- Header: title, the finding, author, and the game's tickets ----
text(M, 14, 380, 9, "ENGR489 PROJECT  ·  VICTORIA UNIVERSITY OF WELLINGTON", { fontSize: 22, bold: true,
  color: C.accent2, charSpacing: 3, valign: "middle" });
slide.addText([{ text: "HUNTING MR. X", options: { breakLine: true } },
  { text: "WELLINGTON EDITION", options: { color: C.accent2 } }], { placeholder: "title" });
slide.addText("A new Open Day demo:\na classic board game, played online on a real map of Wellington",
  { placeholder: "body" });
icon("graduation-cap", M, 121, 11);
text(M + 15, 120, 360, 13, [{ text: "Ming Bao", options: { bold: true, color: C.background1 } },
  { text: "   ·   Supervisors: Jens Dietrich, Stuart Marshall" }], { fontSize: 28, color: C.accent2,
  valign: "middle" });
let tx = M;
[["Escooter", HEX.accent3], ["Bus", HEX.accent4], ["Train", HEX.accent5], ["Ferry", HEX.accent6],
  ["Invisible", SLATE], ["Double", AMBER]].forEach(([t, c]) => { tx += chip(tx, 143, t, c) + 4; });

// ---- Key findings ----
text(M, 171, 200, 9, "KEY FINDINGS", { fontSize: 26, bold: true, color: P, charSpacing: 4, valign: "middle" });
const stats = [["users", "Open Day", "played live by visitors", "on the stand's laptops"],
  ["map-pin", "216 places", "on a real map of Wellington", "by bus, train, ferry and e-scooter"],
  ["monitor-smartphone", "No install", "join from a web browser", "with a link or a six-letter code"]];
const sw = (COL - 24) / 3;
stats.forEach(([ic, big, l1, l2], i) => {
  const sx = M + i * (sw + 12), sy = 183;
  rect(sx, sy, sw, 64, { fill: { color: P }, rectRadius: mm(4) });
  icon(ic, sx + 8, sy + 8, 11);
  text(sx, sy + 5, sw, 26, big, { fontSize: 60, bold: true, color: C.background1, align: "center",
    valign: "middle", fontFace: HEAD });
  text(sx + 6, sy + 33, sw - 12, 28, [{ text: l1, options: { bold: true, breakLine: true } },
    { text: l2, options: { color: C.accent2 } }], { fontSize: 26, align: "center", color: C.background1 });
});

// ---- Why Open Day | How to play ----
let y = 256;
const lw = 254, ox = M + lw + 12, ow = COL - lw - 12;
let top = section(M, y, lw, 80, "target", "Why Open Day?");
bullets(M + 8, top, lw - 16, 48, ["The old software demo was over five years old",
  "Visitors need something to try in a few minutes",
  "A game set in their own city starts conversations"]);
top = section(ox, y, ow, 80, "gamepad-2", "How to play");
bullets(ox + 8, top, ow - 16, 48, ["One player is Mr X; up to five detectives hunt him",
  "Everyone travels by bus, train, ferry or e-scooter",
  "Mr X stays hidden, shown only every few rounds",
  "Catch him within 24 rounds to win"]);

// ---- Main graphic: what each player is sent, drawn like the game's Players panel ----
y = 346;
top = section(M, y, COL, 198, "eye-off", "Same game, different secrets", "dark");
const PANEL = "1F2937", MUTED = "9CA3AF", GRAY700 = "374151";
const players = [["Alice", "000000", "Node 1"], ["Bob", DETECTIVE, "Node 241"], ["Carol", HEX.accent3, "Node 149"]];

// One player's Players panel. `you` is the row they play; `hidden` swaps Mr X's node for "?".
function playersPanel(x, w, role, roleColor, you, hidden, note) {
  rect(x, top, w, 100, { fill: { color: PANEL }, rectRadius: mm(3) });
  const cw0 = chip(x + 8, top + 7, role, roleColor, C.background1, 26);
  text(x + 12 + cw0, top + 6, w - 20 - cw0, 13, "sees", { fontSize: 28, bold: true,
    color: C.background1, valign: "middle" });
  text(x + 8, top + 24, 80, 9, "PLAYERS", { fontSize: 22, color: MUTED, charSpacing: 3, valign: "middle" });
  players.forEach(([who, color, node], r) => {
    const ry = top + 37 + r * 20;
    const secret = r === 0;
    if (secret) rect(x + 4, ry - 3, w - 8, 18, { fill: { type: "none" }, rectRadius: mm(3),
      line: { color: AMBER, width: 4 } });
    const nw = slideChip(x + 8, ry, who, color);
    if (r === you) text(x + 12 + nw, ry, 30, 12, "(you)", { fontSize: 24, color: MUTED, valign: "middle" });
    const shown = secret && hidden ? "?" : node;
    text(x + w - 78, ry - 2, 70, 16, shown, { fontSize: secret && hidden ? 48 : 30, bold: secret,
      fontFace: "Courier New", color: secret ? AMBER : C.background1, align: "right", valign: "middle" });
  });
  text(x, top + 104, w, 12, note, { fontSize: 26, bold: true, color: AMBER, align: "center",
    valign: "middle" });
}

// The game draws Mr X's name in a black pill with a white outline.
function slideChip(x, y, label, color) {
  const w = label.length * 26 * 0.19 + 10;
  slide.addText(label, { shape: pres.shapes.ROUNDED_RECTANGLE, x: mm(x), y: mm(y), w: mm(w), h: mm(12),
    rectRadius: mm(6), fill: { color }, line: color === "000000" ? { color: C.background1, width: 2 }
      : { type: "none" }, fontSize: 26, bold: true, color: C.background1, align: "center",
    valign: "middle", margin: 0, objectName: name("player") });
  return w;
}

const pnw = 190, px1 = M + 8, px2 = M + COL - 8 - pnw, mid = (px1 + pnw + px2) / 2;
playersPanel(px1, pnw, "Mr. X", HEX.accent4, 0, false, "He always knows where he is");
playersPanel(px2, pnw, "Detective", DETECTIVE, 1, true, "They see only a “?”");

// The server between them, sending each its own view.
circleIcon("server", mid - 20, top + 10, 40, P);
text(mid - 60, top + 54, 120, 12, "Game server", { fontSize: 30, bold: true, color: C.background1,
  align: "center", valign: "middle" });
text(mid - 60, top + 68, 120, 26, "sends each player only what they are allowed to see", { fontSize: 24,
  color: C.accent2, align: "center" });
const ay = top + 30;
slide.addShape(pres.shapes.LINE, { x: mm(px1 + pnw + 4), y: mm(ay), w: mm(mid - 24 - (px1 + pnw + 4)), h: 0,
  line: { color: C.accent2, width: 5, beginArrowType: "triangle" }, objectName: "arrow-mrx" });
slide.addShape(pres.shapes.LINE, { x: mm(mid + 24), y: mm(ay), w: mm(px2 - 4 - (mid + 24)), h: 0,
  line: { color: C.accent2, width: 5, endArrowType: "triangle" }, objectName: "arrow-detective" });

// The 24 rounds, with the five in which Mr X is revealed.
const REVEALS = [2, 8, 13, 18, 24];
const ty = top + 128, step = (COL - 16) / 24, d = 17;
for (let r = 1; r <= 24; r++) {
  const on = REVEALS.includes(r);
  slide.addText(String(r), { shape: pres.shapes.OVAL, x: mm(M + 8 + (r - 1) * step + (step - d) / 2), y: mm(ty),
    w: mm(d), h: mm(d), fill: { color: on ? AMBER : GRAY700 }, line: { type: "none" }, fontSize: on ? 24 : 20,
    bold: on, color: on ? C.text1 : MUTED, align: "center", valign: "middle", margin: 0,
    objectName: name("round") });
}
text(M + 8, ty + d + 4, COL - 16, 12, [{ text: "24 rounds. ", options: { bold: true, color: C.background1 } },
  { text: "Mr X is revealed only in the amber rounds; the rest of the time he is a “?”." }],
  { fontSize: 26, color: C.accent2, align: "center", valign: "middle" });

// ---- Technical challenges ----
y = 554;
top = section(M, y, COL, 112, "shield-check", "Technical challenges", "band");
const challenges = [
  ["lock", HEX.accent4, "Keeping a secret", "Each player is sent their own view, so Mr X's position never reaches a detective's screen."],
  ["refresh-cw", HEX.accent6, "Staying in sync", "Every move is pushed live to every screen. A dropped connection catches up by itself."],
  ["map", HEX.accent3, "A free real map", "Routes along real roads are worked out once, not paid for on every move."],
  ["shield-check", HEX.accent5, "Proving it works", "135 automated tests, including 300 random games. Every planted bug was caught."],
];
const kw = (COL - 16 - 30) / 4;
challenges.forEach(([ic, color, title, body], i) => {
  const kx = M + 8 + i * (kw + 10);
  rect(kx, top - 2, kw, 82, { fill: { color: C.background2 }, rectRadius: mm(3) });
  circleIcon(ic, kx + 5, top + 3, 16, color);
  text(kx + 25, top + 3, kw - 28, 16, title.toUpperCase(), { fontSize: 26, bold: true, fontFace: HEAD,
    color: P, valign: "middle" });
  text(kx + 5, top + 24, kw - 10, 54, body, { fontSize: 27 });
});

// ---- Tech stack (chips + board) | Outcome and next steps ----
y = 676;
const pw = 322, cx = M + pw + 12, cw = COL - pw - 12;
top = section(M, y, pw, 124, "layers", "Tech stack");
[["Screens", ["Vue 3", "TypeScript", "Tailwind CSS"]],
  ["Map", ["MapLibre GL", "OpenStreetMap"]],
  ["Server", ["Java 21", "Spring Boot"]],
  ["Live", ["WebSockets", "STOMP"]],
  ["Testing", ["JUnit 5", "Mockito", "PIT"]],
  ["Hosting", ["Docker", "Cloudflare Tunnel"]]].forEach(([label, techs], r) => {
  const ry = top + r * 15.5;
  text(M + 8, ry, 50, 11, label, { fontSize: 24, bold: true, color: P, valign: "middle" });
  let chx = M + 58;
  techs.forEach((t) => { chx += chip(chx, ry, t, C.background2, C.text1) + 4; });
});
const shotW = 100, shotH = shotW * 2150 / 3600, shotX = M + pw - shotW - 8;
slide.addImage({ path: here("board-mrx.jpg"), x: mm(shotX), y: mm(top + 4), w: mm(shotW), h: mm(shotH),
  altText: "Mr X's screen during a game", objectName: "board-mrx" });
text(shotX, top + shotH + 6, shotW, 9, "Mr X's screen in a live game", { fontSize: 22, color: C.text2,
  align: "center" });

top = section(cx, y, cw, 124, "flag", "Outcome", "solid");
bullets(cx + 8, top + 2, cw - 16, 40, ["Played live at Open Day",
  "Anyone can join over the internet",
  "Ready for future Open Days"], { color: C.background1 });
text(cx + 8, top + 45, cw - 16, 10, "NEXT STEPS", { fontSize: 26, bold: true, fontFace: HEAD,
  color: C.accent2, charSpacing: 2 });
bullets(cx + 8, top + 57, cw - 16, 38, ["A big-screen view for the stand",
  "A phone-friendly layout", "Computer players for solo games"], { color: C.background1 });

(async () => {
  const out = here("poster.pptx");
  await pres.writeFile({ fileName: out });
  await applyTheme(out, THEME);
  console.log("wrote", out);
})();
