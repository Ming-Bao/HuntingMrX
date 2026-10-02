'use strict';

// Settings and helpers for the generators. Run Train, Bus, then Escooter.
// Generated edges follow two rules:
// - Road exclusivity: road-mode edges without a shared endpoint never share a street.
// - Drive-by: an edge never passes near a node without stopping; it's split instead.

const BUS_CLUSTERS = [
  { name: 'Wellington',   center: [174.776, -41.286], radiusM: 2800, count: 58 },
  { name: 'Lower Hutt',   center: [174.908, -41.213], radiusM: 3200, count: 52 },
  { name: 'Johnsonville', center: [174.804, -41.228], radiusM: 2000, count: 36 },
  { name: 'Porirua',      center: [174.843, -41.137], radiusM: 2400, count: 36 },
  { name: 'Upper Hutt',   center: [175.055, -41.132], radiusM: 2500, count: 26 },
];
const ESCOOTER_CLUSTERS = [
  { name: 'Wellington',   center: [174.776, -41.286], radiusM: 2500, count: 81 },
  { name: 'Lower Hutt',   center: [174.908, -41.213], radiusM: 2800, count: 72 },
  { name: 'Johnsonville', center: [174.804, -41.228], radiusM: 1800, count: 56 },
  { name: 'Porirua',      center: [174.843, -41.137], radiusM: 2000, count: 44 },
  { name: 'Upper Hutt',   center: [175.055, -41.132], radiusM: 2200, count: 23 },
];

// Nearest-neighbour links per node on top of the spanning tree
const BUS_EXTRA_NN           = 3;
const ESCOOTER_EXTRA_NN      = 4;
// Longer bus edges don't also get escooter
const ESCOOTER_PROMOTE_MAX_M = 900;
const STITCH_MAX_M           = 5000;
const STITCH_DEGREE          = 4;
const BRIDGE_MAX_M           = 6000;
const REPAIR_MAX_M           = 4000;
const SPLIT_DEPTH            = 6;
const DRIVEBY_M              = 120;
const DRIVEBY_TRAIN_M        = 150;
// Sample road intersections rather than mid-street points
const MIN_VERTEX_DEG         = 3;
// Reuse an existing node this close instead of adding one
const MERGE_RADIUS_M         = 150;
// Road route budget as a multiple of the straight-line distance
const ROUTE_DIST_MULT        = 5;

const yld = () => new Promise(r => setTimeout(r, 0));

// "N nodes, M edges" for one mode
function modeSummary(mode) {
  const es = edges.filter(e => e.modes.includes(mode));
  return `${new Set(es.flatMap(e => [e.from, e.to])).size} nodes, ${es.length} edges`;
}

// Up to `count` spread-out road vertices within radiusM of center (farthest-point
// sampling). Prefers intersections, falling back to plain road vertices.
function sampleVertices(center, radiusM, count) {
  const inRange = [];
  for (const key of graphAdj.keys()) {
    const coord = coordFromKey(key);
    if (haversine(coord, center) <= radiusM) inRange.push({ key, coord });
  }
  const deg = c => graphAdj.get(c.key).length;
  let cands = inRange.filter(c => deg(c) >= MIN_VERTEX_DEG);
  if (cands.length < count) cands = cands.concat(inRange.filter(c => deg(c) >= 2 && deg(c) < MIN_VERTEX_DEG));
  if (!cands.length) return [];

  // Random start so every run gives a different map
  const chosen = [cands[Math.floor(Math.random() * cands.length)]];
  const minDist = new Float64Array(cands.length).fill(Infinity);

  while (chosen.length < count && chosen.length < cands.length) {
    const last = chosen[chosen.length - 1];
    let bestI = -1, bestD = -1;
    for (let i = 0; i < cands.length; i++) {
      const d = haversine(cands[i].coord, last.coord);
      if (d < minDist[i]) minDist[i] = d;
      if (minDist[i] > bestD) { bestD = minDist[i]; bestI = i; }
    }
    if (bestI < 0) break;
    chosen.push(cands[bestI]);
  }
  return chosen;
}

// Existing node near road vertex `key`, else a new one there. Null if a new
// node would break the drive-by rule for an existing edge.
function genNode(key) {
  const [lng, lat] = coordFromKey(key);
  const near = nodes.find(n => haversine([n.lng, n.lat], [lng, lat]) <= MERGE_RADIUS_M);
  if (near) return near;
  if (nearRoadModeEdge(lng, lat, DRIVEBY_M)) return null;
  return pushNode({ lng, lat, segAKey: key, segBKey: key });
}

// Adds hops from → stops[0] → … via add(). True if every hop succeeded.
function splitVia(from, stops, depth, add) {
  if (depth >= SPLIT_DEPTH) return false;
  let prev = from, ok = true;
  for (const b of stops) { ok = add(prev, b, depth + 1) && ok; prev = b; }
  return ok;
}

// Links two nodes with a road-mode edge, or adds the mode to an existing edge
// if that's safe.
function addRoadEdge(from, to, mode, depth = 0) {
  if (from.id === to.id) return false;
  const existing = findEdge(from.id, to.id);
  if (existing) {
    if (existing.modes.includes(mode)) return true;
    if (drivebyBlockers(existing.coordinates, existing.from, existing.to, nodes, DRIVEBY_M).length) return false;
    if (!promotionSafe(existing)) return false;
    existing.modes = [...existing.modes, mode];
    return true;
  }
  const coords = findRoadPath(from, to, nodeDist(from, to) * ROUTE_DIST_MULT, [from.id, to.id]);
  if (coords.length <= 2) return false;
  const blockers = drivebyBlockers(coords, from.id, to.id, nodes, DRIVEBY_M);
  if (blockers.length) return splitVia(from, [...blockers, to], depth, (a, b, d) => addRoadEdge(a, b, mode, d));
  registerSegs(pushEdge(from.id, to.id, [mode], coords));
  return true;
}

// Connects a cluster with `mode` edges: a spanning tree, extra nearest-neighbour
// links, then more links until each node has degree 2. Non-station nodes that
// can't reach 2 are dropped.
function meshCluster(cluster, mode, extraNN) {
  const byNearest = from => cluster.filter(n => n.id !== from.id)
    .sort((a, b) => nodeDist(from, a) - nodeDist(from, b));

  // Prim's MST, trying the next-closest pair when one can't be routed
  const inTree = new Set([cluster[0].id]);
  while (inTree.size < cluster.length) {
    const cands = [];
    for (const from of cluster) {
      if (!inTree.has(from.id)) continue;
      for (const to of cluster) {
        if (!inTree.has(to.id)) cands.push({ from, to, d: nodeDist(from, to) });
      }
    }
    if (!cands.length) break;
    cands.sort((a, b) => a.d - b.d || a.from.id - b.from.id || a.to.id - b.to.id);
    const hit = cands.find(c => addRoadEdge(c.from, c.to, mode));
    if (!hit) break;
    inTree.add(hit.to.id);
  }

  for (const from of cluster) {
    for (const to of byNearest(from).slice(0, extraNN)) addRoadEdge(from, to, mode);
  }

  const deg = degreeMap(mode);
  const nearest = new Map(cluster.map(n => [n.id, byNearest(n)]));
  for (const n of cluster) {
    for (const cand of nearest.get(n.id)) {
      if ((deg.get(n.id) ?? 0) >= 2) break;
      if (findEdge(n.id, cand.id)) continue;
      if (addRoadEdge(n, cand, mode)) {
        deg.set(n.id,    (deg.get(n.id)    ?? 0) + 1);
        deg.set(cand.id, (deg.get(cand.id) ?? 0) + 1);
      }
    }
  }

  const stations = trainNodeIds();
  const orphans = new Set(cluster.filter(n => (deg.get(n.id) ?? 0) < 2 && !stations.has(n.id)).map(n => n.id));
  if (orphans.size) {
    edges = edges.filter(e => !orphans.has(e.from) && !orphans.has(e.to));
    nodes = nodes.filter(n => !orphans.has(n.id));
    rebuildUsedSegs();
  }
}
