#!/usr/bin/env python3
"""Headless Selenium harness for the Hunting Mr. X map creator.

Drives mapCreator/map-creator.html in headless Firefox to generate a game map
JSON without manual clicking. Generation parameters can be injected via
window.MAPGEN_PARAMS from a JSON file.

Usage:
    python3 generate.py --out artifacts/iter1/candidate.json \
        [--params params/iter1.json] [--shots artifacts/iter1/] \
        [--no-shots] [--port 18375]

Exit codes: 0 ok, 1 setup/readiness/output failure, 2 generation JS error.
"""

import argparse
import http.server
import json
import sys
import threading
import time
from pathlib import Path

from selenium import webdriver
from selenium.webdriver.firefox.options import Options

MAPCREATOR_DIR = Path(__file__).resolve().parent.parent
UI_ELEMENT_IDS = ["toolbar", "hud", "edge-panel"]  # ids present in map-creator.html

GEN_SCRIPT = """
const cb = arguments[arguments.length - 1];
genTrain().then(() => genBus()).then(() => genEscooter())
  .then(() => cb(null), e => cb(String(e && e.stack || e)));
"""

# map-creator.js keeps its state (nodes, edges, graphAdj, roadData, map, and
# the const WELLINGTON_GEOJSON) in top-level let/const bindings. Those live in
# the page's global *lexical* scope, which geckodriver's execute_script
# sandbox cannot see (only window properties resolve). Injecting a classic
# <script> tag runs in the page realm, which shares that lexical scope, so we
# expose accessor functions on window and call those instead.
BRIDGE_SCRIPT = """
const s = document.createElement('script');
s.textContent = `
  window.__mc = {
    ready:      () => (typeof graphAdj !== 'undefined') && graphAdj.size > 0,
    fallback:   () => { roadData = WELLINGTON_GEOJSON; buildIndex(roadData); return graphAdj.size; },
    styleLoaded:() => typeof map !== 'undefined' && map != null && map.isStyleLoaded(),
    mapLoaded:  () => map.loaded(),
    fitBounds:  (w, s2, e, n) => {
      window.__shot1 = false;
      map.once('idle', () => window.__shot1 = true);
      map.fitBounds([[w, s2], [e, n]], {padding: 60, animate: false});
    },
    jumpCbd:    () => {
      window.__shot2 = false;
      map.once('idle', () => window.__shot2 = true);
      map.jumpTo({center: [174.776, -41.286], zoom: 14});
    },
    extract:    () => JSON.stringify({
      nodes: nodes.map(n => ({ id: n.id, lat: n.lat, lng: n.lng, label: n.label, offRoad: n.offRoad ?? false })),
      edges: edges.map(e => ({ from: e.from, to: e.to, modes: e.modes, coordinates: e.coordinates })),
    }),
  };
`;
document.head.appendChild(s);
"""


def die(msg, code=1):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(code)


def start_server(port):
    handler_cls = lambda *a, **kw: http.server.SimpleHTTPRequestHandler(
        *a, directory=str(MAPCREATOR_DIR), **kw)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler_cls)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def make_driver():
    opts = Options()
    opts.add_argument("--headless")
    opts.add_argument("--width=1920")
    opts.add_argument("--height=1200")
    driver = webdriver.Firefox(options=opts)
    driver.set_window_size(1920, 1200)
    driver.set_page_load_timeout(120)
    driver.set_script_timeout(600)
    return driver


def wait_ready(driver, timeout=45):
    """Poll until buildIndex has populated graphAdj; fall back to a manual
    buildIndex if the map style never loads (offline / CDN failure)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if driver.execute_script("return window.__mc.ready();"):
            return
        time.sleep(0.5)
    print("warning: map not ready after timeout, trying buildIndex fallback",
          file=sys.stderr)
    size = driver.execute_script("return window.__mc.fallback();")
    if not size:
        die("road graph is empty even after buildIndex fallback")


def wait_map_idle(driver, flag, timeout=30):
    """Poll a window flag set by map.once('idle', ...); accept map.loaded()
    as a fallback readiness signal after a few seconds."""
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        if driver.execute_script(f"return window.{flag} === true;"):
            return True
        if (time.monotonic() - start > 5
                and driver.execute_script("return window.__mc.mapLoaded();")):
            return True
        time.sleep(0.5)
    return False


def take_screenshots(driver, shots_dir, nodes):
    # isStyleLoaded() is transiently false while source updates are pending,
    # so poll briefly before concluding the style genuinely failed to load.
    start = time.monotonic()
    while not driver.execute_script("return window.__mc.styleLoaded();"):
        if time.monotonic() - start > 15:
            print("warning: map style not loaded (offline?), skipping screenshots",
                  file=sys.stderr)
            return
        time.sleep(0.5)
    shots_dir.mkdir(parents=True, exist_ok=True)
    driver.execute_script(
        "for (const id of arguments[0]) {"
        "  const el = document.getElementById(id);"
        "  if (el) el.style.display = 'none';"
        "}", UI_ELEMENT_IDS)

    lngs = [n["lng"] for n in nodes]
    lats = [n["lat"] for n in nodes]
    driver.execute_script(
        "window.__mc.fitBounds(arguments[0], arguments[1], arguments[2], arguments[3]);",
        min(lngs), min(lats), max(lngs), max(lats))
    if not wait_map_idle(driver, "__shot1"):
        print("warning: overview shot never reached idle, capturing anyway",
              file=sys.stderr)
    driver.save_screenshot(str(shots_dir / "overview.png"))

    driver.execute_script("window.__mc.jumpCbd();")
    if not wait_map_idle(driver, "__shot2"):
        print("warning: cbd shot never reached idle, capturing anyway",
              file=sys.stderr)
    driver.save_screenshot(str(shots_dir / "cbd.png"))
    print(f"screenshots saved to {shots_dir}/")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--params", help="JSON file injected as window.MAPGEN_PARAMS")
    ap.add_argument("--out", required=True, help="output map JSON path")
    ap.add_argument("--shots", help="directory for overview.png / cbd.png")
    ap.add_argument("--no-shots", action="store_true", help="skip screenshots")
    ap.add_argument("--port", type=int, default=18375, help="local HTTP port")
    args = ap.parse_args()

    params = None
    if args.params:
        try:
            params = json.loads(Path(args.params).read_text())
        except (OSError, json.JSONDecodeError) as e:
            die(f"cannot read params file {args.params}: {e}")

    server = start_server(args.port)
    driver = None
    try:
        driver = make_driver()
        driver.get(f"http://127.0.0.1:{args.port}/map-creator.html")
        driver.execute_script(BRIDGE_SCRIPT)
        wait_ready(driver)

        if params is not None:
            driver.execute_script("window.MAPGEN_PARAMS = arguments[0];", params)

        gen_err = driver.execute_async_script(GEN_SCRIPT)
        if gen_err is not None:
            die(f"generation failed in browser:\n{gen_err}", code=2)

        stats = driver.execute_script(
            "return JSON.stringify(window.__mcStats || {})")
        print(f"stats: {stats}")

        raw = driver.execute_script("return window.__mc.extract();")
        try:
            data = json.loads(raw)
        except (TypeError, json.JSONDecodeError) as e:
            die(f"extracted map is not valid JSON: {e}")
        if not data.get("nodes") or not data.get("edges"):
            die("extracted map has empty nodes or edges")

        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(data, indent=2))

        mode_counts = {}
        for edge in data["edges"]:
            for mode in edge["modes"]:
                mode_counts[mode] = mode_counts.get(mode, 0) + 1
        summary = " ".join(f"{m}={c}" for m, c in sorted(mode_counts.items()))
        print(f"nodes={len(data['nodes'])} edges={len(data['edges'])} {summary}")

        if args.shots and not args.no_shots:
            take_screenshots(driver, Path(args.shots), data["nodes"])
    finally:
        if driver is not None:
            driver.quit()
        server.shutdown()


if __name__ == "__main__":
    main()
