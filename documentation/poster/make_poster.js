// Builds poster.pptx for the ENGR489 Poster Presentation Day (Handbook 2026 Vol. 4, ch. 7):
// A1 portrait (594 x 841 mm trim + 3 mm bleed = 600 x 847 mm), vector art where possible and
// raster at 300 DPI or more, laid out to "ENGR489 Poster Guidelines.pdf" (300-800 words, key
// findings near the top). The story is building a demo for the university's Open Day; the
// centrepiece is the game board itself, the whole Wellington region, followed by the technical
// challenges, the tech stack and the evaluation. The look follows an academic-poster template (outlined section
// boxes with icon headings, in the template's teal) under a dark header in the game's own dark UI
// colour, with the game's ticket colours (frontend/src/shared/tickets.ts) as accents. Every number
// is from the final report or the game's map file.
//
//   NODE_PATH=<dir with pptxgenjs> APPLY_THEME=<pptx skill>/scripts/apply_theme.js node make_poster.js
//
// Beside this file, all captured from a live game in dark mode, the game's default:
//   board.jpg      the whole board, fitted to all 216 nodes, players hidden (4400 x 4000 px, so
//                  about 355 DPI at print size)
//   hero.jpg       a close-up of the city and harbour, dimmed and faded into the header colour on
//                  its left (3949 x 2080 px, about 335 DPI)
// plus qr.svg (link to the live game, https://vuw-mrx.xyz) and icons/ (Lucide, the game's icon set, in white).
// best-so-far/ holds an earlier version, kept for comparison.
const path = require("path");
const pptxgen = require("pptxgenjs");
const { applyTheme } = require(process.env.APPLY_THEME);

const THEME = {
  name: "Hunting Mr X",
  headFontFace: "Arial",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "111827",     // gray-900: text, and the game's dark UI (header, board panel)
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

const mm = (v) => v / 25.4;
const W = 600, H = 847, M = 30, COL = W - 2 * M, HERO = 158;
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
    { image: { path: here("hero.jpg"), x: mm(W - 300), y: 0, w: mm(300), h: mm(HERO),
        altText: "Close-up of the game board over Wellington's city and harbour" } },
    { placeholder: { options: { name: "title", type: "title", x: mm(M), y: mm(26), w: mm(380), h: mm(56),
        fontSize: 84, bold: true, color: C.background1, align: "left", valign: "top", margin: 0,
        fontFace: HEAD, lineSpacingMultiple: 0.9 }, text: "" } },
    { placeholder: { options: { name: "body", type: "body", x: mm(M), y: mm(89), w: mm(300), h: mm(26),
        fontSize: 30, color: C.background1, align: "left", valign: "top", margin: 0 }, text: "" } },
    { text: { text: "Board-game mechanics used with permission from Ravensburger, for non-commercial academic use.",
        options: { x: mm(M), y: mm(808), w: mm(COL), h: mm(12), fontSize: 22, color: C.text2,
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
    altText: `${file.replace(/-/g, " ")} icon`, objectName: name("icon") });
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

// ---- Header: title, the finding, author ----
text(M, 14, 380, 9, "ENGR489 PROJECT  ·  VICTORIA UNIVERSITY OF WELLINGTON", { fontSize: 22, bold: true,
  color: C.accent2, charSpacing: 3, valign: "middle" });
slide.addText([{ text: "HUNTING MR. X", options: { breakLine: true } },
  { text: "WELLINGTON EDITION", options: { color: C.accent2 } }], { placeholder: "title" });
slide.addText([{ text: "A new Open Day demo:", options: { bold: true, color: AMBER, breakLine: true } },
  { text: "a classic board game, played online on a real map of Wellington" }], { placeholder: "body" });
icon("graduation-cap", M, 120, 11);
text(M + 15, 119, 360, 13, [{ text: "Ming Bao", options: { bold: true, color: C.background1 } },
  { text: "   ·   Supervisors: Jens Dietrich, Stuart Marshall" }], { fontSize: 28, color: C.accent2,
  valign: "middle" });

// ---- Key findings: tiles that sit across the bottom edge of the header ----
chip(M, 134, "KEY FINDINGS", AMBER, C.text1, 20);
const stats = [["school", "Future students", "a demo for prospective students", "at the university's Open Days"],
  ["dices", "Scotland Yard", "inspired by the classic board game", "by Ravensburger"],
  ["monitor-smartphone", "No install", "join from a web browser", "with a link or a six-character code"]];
const sw = (COL - 24) / 3;
stats.forEach(([ic, big, l1, l2], i) => {
  const sx = M + i * (sw + 12), sy = 147;
  rect(sx, sy, sw, 62, { fill: { color: P }, rectRadius: mm(4),
    shadow: { type: "outer", color: "000000", opacity: 0.3, blur: 8, offset: 3, angle: 90 } });
  icon(ic, sx + 8, sy + 8, 11);
  text(sx + 12, sy + 4, sw - 16, 26, big, { fontSize: 50, bold: true, color: C.background1, align: "center",
    valign: "middle", fontFace: HEAD });
  text(sx + 6, sy + 31, sw - 12, 28, [{ text: l1, options: { bold: true, breakLine: true } },
    { text: l2, options: { color: C.accent2 } }], { fontSize: 26, align: "center", color: C.background1 });
});

// ---- The board: the whole region as it appears in the game ----
let y = 219;
const bw = 330;
let top = section(M, y, bw, 322, "map", "The board: all of Wellington", "dark");
const imW = bw - 16, imH = imW * 4000 / 4400, imX = M + 8, imY = top;
slide.addImage({ path: here("board.jpg"), x: mm(imX), y: mm(imY), w: mm(imW), h: mm(imH),
  altText: "The game board: 216 places across the Wellington region, joined by colour-coded routes",
  objectName: "board" });
const at = (fx, fy) => [imX + fx * imW, imY + fy * imH];

// The five areas, from the node clusters in map.json (their counts add up to all 216 places).
// Each label sits in empty map space beside its area; positions are fractions of the image.
[["Porirua", 35, 0.02, 0.235], ["Upper Hutt", 18, 0.865, 0.095, "right"], ["Johnsonville", 29, 0.0, 0.38],
  ["Lower Hutt", 65, 0.655, 0.52], ["Wellington city", 69, 0.205, 0.885]].forEach(([area, count, fx, fy, align]) => {
  const label = `${area}  ${count}`, w = label.length * 24 * 0.19 + 10;
  let [x, ly] = at(fx, fy);
  if (align === "right") x -= w;
  slide.addText([{ text: area, options: { bold: true, color: C.background1 } },
    { text: `  ${count}`, options: { bold: true, color: AMBER } }], {
    shape: pres.shapes.ROUNDED_RECTANGLE, x: mm(x), y: mm(ly), w: mm(w), h: mm(12), rectRadius: mm(6),
    fill: { color: C.text1, transparency: 15 }, line: { color: C.accent2, width: 1.5 }, fontSize: 24,
    align: "center", valign: "middle", margin: 0, objectName: name("area") });
});
chip(...at(0.585, 0.285), "TRAIN", HEX.accent5, C.background1, 22);
chip(...at(0.405, 0.735), "FERRY", HEX.accent6, C.text1, 22);

// The headline numbers, over the empty hills east of the harbour. The ticket chips in How to play,
// beside the board, double as its colour key.
const [sx0, sy0] = at(0.63, 0.74);
text(sx0, sy0, 70, 36, "216", { fontSize: 110, bold: true, color: AMBER, fontFace: HEAD, valign: "bottom" });
text(sx0 + 68, sy0 + 12, 50, 20, "places", { fontSize: 36, bold: true, color: C.background1, valign: "bottom" });
text(sx0, sy0 + 38, 116, 12, "372 routes  ·  5 areas", { fontSize: 28, color: C.accent2, valign: "middle" });
text(imX, imY + imH + 1, imW, 8, "Map data © OpenStreetMap contributors, © CARTO", { fontSize: 18,
  color: C.accent2, align: "right", valign: "middle" });

// ---- Why Open Day | How to play | where to play it, down the right of the board ----
const rx0 = M + bw + 12, rw0 = COL - bw - 12;
top = section(rx0, y, rw0, 90, "target", "Why Open Day?");
bullets(rx0 + 8, top, rw0 - 16, 58, ["The old software demo was over five years old",
  "Visitors need something to try in minutes",
  "A map of their own city is instantly familiar",
  "It shows what a fourth-year student can build"]);
top = section(rx0, y + 100, rw0, 116, "gamepad-2", "How to play");
bullets(rx0 + 8, top, rw0 - 16, 56, ["Mr X hides; up to five detectives hunt him",
  "Every move spends a travel ticket",
  "Mr X is shown only every few rounds",
  "Catch him within 24 rounds to win"]);
[[["Escooter", HEX.accent3], ["Bus", HEX.accent4], ["Train", HEX.accent5]],
  [["Ferry", HEX.accent6], ["Invisible", "64748B"], ["Double", AMBER]]].forEach((row, r) => {
  let tx = rx0 + 8;
  row.forEach(([t, c]) => { tx += chip(tx, top + 60 + r * 14, t, c, C.background1) + 4; });
});
const qy = y + 226, qh = 322 - 226;
rect(rx0, qy, rw0, qh, { fill: { color: C.background2 }, rectRadius: mm(3) });
const qr = qh - 16;
slide.addImage({ path: here("qr.svg"), x: mm(rx0 + 8), y: mm(qy + 8), w: mm(qr), h: mm(qr),
  altText: "QR code linking to the game at vuw-mrx.xyz", objectName: "qr" });
text(rx0 + qr + 18, qy + 8, rw0 - qr - 26, qr, [
  { text: "PLAY IT NOW", options: { bold: true, color: P, fontFace: HEAD, fontSize: 30, breakLine: true } },
  { text: "Scan or visit", options: { breakLine: true } },
  { text: "vuw-mrx.xyz", options: { bold: true } }], { fontSize: 26, valign: "middle" });

// ---- Technical challenges ----
y = 551;
top = section(M, y, COL, 112, "shield-check", "Technical challenges", "band");
const challenges = [
  ["lock", HEX.accent4, "Keeping a secret", "Each player is sent their own view, so Mr X's position never reaches a detective's screen."],
  ["refresh-cw", HEX.accent6, "Staying in sync", "Every move is pushed live to every screen. A dropped connection catches up by itself."],
  ["map", HEX.accent3, "A free real map", "Routes along real roads are worked out once, not paid for on every move."],
  ["shield-check", HEX.accent5, "Proving it works", "The rules sit apart from the server, so each is tested exactly. 300 random games hunt for leaks."],
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

// ---- Tech stack | Evaluation, outcome and next steps ----
y = 673;
const pw = 222, cx = M + pw + 12, cw = COL - pw - 12;
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
top = section(cx, y, cw, 124, "clipboard-check", "Evaluation & outcome", "solid");
const ew = (cw - 16 - 18) / 4;
[["135", "automated tests, all passing"], ["99.2%", "of server code lines covered"],
  ["100%", "of 199 planted bugs caught"], ["2", "rounds of user testing"]].forEach(([big, label], i) => {
  const ex = cx + 8 + i * (ew + 6);
  rect(ex, top - 2, ew, 40, { fill: { color: C.text1 }, rectRadius: mm(3) });
  text(ex, top, ew, 17, big, { fontSize: 48, bold: true, color: AMBER, fontFace: HEAD, align: "center",
    valign: "middle" });
  text(ex + 3, top + 18, ew - 6, 19, label, { fontSize: 22, color: C.background1, align: "center" });
});
const hw = (cw - 28) / 2;
[["OUTCOME", ["Played live at Open Day", "Anyone can join over the internet", "Ready for future Open Days"]],
  ["NEXT STEPS", ["A big-screen view for the stand", "A phone-friendly layout", "Computer players for solo games"]]]
  .forEach(([head, items], i) => {
    const hx = cx + 8 + i * (hw + 12);
    text(hx, top + 44, hw, 10, head, { fontSize: 26, bold: true, fontFace: HEAD, color: C.accent2, charSpacing: 2 });
    bullets(hx, top + 56, hw, 38, items, { color: C.background1 });
  });

(async () => {
  const out = here("poster.pptx");
  await pres.writeFile({ fileName: out });
  await applyTheme(out, THEME);
  console.log("wrote", out);
})();
