// Builds poster.pptx for the ENGR489 Poster Presentation Day (Handbook 2026 Vol. 4, ch. 7):
// A1 portrait (594 x 841 mm trim + 3 mm bleed = 600 x 847 mm), vector art where possible and
// raster at 300 DPI or more, laid out to "ENGR489 Poster Guidelines.pdf" (300-800 words, key
// findings near the top). The story is building a demo for the university's Open Day, then the
// technical challenges and the tech stack behind it. The look follows an academic-poster template:
// a bold uppercase title, a filled subtitle box, an author card, and outlined section boxes with
// icon headings, in the template's teal. Mr X red and detective blue (frontend/src/shared/tickets.ts)
// mark the two roles. Every number is from the final report.
//
//   NODE_PATH=<dir with pptxgenjs> APPLY_THEME=<pptx skill>/scripts/apply_theme.js node make_poster.js
//
// Beside this file: board-*.jpg (dark-mode screenshots, the game's default, captured at 2.5x, 3600 x 2150 px, so
// about 345 DPI at print size), wellington-graph.svg (pdftocairo -svg of the report figure),
// qr.svg (link to the submitted source tag) and icons/ (Lucide, the game's icon set, in white).
const path = require("path");
const pptxgen = require("pptxgenjs");
const { applyTheme } = require(process.env.APPLY_THEME);

const THEME = {
  name: "Hunting Mr X",
  headFontFace: "Arial",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "111827",     // gray-900 text
    lt1: "FFFFFF",     // page
    dk2: "4B5563",     // gray-600 muted text
    lt2: "EAF3F1",     // teal tint for table labels
    accent1: "3E7B74", // template teal: titles, borders, headers
    accent2: "D5E6E2", // light teal: gauge track, dividers
    accent3: "22C55E", // e-scooter
    accent4: "EF4444", // bus, and Mr X
    accent5: "8B5CF6", // train
    accent6: "06B6D4", // ferry
    hlink: "3E7B74",
    folHlink: "8B5CF6",
  },
};
const HEX = THEME.colors;
const NARROW = THEME.headFontFace;

const mm = (v) => v / 25.4;
const W = 600, H = 847, M = 30, COL = W - 2 * M;
const here = (f) => path.join(__dirname, f);

const pres = new pptxgen();
pres.defineLayout({ name: "A1_BLEED", width: mm(W), height: mm(H) });
pres.layout = "A1_BLEED";
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = "Hunting Mr. X: Wellington Edition";
pres.author = "Ming Bao";
const C = pres.SchemeColor;
const P = C.accent1;

// The template's dot cluster: three dots over four.
const DOTS = [[HEX.accent1, HEX.accent1, HEX.accent1], [HEX.accent1, HEX.accent1, HEX.accent1, HEX.accent1]];
const dots = [];
DOTS.forEach((row, r) => row.forEach((color, i) => dots.push({ text: { text: "", options: {
  shape: pres.shapes.OVAL, x: mm(W - M - 7 - (row.length - 1 - i) * 13), y: mm(30 + r * 12), w: mm(7), h: mm(7),
  fill: { color }, line: { type: "none" } } } })));

pres.defineSlideMaster({
  title: "POSTER",
  background: { color: HEX.lt1 },
  objects: [
    ...dots,
    { rect: { x: mm(M), y: mm(88), w: mm(296), h: mm(50), fill: { color: P }, rectRadius: mm(3) } },
    { placeholder: { options: { name: "title", type: "title", x: mm(M), y: mm(22), w: mm(440), h: mm(56),
        fontSize: 84, bold: true, color: P, align: "left", valign: "top", margin: 0, fontFace: NARROW,
        lineSpacingMultiple: 0.9 }, text: "" } },
    { placeholder: { options: { name: "body", type: "body", x: mm(M + 10), y: mm(90), w: mm(276), h: mm(46),
        fontSize: 32, bold: true, color: C.background1, align: "left", valign: "middle", margin: 0 }, text: "" } },
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

// An outlined section box. Its heading is either an icon tile, title and rule (plain), or a
// filled band (for the two sections to read first). Returns the y where content starts.
function section(x, y, w, h, iconFile, title, filled = false) {
  rect(x, y, w, h, { fill: { color: C.background1 }, line: { color: P, width: 3 }, rectRadius: mm(3) });
  if (filled) {
    rect(x, y, w, 18, { fill: { color: P }, rectRadius: mm(3) });
    rect(x, y + 9, w, 9, { fill: { color: P } });
    icon(iconFile, x + 7, y + 4, 10);
    text(x + 22, y + 2, w - 30, 14, title.toUpperCase(), { fontSize: 40, bold: true, fontFace: NARROW,
      color: C.background1, valign: "middle" });
  } else {
    rect(x + 6, y + 4, 13, 13, { fill: { color: P }, rectRadius: mm(2) });
    icon(iconFile, x + 8.5, y + 6.5, 8);
    text(x + 23, y + 3, w - 30, 14, title.toUpperCase(), { fontSize: 40, bold: true, fontFace: NARROW,
      color: P, valign: "middle" });
    slide.addShape(pres.shapes.LINE, { x: mm(x), y: mm(y + 21), w: mm(w), h: 0,
      line: { color: P, width: 2 }, objectName: name("rule") });
  }
  return y + 26;
}

function pill(x, y, w, color, label) {
  slide.addText(label, { shape: pres.shapes.ROUNDED_RECTANGLE, x: mm(x), y: mm(y), w: mm(w), h: mm(11),
    rectRadius: mm(5.5), fill: { color }, line: { type: "none" }, fontSize: 24, bold: true,
    color: C.background1, align: "center", valign: "middle", margin: 0, objectName: name("pill") });
}

// A callout in the main colour with a ring around the thing it describes and a line between them.
// The ring must sit below and to the right of the callout's centre (the line is not flipped).
function callout(x, y, w, label, cx, cy, r) {
  slide.addShape(pres.shapes.LINE, { x: mm(x + w / 2), y: mm(y + 7), w: mm(cx - (x + w / 2)),
    h: mm(cy - r - (y + 7)), line: { color: P, width: 6 }, objectName: name("leader") });
  slide.addShape(pres.shapes.OVAL, { x: mm(cx - r), y: mm(cy - r), w: mm(2 * r), h: mm(2 * r),
    fill: { type: "none" }, line: { color: P, width: 7 }, objectName: name("ring") });
  slide.addText(label, { shape: pres.shapes.ROUNDED_RECTANGLE, x: mm(x), y: mm(y), w: mm(w), h: mm(14),
    rectRadius: mm(3), fill: { color: P }, line: { type: "none" }, fontSize: 28, bold: true,
    color: C.background1, align: "center", valign: "middle", margin: 0, objectName: name("callout") });
}

const chartStyle = { catAxisLabelColor: HEX.dk1, catAxisLabelFontSize: 22, catAxisLabelFontFace: "+mn-lt",
  catAxisLineShow: false, valAxisHidden: true, valGridLine: { style: "none" }, catGridLine: { style: "none" },
  showValue: true, dataLabelPosition: "outEnd", dataLabelColor: HEX.dk1, dataLabelFontSize: 24,
  dataLabelFontBold: true, dataLabelFontFace: "+mn-lt", showLegend: false, showTitle: false,
  barGapWidthPct: 45 };

function chartTitle(x, y, w, t) {
  text(x, y, w, 12, t.toUpperCase(), { fontSize: 28, bold: true, fontFace: NARROW, valign: "middle" });
}

// ---- Title, subtitle (the finding) and author card ----
slide.addText("HUNTING MR. X\nWELLINGTON EDITION", { placeholder: "title" });
slide.addText("A new Open Day demo: a classic board game, rebuilt to play online on a real map of Wellington",
  { placeholder: "body" });
const ax = 340, aw = W - M - ax;
rect(ax, 88, aw, 50, { fill: { color: C.background1 }, line: { color: P, width: 3 }, rectRadius: mm(3) });
rect(ax + 8, 98, 30, 30, { fill: { color: P }, rectRadius: mm(3) });
icon("graduation-cap", ax + 13, 103, 20);
text(ax + 46, 92, aw - 52, 16, "MING BAO", { fontSize: 44, bold: true, fontFace: NARROW, color: P,
  valign: "middle" });
text(ax + 46, 110, aw - 52, 24, ["Supervisors: Jens Dietrich, Stuart Marshall",
  "ENGR489, Victoria University of Wellington"].map((t, i) => ({ text: t,
  options: { breakLine: i === 0, color: i ? C.text2 : C.text1 } })), { fontSize: 24 });

// ---- Key findings ----
let y = 146;
let top = section(M, y, COL, 84, "trophy", "Key findings", true);
const stats = [["Open Day", "played live by visitors", "on the stand's laptops"],
  ["216 places", "on a real map of Wellington", "by bus, train, ferry and e-scooter"],
  ["No install", "join from a web browser", "with a link or a six-letter code"]];
const sw = (COL - 16) / 3;
stats.forEach(([big, l1, l2], i) => {
  const sx = M + 8 + i * sw;
  if (i) slide.addShape(pres.shapes.LINE, { x: mm(sx), y: mm(top + 2), w: 0, h: mm(52),
    line: { color: C.accent2, width: 2 }, objectName: name("divider") });
  text(sx, top - 2, sw, 30, big, { fontSize: 72, bold: true, color: P, align: "center", valign: "middle",
    fontFace: NARROW });
  text(sx, top + 29, sw, 26, [{ text: l1, options: { bold: true, breakLine: true } },
    { text: l2, options: { color: C.text2 } }], { fontSize: 28, align: "center" });
});

// ---- Why Open Day | How to play ----
y = 240;
const lw = 254, ox = M + lw + 12, ow = COL - lw - 12;
top = section(M, y, lw, 80, "target", "Why Open Day?");
bullets(M + 8, top, lw - 16, 50, ["The old software demo was over five years old",
  "Visitors need something to try in a few minutes",
  "A game set in their own city starts conversations"]);
top = section(ox, y, ow, 80, "gamepad-2", "How to play");
bullets(ox + 8, top, ow - 16, 50, ["One player is Mr X; up to five detectives hunt him",
  "Everyone travels by bus, train, ferry or e-scooter",
  "Mr X stays hidden, shown only every few rounds",
  "Catch him within 24 rounds to win"]);

// ---- Main graphic: one game, two views ----
y = 330;
top = section(M, y, COL, 196, "eye-off", "Same game, different secrets");
const iw = (COL - 26) / 2, ih = iw * 2150 / 3600, iy = top;
const ix1 = M + 8, ix2 = ix1 + iw + 10;
slide.addImage({ path: here("board-mrx.jpg"), x: mm(ix1), y: mm(iy), w: mm(iw), h: mm(ih),
  altText: "Mr X's screen: the map with his own token shown", objectName: "board-mrx" });
slide.addImage({ path: here("board-detective.jpg"), x: mm(ix2), y: mm(iy), w: mm(iw), h: mm(ih),
  altText: "A detective's screen: Mr X's location shown as a question mark", objectName: "board-detective" });
callout(ix1 + iw * 0.06, iy + ih * 0.209 - 18, 108, "He sees where he is", ix1 + iw * 0.479,
  iy + ih * 0.209, 9);
callout(ix2 + iw * 0.79 - 70, iy + ih * 0.138 - 16, 70, "They see “?”", ix2 + iw * 0.985,
  iy + ih * 0.138, 6);
const cy = iy + ih + 4;
pill(ix1, cy, 28, HEX.accent4, "Mr. X");
text(ix1 + 32, cy - 1, iw - 32, 13, "moves in secret across the city.", { valign: "middle" });
pill(ix2, cy, 40, "2563EB", "Detective");
text(ix2 + 44, cy - 1, iw - 44, 13, "sees him only in rounds 2, 8, 13, 18 and 24.", { valign: "middle" });

// ---- Technical challenges ----
y = 536;
top = section(M, y, COL, 110, "shield-check", "Technical challenges", true);
const challenges = [
  ["lock", "Keeping a secret", "Each player is sent their own view, so Mr X's position never reaches a detective's screen."],
  ["refresh-cw", "Staying in sync", "Every move is pushed live to every screen. A dropped connection catches up by itself."],
  ["map", "A free real map", "Routes along real roads are worked out once, not paid for on every move."],
  ["shield-check", "Proving it works", "135 automated tests, including 300 random games. Every planted bug was caught."],
];
const kw = (COL - 16 - 36) / 4;
challenges.forEach(([ic, title, body], i) => {
  const kx = M + 8 + i * (kw + 12);
  slide.addShape(pres.shapes.OVAL, { x: mm(kx), y: mm(top + 1), w: mm(18), h: mm(18),
    fill: { color: P }, line: { type: "none" }, objectName: name("challenge-icon") });
  icon(ic, kx + 4, top + 5, 10);
  text(kx + 22, top + 1, kw - 22, 18, title.toUpperCase(), { fontSize: 28, bold: true, fontFace: NARROW,
    color: P, valign: "middle" });
  text(kx, top + 24, kw, 58, body, { fontSize: 28 });
});

// ---- Tech stack (table + board) | Outcome and next steps ----
y = 658;
const pw = 322, cx = M + pw + 12, cw = COL - pw - 12;
top = section(M, y, pw, 138, "layers", "Tech stack");
const label = (t) => ({ text: t, options: { bold: true, color: P, fill: { color: HEX.lt2 } } });
slide.addTable([
  [label("Game screens"), "Vue 3, TypeScript, Tailwind CSS"],
  [label("Map"), "MapLibre GL, OpenStreetMap"],
  [label("Game server"), "Java 21, Spring Boot"],
  [label("Live updates"), "WebSockets (STOMP)"],
  [label("Testing"), "JUnit 5, Mockito, PIT"],
  [label("Hosting"), "Docker, Cloudflare Tunnel"],
], { x: mm(M + 8), y: mm(top), w: mm(214), colW: [mm(66), mm(148)], rowH: mm(17.5), fontSize: 26,
  color: HEX.dk1, fontFace: THEME.bodyFontFace, valign: "middle", margin: [3, 6, 3, 6],
  border: [{ type: "none" }, { type: "none" }, { pt: 1.5, color: "C9DFDA" }, { type: "none" }],
  objectName: "table-stack" });
const mapW = 84, mapH = mapW * 1800 / 1907;
slide.addImage({ path: here("wellington-graph.svg"), x: mm(M + 226 + (pw - 234 - mapW) / 2), y: mm(top),
  w: mm(mapW), h: mm(mapH), altText: "The Wellington board graph", objectName: "board-graph" });
text(M + 224, top + mapH + 1, pw - 228, 9, "The board: 216 places", { fontSize: 22, color: C.text2,
  align: "center" });

top = section(cx, y, cw, 138, "flag", "Outcome");
bullets(cx + 8, top + 2, cw - 16, 40, ["Played live at Open Day",
  "Anyone can join over the internet",
  "Ready for future Open Days"]);
text(cx + 8, top + 50, cw - 16, 10, "NEXT STEPS", { fontSize: 28, bold: true, fontFace: NARROW, color: P });
bullets(cx + 8, top + 64, cw - 16, 40, ["A big-screen view for the stand",
  "A phone-friendly layout", "Computer players for solo games"]);

(async () => {
  const out = here("poster.pptx");
  await pres.writeFile({ fileName: out });
  await applyTheme(out, THEME);
  console.log("wrote", out);
})();
