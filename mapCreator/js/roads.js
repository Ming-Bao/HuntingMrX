'use strict';

// Road network: snapping index, routing graph and road-use registry.

// Grid cell size (~200 m) and max snap distance (~800 m), in degrees
const CELL         = 0.002;
const SNAP_MAX_DEG = 0.008;

// cell key → [{a, b, aKey, bKey}]
let segGrid  = new Map();
// vertex key → [{key, dist}]
let graphAdj = new Map();

function buildIndex(geojson) {
  segGrid  = new Map();
  graphAdj = new Map();

  for (const feature of geojson.features) {
    for (const coords of getCoordLines(feature.geometry)) {
      for (let i = 0; i < coords.length - 1; i++) {
        const a = coords[i], b = coords[i + 1];
        const aKey = coordKey(a), bKey = coordKey(b);
        if (aKey === bKey) continue;

        if (!graphAdj.has(aKey)) graphAdj.set(aKey, []);
        if (!graphAdj.has(bKey)) graphAdj.set(bKey, []);
        const d = haversine(a, b);
        if (!graphAdj.get(aKey).some(e => e.key === bKey)) {
          graphAdj.get(aKey).push({ key: bKey, dist: d });
          graphAdj.get(bKey).push({ key: aKey, dist: d });
        }

        // Index the segment in every cell its bbox touches
        const seg = { a, b, aKey, bKey };
        const x0 = Math.floor(Math.min(a[0], b[0]) / CELL);
        const x1 = Math.floor(Math.max(a[0], b[0]) / CELL);
        const y0 = Math.floor(Math.min(a[1], b[1]) / CELL);
        const y1 = Math.floor(Math.max(a[1], b[1]) / CELL);
        for (let cx = x0; cx <= x1; cx++) {
          for (let cy = y0; cy <= y1; cy++) {
            const k = `${cx},${cy}`;
            if (!segGrid.has(k)) segGrid.set(k, []);
            segGrid.get(k).push(seg);
          }
        }
      }
    }
  }
}

// Nearest point on a road within SNAP_MAX_DEG, or null.
function snapToRoad(lng, lat) {
  const cx = Math.floor(lng / CELL);
  const cy = Math.floor(lat / CELL);

  let nearest    = null;
  let nearestDSq = SNAP_MAX_DEG ** 2;

  for (let r = 0; r <= 3; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dy = -r; dy <= r; dy++) {
        if (r > 0 && Math.abs(dx) < r && Math.abs(dy) < r) continue;
        for (const { a, b, aKey, bKey } of (segGrid.get(`${cx + dx},${cy + dy}`) || [])) {
          const pt = nearestPtOnSeg([lng, lat], a, b);
          const d  = distSqDeg(pt, [lng, lat]);
          if (d < nearestDSq) {
            nearestDSq = d;
            nearest = { lng: pt[0], lat: pt[1], segAKey: aKey, segBKey: bKey };
          }
        }
      }
    }
    if (nearest) break;
  }

  return nearest;
}

// ── Road-use registry ─────────────────────────────────────
// segKey → edges using it. Stores edge objects so a mode added later counts.
let usedSegs = new Map();

const segKey2 = (a, b) => a < b ? a + '|' + b : b + '|' + a;

const isRoadMode = e => e.modes.includes('BUS') || e.modes.includes('ESCOOTER');

function registerSegs(e) {
  const c = e.coordinates;
  for (let i = 0; i < c.length - 1; i++) {
    const aKey = coordKey(c[i]), bKey = coordKey(c[i + 1]);
    if (aKey === bKey) continue;
    const k = segKey2(aKey, bKey);
    if (!usedSegs.has(k)) usedSegs.set(k, []);
    usedSegs.get(k).push(e);
  }
}

function rebuildUsedSegs() {
  usedSegs = new Map();
  for (const e of edges) registerSegs(e);
}

// True if no other road-mode edge uses any of e's road segments.
function promotionSafe(e) {
  const c = e.coordinates;
  for (let i = 0; i < c.length - 1; i++) {
    const aKey = coordKey(c[i]), bKey = coordKey(c[i + 1]);
    if (aKey === bKey) continue;
    for (const own of (usedSegs.get(segKey2(aKey, bKey)) ?? [])) {
      if (own !== e && isRoadMode(own)) return false;
    }
  }
  return true;
}

// ── Dijkstra road routing ─────────────────────────────────
// Returns [from, ...road vertices, to], or [from, to] if nothing is within maxDist.
// With endpointIds, skips segments used by road-mode edges that share no endpoint.
function findRoadPath(fromNode, toNode, maxDist = Infinity, endpointIds = null) {
  const dist = new Map();
  const prev = new Map();
  const heap = [];

  function init(key, snapPt) {
    const d = haversine(snapPt, coordFromKey(key));
    if (d < (dist.get(key) ?? Infinity)) {
      dist.set(key, d);
      prev.set(key, '__src__');
      heapPush(heap, [d, key]);
    }
  }

  const blocked = (u, v) => (usedSegs.get(segKey2(u, v)) ?? []).some(own =>
    isRoadMode(own) && !endpointIds.includes(own.from) && !endpointIds.includes(own.to));

  const srcPt = [fromNode.lng, fromNode.lat];
  if (fromNode.segAKey) init(fromNode.segAKey, srcPt);
  if (fromNode.segBKey && fromNode.segBKey !== fromNode.segAKey) init(fromNode.segBKey, srcPt);

  while (heap.length > 0) {
    const [d, u] = heapPop(heap);
    if (d > maxDist) break;
    if (d > (dist.get(u) ?? Infinity)) continue;
    for (const { key: v, dist: w } of (graphAdj.get(u) || [])) {
      if (endpointIds && blocked(u, v)) continue;
      const nd = d + w;
      if (nd < (dist.get(v) ?? Infinity)) {
        dist.set(v, nd);
        prev.set(v, u);
        heapPush(heap, [nd, v]);
      }
    }
  }

  const tgtPt = [toNode.lng, toNode.lat];
  const costA = toNode.segAKey ? (dist.get(toNode.segAKey) ?? Infinity) + haversine(coordFromKey(toNode.segAKey), tgtPt) : Infinity;
  const costB = toNode.segBKey ? (dist.get(toNode.segBKey) ?? Infinity) + haversine(coordFromKey(toNode.segBKey), tgtPt) : Infinity;

  if (costA === Infinity && costB === Infinity) return [srcPt, tgtPt];

  const verts = [];
  let cur = costA <= costB ? toNode.segAKey : toNode.segBKey;
  while (cur && cur !== '__src__') {
    verts.unshift(coordFromKey(cur));
    cur = prev.get(cur);
  }

  return [srcPt, ...verts, tgtPt];
}
