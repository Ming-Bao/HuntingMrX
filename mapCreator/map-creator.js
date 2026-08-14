'use strict';

// ══════════════════════════════════════════════════════════
//  CONSTANTS
// ══════════════════════════════════════════════════════════

const MODE_COLORS = {
  ESCOOTER: '#22c55e',
  BUS:      '#ef4444',
  TRAIN:    '#8b5cf6',
  FERRY:    '#06b6d4',
};

const MODE_ORDER = ['ESCOOTER', 'BUS', 'TRAIN', 'FERRY'];

function modesKey(modes) {
  const set = modes instanceof Set ? modes : new Set(modes);
  return MODE_ORDER.filter(m => set.has(m))
    .map(m => ({ ESCOOTER: 'E', BUS: 'B', TRAIN: 'T', FERRY: 'F' }[m]))
    .join('') || 'none';
}

function makePieIcon(modes) {
  const SIZE = 36;
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = SIZE;
  const ctx = canvas.getContext('2d');
  const cx = SIZE / 2, r = SIZE / 2 - 2;

  if (modes.length === 0) {
    ctx.beginPath();
    ctx.arc(cx, cx, r, 0, Math.PI * 2);
    ctx.fillStyle = '#111827';
    ctx.fill();
    ctx.strokeStyle = '#60a5fa';
    ctx.lineWidth = 2.5;
    ctx.stroke();
    return ctx.getImageData(0, 0, SIZE, SIZE);
  }

  const step = (Math.PI * 2) / modes.length;
  let angle = -Math.PI / 2;
  for (const mode of modes) {
    ctx.beginPath();
    ctx.moveTo(cx, cx);
    ctx.arc(cx, cx, r, angle, angle + step);
    ctx.closePath();
    ctx.fillStyle = MODE_COLORS[mode] || '#6b7280';
    ctx.fill();
    angle += step;
  }

  ctx.beginPath();
  ctx.arc(cx, cx, r, 0, Math.PI * 2);
  ctx.strokeStyle = '#111827';
  ctx.lineWidth = 2;
  ctx.stroke();
  return ctx.getImageData(0, 0, SIZE, SIZE);
}

const CENTER       = [174.85, -41.21]; // Wellington / Lower Hutt midpoint
const CELL         = 0.002;            // spatial grid cell size in degrees (~200 m)
const SNAP_MAX_DEG = 0.008;            // max snap distance to a road (~800 m)

// ══════════════════════════════════════════════════════════
//  STATE
// ══════════════════════════════════════════════════════════

let map      = null;
let roadData = null;
let segGrid  = new Map();  // cell key → [{a, b, aKey, bKey}]
let graphAdj = new Map();  // vertex key → [{key, dist}]

let nodes      = [];       // {id, lng, lat, label, segAKey, segBKey}
let edges      = [];       // {id, from, to, modes, coordinates}
let nextNodeId = 1;
let nextEdgeId = 1;

let activeModes    = new Set(['ESCOOTER', 'BUS']); // modes applied to new edges
let selectedEdgeId = null;
let exploreNodeId  = null; // clicked node whose direct neighbours stay lit (others dim)
let componentsMode  = false; // "Show Components" toggle — see toggleComponents()

const undoStack = [];
const UNDO_MAX  = 20;

const drag = { active: false, nodeId: null, moved: false, justDragged: false };

// ══════════════════════════════════════════════════════════
//  GEOMETRY UTILS
// ══════════════════════════════════════════════════════════

function coordKey([lng, lat]) {
  return `${lng.toFixed(6)},${lat.toFixed(6)}`;
}

function coordFromKey(key) {
  return key.split(',').map(Number);
}

function nearestPtOnSeg([px, py], [ax, ay], [bx, by]) {
  const dx = bx - ax, dy = by - ay;
  const lenSq = dx * dx + dy * dy;
  if (lenSq === 0) return [ax, ay];
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
  return [ax + t * dx, ay + t * dy];
}

function distSqDeg([ax, ay], [bx, by]) {
  return (ax - bx) ** 2 + (ay - by) ** 2;
}

function haversine([lng1, lat1], [lng2, lat2]) {
  const R  = 6_371_000;
  const φ1 = lat1 * Math.PI / 180, φ2 = lat2 * Math.PI / 180;
  const Δφ = (lat2 - lat1) * Math.PI / 180;
  const Δλ = (lng2 - lng1) * Math.PI / 180;
  const a  = Math.sin(Δφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function getCoordLines(geometry) {
  if (geometry.type === 'LineString')      return [geometry.coordinates];
  if (geometry.type === 'MultiLineString') return geometry.coordinates;
  return [];
}

// ══════════════════════════════════════════════════════════
//  SPATIAL INDEX + ROUTING GRAPH
// ══════════════════════════════════════════════════════════

function buildIndex(geojson) {
  segGrid  = new Map();
  graphAdj = new Map();

  for (const feature of geojson.features) {
    for (const coords of getCoordLines(feature.geometry)) {
      for (let i = 0; i < coords.length - 1; i++) {
        const a = coords[i], b = coords[i + 1];
        const aKey = coordKey(a), bKey = coordKey(b);
        if (aKey === bKey) continue;

        // Routing graph
        if (!graphAdj.has(aKey)) graphAdj.set(aKey, []);
        if (!graphAdj.has(bKey)) graphAdj.set(bKey, []);
        const d = haversine(a, b);
        if (!graphAdj.get(aKey).some(e => e.key === bKey)) {
          graphAdj.get(aKey).push({ key: bKey, dist: d });
          graphAdj.get(bKey).push({ key: aKey, dist: d });
        }

        // Spatial index: segment bbox → cells
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

// ══════════════════════════════════════════════════════════
//  ROAD SNAPPING
// ══════════════════════════════════════════════════════════

function snapToRoad(lng, lat) {
  const cx = Math.floor(lng / CELL);
  const cy = Math.floor(lat / CELL);

  let nearest   = null;
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

// Scans every road-graph vertex and returns the key of the closest one.
// Uses a latitude-corrected squared-degree metric (no trig in the inner loop).
function nearestRoadVertex(lng, lat) {
  const cosLat = Math.cos(lat * Math.PI / 180);
  let bestKey = null, bestDsq = Infinity;
  for (const key of graphAdj.keys()) {
    const c = coordFromKey(key);
    const dsq = ((c[0] - lng) * cosLat) ** 2 + (c[1] - lat) ** 2;
    if (dsq < bestDsq) { bestDsq = dsq; bestKey = key; }
  }
  return bestKey;
}

// ══════════════════════════════════════════════════════════
//  DIJKSTRA ROAD ROUTING
// ══════════════════════════════════════════════════════════

function heapPush(h, item) {
  h.push(item);
  let i = h.length - 1;
  while (i > 0) {
    const p = (i - 1) >> 1;
    if (h[p][0] <= h[i][0]) break;
    [h[p], h[i]] = [h[i], h[p]];
    i = p;
  }
}

function heapPop(h) {
  const top  = h[0];
  const last = h.pop();
  if (h.length > 0) {
    h[0] = last;
    let i = 0;
    for (;;) {
      const l = 2 * i + 1, r = 2 * i + 2;
      let s = i;
      if (l < h.length && h[l][0] < h[s][0]) s = l;
      if (r < h.length && h[r][0] < h[s][0]) s = r;
      if (s === i) break;
      [h[i], h[s]] = [h[s], h[i]];
      i = s;
    }
  }
  return top;
}

// opts.exclusive: skip road segments already used by other edges (see usedSegs).
// A used segment stays passable only when every owning edge shares an endpoint
// node with the edge being routed (opts.endpointIds) AND the current vertex is
// within opts.exemptM metres of that shared endpoint (opts.endpointPts, aligned
// with endpointIds) — this lets multiple edges fan out of a shared hub before
// they must diverge onto distinct roads.
function findRoadPath(fromNode, toNode, maxDist = Infinity, opts = {}) {
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

  const srcPt = [fromNode.lng, fromNode.lat];
  if (fromNode.segAKey) init(fromNode.segAKey, srcPt);
  if (fromNode.segBKey && fromNode.segBKey !== fromNode.segAKey) init(fromNode.segBKey, srcPt);

  while (heap.length > 0) {
    const [d, u] = heapPop(heap);
    if (d > maxDist) break;
    if (d > (dist.get(u) ?? Infinity)) continue;
    let uPt = null; // lazily decoded coordinate of u for the exclusivity check
    for (const { key: v, dist: w } of (graphAdj.get(u) || [])) {
      if (opts.exclusive) {
        const owners = usedSegs.get(segKey2(u, v));
        if (owners && owners.length) {
          // Station-hub co-running: within hubM of a train-station endpoint,
          // any owner is tolerated (modes converge on interchange streets).
          if (opts.hubPt && haversine(coordFromKey(u), opts.hubPt) <= opts.hubM &&
              haversine(coordFromKey(v), opts.hubPt) <= opts.hubM) {
            const nd0 = d + w;
            if (nd0 < (dist.get(v) ?? Infinity)) {
              dist.set(v, nd0);
              prev.set(v, u);
              heapPush(heap, [nd0, v]);
            }
            continue;
          }
          let blocked = false;
          for (const own of owners) {
            // Rail-only edges don't occupy the road — trains run on tracks;
            // the polyline is just the visual approximation.
            if (!own.modes.includes('BUS') && !own.modes.includes('ESCOOTER')) continue;
            // Edges that meet at a node may co-run along the same street for
            // any distance (routes fanning out of a shared hub before
            // diverging — the classic board look). Only edges with no common
            // endpoint must keep to distinct roads.
            if (own.from !== opts.endpointIds[0] && own.to !== opts.endpointIds[0] &&
                own.from !== opts.endpointIds[1] && own.to !== opts.endpointIds[1]) {
              blocked = true;
              break;
            }
          }
          if (blocked) continue;
        }
      }
      const nd = d + w;
      if (nd < (dist.get(v) ?? Infinity)) {
        dist.set(v, nd);
        prev.set(v, u);
        heapPush(heap, [nd, v]);
      }
    }
  }

  const tgtPt = [toNode.lng, toNode.lat];
  const costA  = toNode.segAKey ? (dist.get(toNode.segAKey) ?? Infinity) + haversine(coordFromKey(toNode.segAKey), tgtPt) : Infinity;
  const costB  = toNode.segBKey ? (dist.get(toNode.segBKey) ?? Infinity) + haversine(coordFromKey(toNode.segBKey), tgtPt) : Infinity;

  if (costA === Infinity && costB === Infinity) return [srcPt, tgtPt];

  const endKey = costA <= costB ? toNode.segAKey : toNode.segBKey;
  const verts  = [];
  let cur = endKey;
  while (cur && cur !== '__src__') {
    verts.unshift(coordFromKey(cur));
    cur = prev.get(cur);
  }

  return [srcPt, ...verts, tgtPt];
}

// Greedy farthest-point sampling of road-graph vertices within a geographic cluster.
// Returns up to `count` vertices that maximise spatial spread inside `radiusM`.
// Candidates are restricted to intersections (degree >= minVertexDeg) so placed
// nodes have enough departure directions under road exclusivity; if that starves
// the cluster, degree-2 chain vertices are appended as a deterministic fallback.
function sampleVertices(center, radiusM, count) {
  const inRange = [];
  for (const key of graphAdj.keys()) {
    const coord = coordFromKey(key);
    if (haversine(coord, center) <= radiusM) inRange.push({ key, coord });
  }
  const minDeg = MAPGEN().minVertexDeg ?? 3;
  let cands = inRange.filter(c => (graphAdj.get(c.key) || []).length >= minDeg);
  if (cands.length < count && minDeg > 2) {
    const fallback = inRange.filter(c => {
      const deg = (graphAdj.get(c.key) || []).length;
      return deg >= 2 && deg < minDeg;
    });
    cands = cands.concat(fallback);
  }
  if (!cands.length) return [];

  let seedIdx = 0, seedD = Infinity;
  for (let i = 0; i < cands.length; i++) {
    const d = haversine(cands[i].coord, center);
    if (d < seedD) { seedD = d; seedIdx = i; }
  }
  const chosen = [cands[seedIdx]];
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

// ══════════════════════════════════════════════════════════
//  UNDO
// ══════════════════════════════════════════════════════════

function saveUndo() {
  undoStack.push({
    nodes: structuredClone(nodes),
    edges: structuredClone(edges),
    nextNodeId,
    nextEdgeId,
  });
  if (undoStack.length > UNDO_MAX) undoStack.shift();
  document.getElementById('btn-undo').disabled = false;
}

function undo() {
  if (!undoStack.length) return;
  const snap = undoStack.pop();
  nodes      = snap.nodes;
  edges      = snap.edges;
  nextNodeId = snap.nextNodeId;
  nextEdgeId = snap.nextEdgeId;
  if (selectedEdgeId !== null) selectEdge(null);
  refreshSources();
  updateCounts();
  if (!undoStack.length) document.getElementById('btn-undo').disabled = true;
  setStatus(`Undo — ${nodes.length} nodes, ${edges.length} edges`);
}

// ══════════════════════════════════════════════════════════
//  NODE / EDGE MANAGEMENT
// ══════════════════════════════════════════════════════════

function addNode(snap) {
  saveUndo();
  const id   = nextNodeId++;
  const node = { id, lng: snap.lng, lat: snap.lat, label: String(id), segAKey: snap.segAKey, segBKey: snap.segBKey };
  nodes.push(node);
  refreshSources();
  updateCounts();
  return node;
}

function renameNode(id) {
  const node = nodes.find(n => n.id === id);
  if (!node) return;
  const newLabel = prompt(`Rename node ${id}:`, node.label);
  if (newLabel !== null) {
    saveUndo();
    node.label = newLabel.trim() || node.label;
    refreshSources();
  }
}

function removeNode(id) {
  saveUndo();
  nodes = nodes.filter(n => n.id !== id);
  edges = edges.filter(e => e.from !== id && e.to !== id);
  refreshSources();
  updateCounts();
}

// Finds a node by exact id (numeric input) or, failing that, by exact label
// (case-insensitive) — flies the camera there and reuses the click-to-explore
// highlight so its neighbours are shown too. Reports failure via the status
// bar rather than throwing, since "doesn't exist" is an expected outcome here.
function searchNode(query) {
  const q = query.trim();
  if (!q) { setStatus('Enter a node id or name to search'); return; }

  let target = /^\d+$/.test(q) ? nodes.find(n => n.id === Number(q)) : null;
  if (!target) target = nodes.find(n => n.label.toLowerCase() === q.toLowerCase());

  if (!target) {
    setStatus(`Node "${q}" doesn't exist`);
    return;
  }

  exploreNodeId = target.id;
  refreshSources();
  map.flyTo({ center: [target.lng, target.lat], zoom: Math.max(map.getZoom(), 16) });
  const named = target.label !== String(target.id) ? ` (${target.label})` : '';
  setStatus(`Found node ${target.id}${named} — showing its neighbours`);
}

// Relabels every node 1..N in reading order: top-left to bottom-right.
// Buckets nodes into ~440 m north-south bands ("rows") and sorts west-to-east
// within each band — a plain two-key sort on raw lat/lng would order strictly
// by latitude with no row grouping, which for a real (non-gridded) road
// network doesn't read as rows at all. Only touches `label`, never `id` —
// ids are what edges reference, so this can't break connectivity.
function renumberNodesReadingOrder() {
  if (!nodes.length) { setStatus('No nodes to renumber'); return; }
  if (!confirm(`Relabel all ${nodes.length} nodes 1–${nodes.length}, top-left to bottom-right? This overwrites existing labels.`)) return;

  saveUndo();
  const ROW_HEIGHT_DEG = 0.004; // ≈ 440 m north-south band
  const maxLat = Math.max(...nodes.map(n => n.lat));
  const rowOf = n => Math.floor((maxLat - n.lat) / ROW_HEIGHT_DEG);

  const ordered = [...nodes].sort((a, b) => rowOf(a) - rowOf(b) || a.lng - b.lng);
  ordered.forEach((n, i) => { n.label = String(i + 1); });

  refreshSources();
  setStatus(`Renumbered ${nodes.length} node labels, top-left → bottom-right`);
}

function addEdge(fromId, toId) {
  const from = nodes.find(n => n.id === fromId);
  const to   = nodes.find(n => n.id === toId);
  if (!from || !to || fromId === toId) return;

  if (edges.some(e =>
    (e.from === fromId && e.to === toId) ||
    (e.from === toId   && e.to === fromId)
  )) {
    setStatus('Edge already exists between these nodes');
    return;
  }

  saveUndo();
  setStatus('Routing…');

  let coordinates;

  // Ferries cross open water — there's no road-adjacent geometry to follow
  // at all, unlike trains (which can still hug a rail-corridor-ish path) or
  // buses/escooters (actual roads). Always point-to-point, unconditionally,
  // ahead of and regardless of the train/road logic below.
  const drawingFerry = activeModes.has('FERRY');

  // "Train" here means the node already carries a TRAIN edge (nodesGJ's
  // hasTrain), not the offRoad flag — offRoad is only ever set by the
  // auto-generator, so a manually-placed or merged station (offRoad false)
  // wouldn't otherwise count even though it's a real station.
  const nodeHasTrain = id => edges.some(e => (e.from === id || e.to === id) && e.modes.includes('TRAIN'));
  const fromTrain = nodeHasTrain(fromId);
  const toTrain   = nodeHasTrain(toId);
  const drawingTrain = activeModes.has('TRAIN');

  let mixedTrainRoad;
  if (fromTrain === toTrain) {
    // Both train, or both non-train — not a train/road mismatch by itself.
    // Train↔train always routes normally (below); for non-train↔non-train,
    // fall back to the legacy offRoad check (road↔road always routes
    // normally too, so this only ever matters for the rare case where one
    // side is offRoad without carrying a TRAIN edge yet).
    mixedTrainRoad = !fromTrain && ((from.offRoad && !to.offRoad) || (!from.offRoad && to.offRoad));
  } else {
    // Exactly one end already has TRAIN. If we're actively drawing a TRAIN
    // edge, this is a real train connection (a new station, or hooking into
    // an existing bus/escooter node as an interchange) and should route
    // normally, same as train↔train. Otherwise it's a genuine train/road
    // mismatch — no rail-adjacent geometry to follow, so use a straight line.
    mixedTrainRoad = !drawingTrain;
  }
  if (!roadData || !graphAdj.size || drawingFerry || mixedTrainRoad) {
    coordinates = [[from.lng, from.lat], [to.lng, to.lat]];
  } else {
    const fromSnap = snapToRoad(from.lng, from.lat);
    const toSnap   = snapToRoad(to.lng,   to.lat);

    if (!fromSnap || !toSnap) {
      coordinates = [[from.lng, from.lat], [to.lng, to.lat]];
    } else {
      const road = findRoadPath(fromSnap, toSnap);
      const pts  = [...road];

      if (haversine([from.lng, from.lat], [fromSnap.lng, fromSnap.lat]) > 5)
        pts.unshift([from.lng, from.lat]);
      if (haversine([to.lng, to.lat], [toSnap.lng, toSnap.lat]) > 5)
        pts.push([to.lng, to.lat]);

      coordinates = pts.filter((p, i) => i === 0 || p[0] !== pts[i-1][0] || p[1] !== pts[i-1][1]);
    }
  }

  edges.push({ id: nextEdgeId++, from: fromId, to: toId, modes: [...activeModes], coordinates });
  refreshSources();
  updateCounts();
  setStatus('Ready');
}

function removeEdge(id) {
  saveUndo();
  edges = edges.filter(e => e.id !== id);
  refreshSources();
  updateCounts();
}

// ══════════════════════════════════════════════════════════
//  GEOJSON BUILDERS
// ══════════════════════════════════════════════════════════

// IDs of nodes directly connected to `id` by an edge.
function neighbourIdSet(id) {
  const s = new Set();
  for (const e of edges) {
    if (e.from === id) s.add(e.to);
    if (e.to === id)   s.add(e.from);
  }
  return s;
}

function nodesGJ() {
  const nodeModes = new Map();
  for (const e of edges) {
    for (const m of e.modes) {
      if (!nodeModes.has(e.from)) nodeModes.set(e.from, new Set());
      if (!nodeModes.has(e.to))   nodeModes.set(e.to,   new Set());
      nodeModes.get(e.from).add(m);
      nodeModes.get(e.to).add(m);
    }
  }

  // Auto-heals if the explored node was since deleted (undo, right-click
  // delete, reload) — falls back to "nothing dimmed" instead of a stale ID
  // matching no node and dimming everything.
  const exploring = exploreNodeId != null && nodes.some(n => n.id === exploreNodeId);
  const nbrs = exploring ? neighbourIdSet(exploreNodeId) : null;

  return {
    type: 'FeatureCollection',
    features: nodes.map(n => {
      const ms = nodeModes.get(n.id) ?? new Set();
      return {
        type: 'Feature',
        properties: {
          id: n.id, label: n.label,
          modesKey:    modesKey(ms),
          hasEscooter: ms.has('ESCOOTER'),
          hasBus:      ms.has('BUS'),
          hasTrain:    ms.has('TRAIN'),
          hasFerry:    ms.has('FERRY'),
          dimmed: exploring && n.id !== exploreNodeId && !nbrs.has(n.id),
        },
        geometry: { type: 'Point', coordinates: [n.lng, n.lat] },
      };
    }),
  };
}

function edgesGJ() {
  const SPACING = 4.5;
  const features = [];

  const exploring = exploreNodeId != null && nodes.some(n => n.id === exploreNodeId);

  // Collect every (edge, mode) pair grouped by node-pair so that
  // separate edge objects between the same two nodes are offset together.
  // pairMap key: "smallerId-largerId"
  const pairMap = new Map();
  for (const e of edges) {
    const modes = e.modes.length ? e.modes : ['BUS'];
    const key = `${Math.min(e.from, e.to)}-${Math.max(e.from, e.to)}`;
    if (!pairMap.has(key)) pairMap.set(key, []);
    for (const mode of modes) pairMap.get(key).push({ e, mode });
  }

  // Sort each group by canonical mode order so the layout is deterministic
  const modeRank = m => MODE_ORDER.indexOf(m);
  for (const items of pairMap.values()) {
    items.sort((a, b) => modeRank(a.mode) - modeRank(b.mode));
    const n = items.length;
    items.forEach(({ e, mode }, i) => {
      // Only edges touching the explored node itself stay lit — showing "what
      // connects here", not the wider neighbourhood.
      const dimmed = exploring && e.from !== exploreNodeId && e.to !== exploreNodeId;
      features.push({
        type: 'Feature',
        properties: { id: e.id, mode, lineOffset: n === 1 ? 0 : (i - (n - 1) / 2) * SPACING, dimmed },
        geometry: { type: 'LineString', coordinates: e.coordinates },
      });
    });
  }

  return { type: 'FeatureCollection', features };
}

function previewGJ(coords) {
  return {
    type: 'FeatureCollection',
    features: coords
      ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } }]
      : [],
  };
}

// ══════════════════════════════════════════════════════════
//  MAP INITIALISATION
// ══════════════════════════════════════════════════════════

// Shared dim expressions — clicking a node ("explore") fades everything
// except it and its direct neighbours, so the local connectivity is obvious
// even in the densest parts of the graph.
const DIMMED_NODE_OPACITY        = ['case', ['boolean', ['get', 'dimmed'], false], 0.18, 1];
const DIMMED_EDGE_OPACITY        = ['case', ['boolean', ['get', 'dimmed'], false], 0.1,  1];
const DIMMED_EDGE_CASING_OPACITY = ['case', ['boolean', ['get', 'dimmed'], false], 0.08, 0.8];

function initMap() {
  map = new maplibregl.Map({
    container: 'map',
    style: 'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json',
    center: CENTER,
    zoom: 12,
    attributionControl: false,
  });

  map.addControl(new maplibregl.NavigationControl(), 'top-right');
  map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');

  map.on('load', () => {
    map.addSource('roads-display',  { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    map.addSource('edges',          { type: 'geojson', data: edgesGJ() });
    map.addSource('selected-edge',  { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    map.addSource('preview',        { type: 'geojson', data: previewGJ(null) });
    map.addSource('nodes',          { type: 'geojson', data: nodesGJ() });

    // Pre-generate pie chart icons for all 16 mode combinations
    for (let mask = 0; mask < 16; mask++) {
      const modes = MODE_ORDER.filter((_, i) => mask & (1 << i));
      map.addImage(`node-pie-${modesKey(new Set(modes))}`, makePieIcon(modes));
    }

    map.addLayer({
      id: 'roads-display', type: 'line', source: 'roads-display',
      paint: { 'line-color': '#94a3b8', 'line-width': 1.5, 'line-opacity': 0.5 },
    });

    // Selected edge halo — drawn below game edges
    map.addLayer({
      id: 'selected-edge', type: 'line', source: 'selected-edge',
      paint: { 'line-color': '#ffffff', 'line-width': 11, 'line-opacity': 0.35 },
    });

    // Wide rail casing beneath the coloured edges: bus spurs legitimately run
    // on the rail alignment, so without this band the purple line disappears
    // under the red one.
    map.addLayer({
      id: 'edges-train-casing', type: 'line', source: 'edges',
      filter: ['==', ['get', 'mode'], 'TRAIN'],
      paint: {
        'line-color': MODE_COLORS.TRAIN,
        'line-width': 10,
        'line-offset': ['get', 'lineOffset'],
        'line-opacity': DIMMED_EDGE_CASING_OPACITY,
      },
    });

    map.addLayer({
      id: 'edges', type: 'line', source: 'edges',
      paint: {
        'line-color': ['match', ['get', 'mode'],
          'ESCOOTER', MODE_COLORS.ESCOOTER,
          'BUS',      MODE_COLORS.BUS,
          'TRAIN',    MODE_COLORS.TRAIN,
          'FERRY',    MODE_COLORS.FERRY,
          '#94a3b8',
        ],
        'line-width': 4,
        'line-offset': ['get', 'lineOffset'],
        'line-opacity': DIMMED_EDGE_OPACITY,
      },
    });

    map.addLayer({
      id: 'preview', type: 'line', source: 'preview',
      paint: { 'line-color': '#fff', 'line-width': 2, 'line-dasharray': [4, 3], 'line-opacity': 0.55 },
    });

    map.addLayer({
      id: 'nodes', type: 'symbol', source: 'nodes',
      layout: {
        'icon-image': ['concat', 'node-pie-', ['get', 'modesKey']],
        'icon-size': ['interpolate', ['linear'], ['zoom'], 12, 0.44, 15, 0.67, 18, 1.0],
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
      },
      paint: { 'icon-opacity': DIMMED_NODE_OPACITY },
    });

    map.addLayer({
      id: 'node-labels', type: 'symbol', source: 'nodes',
      layout: {
        'text-field': ['to-string', ['get', 'id']],
        'text-size': ['interpolate', ['linear'], ['zoom'], 12, 9, 18, 13],
        'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
        'text-anchor': 'center',
        'text-allow-overlap': true,
        'text-ignore-placement': true,
      },
      paint: { 'text-color': '#ffffff', 'text-opacity': DIMMED_NODE_OPACITY },
    });

    map.addLayer({
      id: 'node-name-labels', type: 'symbol', source: 'nodes',
      layout: {
        'text-field': ['get', 'label'],
        'text-size': 10,
        'text-font': ['Open Sans Regular', 'Arial Unicode MS Regular'],
        'text-anchor': 'top',
        'text-offset': [0, 1.2],
        'text-allow-overlap': false,
      },
      paint: {
        'text-color': '#d1d5db',
        'text-halo-color': '#000000',
        'text-halo-width': 1.5,
        'text-opacity': DIMMED_NODE_OPACITY,
      },
    });

    // "Show Components" overlay — hidden until toggled (see toggleComponents).
    map.addSource('components-edges', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    map.addSource('components-nodes', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });

    map.addLayer({
      id: 'components-edges', type: 'line', source: 'components-edges',
      layout: { visibility: 'none' },
      paint: { 'line-color': ['get', 'color'], 'line-width': 5, 'line-opacity': 0.9 },
    });

    map.addLayer({
      id: 'components-nodes', type: 'circle', source: 'components-nodes',
      layout: { visibility: 'none' },
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 12, 7, 18, 14],
        'circle-color': ['get', 'color'],
        'circle-stroke-color': '#000000',
        'circle-stroke-width': 1.5,
      },
    });

    setupInteractions();
    autoLoadRoads();
  });
}

// ══════════════════════════════════════════════════════════
//  MAP SOURCE REFRESH
// ══════════════════════════════════════════════════════════

function refreshSources() {
  if (!map) return;
  map.getSource('nodes')?.setData(nodesGJ());
  map.getSource('edges')?.setData(edgesGJ());
  map.triggerRepaint();
}

function setPreview(coords) {
  map?.getSource('preview')?.setData(previewGJ(coords));
}

// ══════════════════════════════════════════════════════════
//  CONNECTED COMPONENTS ("Show Components" button)
// ══════════════════════════════════════════════════════════
// The finished map is supposed to be one single connected graph (see the
// auto-generator's own bridge-disconnected-components pass) — this is a
// manual way to check that after hand-edits too: colours every connected
// component differently so any stray island jumps out at a glance.

const COMPONENT_PALETTE = [
  '#ef4444', '#3b82f6', '#22c55e', '#eab308', '#a855f7',
  '#ec4899', '#06b6d4', '#f97316', '#84cc16', '#6366f1',
];

function computeComponents() {
  const adj = new Map();
  for (const n of nodes) adj.set(n.id, []);
  for (const e of edges) {
    adj.get(e.from)?.push(e.to);
    adj.get(e.to)?.push(e.from);
  }

  const compOf = new Map(); // nodeId -> component index
  const sizes  = [];
  for (const n of nodes) {
    if (compOf.has(n.id)) continue;
    const idx = sizes.length;
    let size = 0;
    const stack = [n.id];
    compOf.set(n.id, idx);
    while (stack.length) {
      const cur = stack.pop();
      size++;
      for (const nb of adj.get(cur) ?? []) {
        if (!compOf.has(nb)) { compOf.set(nb, idx); stack.push(nb); }
      }
    }
    sizes.push(size);
  }

  return { compOf, sizes };
}

function toggleComponents() {
  componentsMode = !componentsMode;
  const btn = document.getElementById('btn-show-components');
  const graphLayers = ['edges-train-casing', 'edges', 'nodes', 'node-labels', 'node-name-labels'];
  const compLayers  = ['components-edges', 'components-nodes'];

  if (componentsMode) {
    const { compOf, sizes } = computeComponents();
    const color = idx => COMPONENT_PALETTE[idx % COMPONENT_PALETTE.length];

    map.getSource('components-nodes')?.setData({
      type: 'FeatureCollection',
      features: nodes.map(n => ({
        type: 'Feature',
        properties: { id: n.id, comp: compOf.get(n.id) ?? -1, color: color(compOf.get(n.id) ?? 0) },
        geometry: { type: 'Point', coordinates: [n.lng, n.lat] },
      })),
    });
    map.getSource('components-edges')?.setData({
      type: 'FeatureCollection',
      features: edges.map(e => ({
        type: 'Feature',
        properties: { comp: compOf.get(e.from) ?? -1, color: color(compOf.get(e.from) ?? 0) },
        geometry: { type: 'LineString', coordinates: e.coordinates },
      })),
    });

    for (const id of graphLayers) map.setLayoutProperty(id, 'visibility', 'none');
    for (const id of compLayers)  map.setLayoutProperty(id, 'visibility', 'visible');
    btn.classList.add('active');
    btn.textContent = 'Hide Components';

    const sorted = [...sizes].sort((a, b) => b - a);
    setStatus(sizes.length === 1
      ? `1 connected component — fully connected (${sorted[0]} nodes)`
      : `${sizes.length} connected components — sizes: ${sorted.join(', ')}`);
  } else {
    for (const id of graphLayers) map.setLayoutProperty(id, 'visibility', 'visible');
    for (const id of compLayers)  map.setLayoutProperty(id, 'visibility', 'none');
    btn.classList.remove('active');
    btn.textContent = 'Show Components';
    setStatus('Ready');
  }
}

// ══════════════════════════════════════════════════════════
//  INTERACTIONS
// ══════════════════════════════════════════════════════════

function setupInteractions() {
  map.on('mouseenter', 'nodes', () => { map.getCanvas().style.cursor = 'grab'; });
  map.on('mouseleave', 'nodes', () => { map.getCanvas().style.cursor = ''; });
  map.on('mouseenter', 'edges', () => { map.getCanvas().style.cursor = 'pointer'; });
  map.on('mouseleave', 'edges', () => { map.getCanvas().style.cursor = ''; });

  map.on('mousedown', e => {
    if (e.originalEvent.button !== 0) return; // left-click only
    const hit = map.queryRenderedFeatures(e.point, { layers: ['nodes'] });
    if (hit.length === 0) return;
    drag.active = true;
    drag.nodeId = hit[0].properties.id;
    drag.moved  = false;
    map.dragPan.disable();
    e.preventDefault();
  });

  map.on('mousemove', e => {
    if (!drag.active) return;
    const from = nodes.find(n => n.id === drag.nodeId);
    if (!from) return;
    drag.moved = true;
    setPreview([[from.lng, from.lat], [e.lngLat.lng, e.lngLat.lat]]);
  });

  map.on('mouseup', e => {
    if (!drag.active) return;
    setPreview(null);
    map.dragPan.enable();

    if (drag.moved) {
      const hit = map.queryRenderedFeatures(e.point, { layers: ['nodes'] });
      if (hit.length > 0 && hit[0].properties.id !== drag.nodeId) {
        addEdge(drag.nodeId, hit[0].properties.id);
      }
    }

    drag.active      = false;
    drag.nodeId      = null;
    if (drag.moved) drag.justDragged = true;
    drag.moved       = false;
  });

  map.getCanvas().addEventListener('mouseleave', () => {
    if (!drag.active) return;
    setPreview(null);
    map.dragPan.enable();
    drag.active      = false;
    drag.nodeId      = null;
    drag.moved       = false;
    drag.justDragged = false;
  });

  map.on('click', e => {
    if (drag.justDragged) { drag.justDragged = false; return; }

    const nodeHit = map.queryRenderedFeatures(e.point, { layers: ['nodes'] });
    if (nodeHit.length > 0) {
      selectEdge(null);
      const id = nodeHit[0].properties.id;
      // Click again on the same node to clear the highlight.
      exploreNodeId = exploreNodeId === id ? null : id;
      refreshSources();
      return;
    }

    selectEdge(null);
    if (exploreNodeId !== null) { exploreNodeId = null; refreshSources(); }

    const snap = snapToRoad(e.lngLat.lng, e.lngLat.lat);
    if (!snap) { setStatus('No road found nearby — zoom in and click closer to a road'); return; }

    addNode(snap);
    setStatus(`Node ${nextNodeId - 1} added`);
  });

  // Renaming moved off single-click (now "show neighbours") to double-click.
  // preventDefault() stops MapLibre's built-in double-click-to-zoom so a
  // rename doesn't also yank the camera in.
  map.on('dblclick', e => {
    const nodeHit = map.queryRenderedFeatures(e.point, { layers: ['nodes'] });
    if (nodeHit.length === 0) return;
    e.preventDefault();
    selectEdge(null);
    renameNode(nodeHit[0].properties.id);
  });

  map.on('contextmenu', e => {
    e.preventDefault?.();
    const nodeHit = map.queryRenderedFeatures(e.point, { layers: ['nodes'] });
    const edgeHit = map.queryRenderedFeatures(e.point, { layers: ['edges'] });

    if (nodeHit.length > 0) {
      selectEdge(null);
      removeNode(nodeHit[0].properties.id);
    } else if (edgeHit.length > 0) {
      selectEdge(edgeHit[0].properties.id);
    }
  });
}


// ══════════════════════════════════════════════════════════
//  EDGE SELECTION + MODE EDITOR
// ══════════════════════════════════════════════════════════

function selectEdge(id) {
  selectedEdgeId = id;
  const panel = document.getElementById('edge-panel');

  if (id === null) {
    panel.style.display = 'none';
    map.getSource('selected-edge')?.setData({ type: 'FeatureCollection', features: [] });
    return;
  }

  const edge = edges.find(e => e.id === id);
  if (!edge) { selectEdge(null); return; }

  // Highlight selected edge
  map.getSource('selected-edge')?.setData({
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: edge.coordinates } }],
  });

  // Update panel
  const fromNode = nodes.find(n => n.id === edge.from);
  const toNode   = nodes.find(n => n.id === edge.to);
  document.getElementById('ep-label').textContent =
    `${fromNode?.label ?? edge.from} → ${toNode?.label ?? edge.to}`;
  document.querySelectorAll('.ep-mode-btn').forEach(btn =>
    btn.classList.toggle('active', edge.modes.includes(btn.dataset.mode))
  );
  panel.style.display = 'flex';
  map.triggerRepaint();
}

// Edge panel buttons
document.querySelectorAll('.ep-mode-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    if (selectedEdgeId === null) return;
    const edge = edges.find(e => e.id === selectedEdgeId);
    if (!edge) return;
    saveUndo();
    const m = btn.dataset.mode;
    if (edge.modes.includes(m)) {
      if (edge.modes.length > 1) edge.modes = edge.modes.filter(x => x !== m);
    } else {
      edge.modes.push(m);
    }
    btn.classList.toggle('active', edge.modes.includes(m));
    refreshSources();
  });
});

document.getElementById('ep-delete').addEventListener('click', () => {
  if (selectedEdgeId !== null) { removeEdge(selectedEdgeId); selectEdge(null); }
});
document.getElementById('ep-close').addEventListener('click', () => selectEdge(null));

// ══════════════════════════════════════════════════════════
//  SAVE / LOAD
// ══════════════════════════════════════════════════════════

function saveMap() {
  const output = {
    nodes: nodes.map(n => ({ id: n.id, lat: n.lat, lng: n.lng, label: n.label, offRoad: n.offRoad ?? false })),
    edges: edges.map(e => ({ from: e.from, to: e.to, modes: e.modes, coordinates: e.coordinates })),
  };

  const blob = new Blob([JSON.stringify(output, null, 2)], { type: 'application/json' });
  const url  = URL.createObjectURL(blob);
  Object.assign(document.createElement('a'), { href: url, download: 'hunting-mrx-map.json' }).click();
  URL.revokeObjectURL(url);
  setStatus(`Saved — ${nodes.length} nodes, ${edges.length} edges`);
}

function loadMapFile(file) {
  const reader = new FileReader();
  reader.onload = ev => {
    try {
      const data = JSON.parse(ev.target.result);
      saveUndo();
      nodes = []; edges = []; nextNodeId = 1; nextEdgeId = 1;

      for (const n of data.nodes ?? []) {
        nodes.push({ id: n.id, lng: n.lng, lat: n.lat, label: n.label ?? `Node ${n.id}`, segAKey: null, segBKey: null, offRoad: n.offRoad ?? false });
        nextNodeId = Math.max(nextNodeId, n.id + 1);
      }
      for (const e of data.edges ?? []) {
        edges.push({ id: nextEdgeId++, from: e.from, to: e.to, modes: e.modes, coordinates: e.coordinates ?? [] });
      }

      refreshSources();
      updateCounts();
      document.getElementById('btn-save-map').disabled = false;
      setStatus(`Loaded — ${nodes.length} nodes, ${edges.length} edges`);

      if (nodes.length > 0) {
        const lngs = nodes.map(n => n.lng), lats = nodes.map(n => n.lat);
        map.fitBounds(
          [[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]],
          { padding: 80, maxZoom: 15 }
        );
      }
    } catch (err) {
      setStatus('Error loading map: ' + err.message);
    }
  };
  reader.readAsText(file);
}

function autoLoadRoads() {
  setStatus('Loading road data…');
  roadData = WELLINGTON_GEOJSON;
  map.getSource('roads-display')?.setData(roadData);

  const allCoords = [];
  for (const f of roadData.features) {
    for (const line of getCoordLines(f.geometry)) allCoords.push(...line);
  }
  if (allCoords.length > 0) {
    const lngs = allCoords.map(c => c[0]), lats = allCoords.map(c => c[1]);
    map.fitBounds(
      [[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]],
      { padding: 40 }
    );
  }

  setStatus('Building road graph…');
  setTimeout(() => {
    buildIndex(roadData);
    document.getElementById('btn-save-map').disabled = false;
    document.getElementById('btn-auto-gen').disabled = false;
    document.getElementById('btn-gen-train').disabled = false;
    document.getElementById('btn-gen-bus').disabled = false;
    document.getElementById('btn-gen-escooter').disabled = false;
    setStatus(`Ready — ${graphAdj.size.toLocaleString()} road vertices. Click a road to place a node.`);
  }, 30);
}

// ══════════════════════════════════════════════════════════
//  AUTO-GENERATE
// ══════════════════════════════════════════════════════════

async function autoGenerate() {
  if (!graphAdj.size) { setStatus('Road graph not ready yet'); return; }
  if ((nodes.length || edges.length) && !confirm('Clear all existing nodes and edges and auto-generate?')) return;

  saveUndo();
  const btn = document.getElementById('btn-auto-gen');
  btn.disabled = true;

  // ── 1. Collect road vertices and shuffle ──────────────────
  const candidates = [];
  for (const key of graphAdj.keys()) {
    const [lng, lat] = coordFromKey(key);
    candidates.push({ lng, lat, key });
  }
  for (let i = candidates.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [candidates[i], candidates[j]] = [candidates[j], candidates[i]];
  }

  // ── 2. Minimum-distance sampling (~300 m apart) ───────────
  const MIN_DIST = 0.0025;
  const TARGET   = 200;
  const selected = [];
  for (const c of candidates) {
    if (selected.length >= TARGET) break;
    if (!selected.some(s => Math.hypot(c.lng - s.lng, c.lat - s.lat) < MIN_DIST))
      selected.push(c);
  }

  // ── 3. Place nodes ────────────────────────────────────────
  nodes = []; edges = []; nextNodeId = 1; nextEdgeId = 1;
  for (const c of selected) {
    nodes.push({ id: nextNodeId++, lng: c.lng, lat: c.lat,
                 label: String(nextNodeId - 1), segAKey: c.key, segBKey: c.key });
  }
  updateCounts();
  refreshSources();
  setStatus(`Placed ${nodes.length} nodes — routing edges…`);
  await new Promise(r => setTimeout(r, 0));

  // ── 4. Per-node nearest-neighbour lists (within MAX_EDGE_DEG) ─
  const MAX_DEGREE   = 4;
  const MAX_EDGE_DEG = 0.025; // ~2.5 km Euclidean cap for candidate pairs

  // Build sorted neighbour list for each node cheaply by scanning pairs once
  const nbrs = Array.from({ length: nodes.length }, () => []); // nbrs[i] = [{j, d}] sorted asc
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      const d = Math.hypot(nodes[i].lng - nodes[j].lng, nodes[i].lat - nodes[j].lat);
      if (d <= MAX_EDGE_DEG) { nbrs[i].push({ j, d }); nbrs[j].push({ i: j, j: i, d }); }
    }
  }
  for (const list of nbrs) list.sort((a, b) => a.d - b.d);

  // ── 5. Edge builder — returns false if no road path or path too long ─
  const MAX_EDGE_M = 1500; // road path must be ≤ 1500 m
  const degree = new Array(nodes.length).fill(0);
  const added  = new Set();

  function pathLengthM(coords) {
    let total = 0;
    for (let k = 1; k < coords.length; k++) total += haversine(coords[k - 1], coords[k]);
    return total;
  }

  function commitEdge(i, j) {
    const a = Math.min(i, j), b = Math.max(i, j);
    const key = `${a}-${b}`;
    if (added.has(key)) return true;
    const na = nodes[a], nb = nodes[b];
    const dm = haversine([na.lng, na.lat], [nb.lng, nb.lat]);
    const coords = findRoadPath(na, nb, Math.min(dm * 5, MAX_EDGE_M * 1.5));
    if (coords.length === 2) return false;          // no road path
    if (pathLengthM(coords) > MAX_EDGE_M) return false; // road path too long
    edges.push({ id: nextEdgeId++, from: na.id, to: nb.id, modes: ['ESCOOTER'], coordinates: coords });
    degree[a]++; degree[b]++;
    added.add(key);
    return true;
  }

  // ── 6. Connect each node to its nearest road-reachable neighbours ─
  let done = 0;
  for (let idx = 0; idx < nodes.length; idx++) {
    for (const { j } of nbrs[idx]) {
      if (degree[idx] >= MAX_DEGREE) break;       // this node is full
      if (degree[j]   >= MAX_DEGREE) continue;    // neighbour is full, try next
      commitEdge(idx, j);
    }
    if (++done % 10 === 0) {
      updateCounts(); refreshSources();
      setStatus(`Connecting nodes… ${edges.length} edges`);
      await new Promise(r => setTimeout(r, 0));
    }
  }

  // ── 7. Remove nodes that got no connections at all ────────
  const isolatedIds = new Set(
    nodes.filter((_, i) => degree[i] === 0).map(n => n.id)
  );
  if (isolatedIds.size > 0) {
    nodes = nodes.filter(n => !isolatedIds.has(n.id));
    setStatus(`Removed ${isolatedIds.size} isolated nodes — ${nodes.length} nodes, ${edges.length} edges`);
  }

  refreshSources();
  updateCounts();
  document.getElementById('btn-save-map').disabled = false;
  btn.disabled = false;
  setStatus(`Generated ${nodes.length} nodes, ${edges.length} edges`);
}

// ══════════════════════════════════════════════════════════
//  ROUTE-BASED GENERATION  (Train / Bus / Escooter)
// ══════════════════════════════════════════════════════════

const TRAIN_STEP_M   = 1200;  // target spacing between consecutive train nodes
const BUS_STEP_M     = 500;   // target spacing between consecutive bus nodes
let   MERGE_RADIUS_M = 150;   // nodes closer than this are merged, not duplicated
const ESCOOTER_MAX_M = 400;   // max straight-line distance for an escooter edge
const MAX_BRIDGE_M   = 500;   // max gap to bridge between disconnected train components

// Optional overrides for headless/scripted generation. A harness can set
// window.MAPGEN_PARAMS before calling the gen functions; the browser UI is
// unaffected when it is absent (all call sites fall back to the defaults).
const MAPGEN = () => window.MAPGEN_PARAMS ?? {};

// Generation diagnostics, surfaced by the headless harness (generate.py).
window.__mcStats = { drivebySplits: 0, splitDepthFails: 0, exclusiveFails: 0, trainFallbacks: 0, mstRetries: 0 };

// ── Road-exclusivity registry ─────────────────────────────
// Maps each 6-dp road segment key to the edge objects whose polylines traverse
// it, so findRoadPath can refuse already-used segments. Edge objects (not id
// pairs) are stored so ownership reflects an edge's CURRENT modes: a TRAIN-only
// edge is rail, not road, and does not occupy the road for exclusivity — but
// if it later gains ESCOOTER (promotion) it becomes a road occupant.
let usedSegs = new Map(); // segKey -> [edge, ...]

const segKey2 = (a, b) => a < b ? a + '|' + b : b + '|' + a;

// Road-route budget as a multiple of crow-flies distance. The anchor pass
// temporarily raises it: under road exclusivity, short hops from a station
// may legitimately need long detours around already-claimed streets.
let ROUTE_DIST_MULT = 5;

// Set by the station-anchor pass: {pt: [lng,lat], m: radius}. Within m of pt
// (the station being anchored), routing tolerates any segment owner —
// station-hub co-running for interchange streets.
let ANCHOR_HUB = null;

// All distinct road-graph vertices within maxM of a point, nearest first.
// Used to re-seed routing for stations whose snapToRoad segment is a
// motorway-class way with no nearby graph exit onto local streets.
function nearbyVertexKeys(lng, lat, maxM, limit) {
  const cx = Math.floor(lng / CELL), cy = Math.floor(lat / CELL);
  const R = Math.ceil((maxM / 111320) / CELL) + 1;
  const seen = new Map();
  for (let dx = -R; dx <= R; dx++) {
    for (let dy = -R; dy <= R; dy++) {
      for (const { aKey, bKey } of (segGrid.get(`${cx + dx},${cy + dy}`) || [])) {
        for (const k of [aKey, bKey]) {
          if (seen.has(k)) continue;
          const d = haversine([lng, lat], coordFromKey(k));
          if (d <= maxM) seen.set(k, d);
        }
      }
    }
  }
  return [...seen.entries()]
    .sort((x, y) => x[1] - y[1] || (x[0] < y[0] ? -1 : 1))
    .slice(0, limit)
    .map(e => e[0]);
}

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

// A rail-only edge may gain a road mode (promotion) only if none of its
// segments are already occupied by a road-mode edge — otherwise promoting it
// would retroactively create a shared-road violation.
function promotionSafe(e) {
  const c = e.coordinates;
  for (let i = 0; i < c.length - 1; i++) {
    const aKey = coordKey(c[i]), bKey = coordKey(c[i + 1]);
    if (aKey === bKey) continue;
    for (const own of (usedSegs.get(segKey2(aKey, bKey)) ?? [])) {
      if (own === e) continue;
      if (own.modes.includes('BUS') || own.modes.includes('ESCOOTER')) return false;
    }
  }
  return true;
}

// ── Drive-by rule helpers ─────────────────────────────────
// Equirectangular point-to-segment distance at Wellington's latitude:
// cheap, accurate to well under a metre over the corridor scales used here.
const WLG_COS   = Math.cos(-41.25 * Math.PI / 180); // ≈ 0.7518
const M_PER_DEG = 6_371_000 * Math.PI / 180;        // ≈ 111,195 m per degree

function ptSegDistM(p, a, b) {
  const px = (p[0] - a[0]) * WLG_COS, py = p[1] - a[1];
  const bx = (b[0] - a[0]) * WLG_COS, by = b[1] - a[1];
  const lenSq = bx * bx + by * by;
  const t = lenSq === 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / lenSq));
  const dx = px - t * bx, dy = py - t * by;
  return { d: Math.sqrt(dx * dx + dy * dy) * M_PER_DEG, t };
}

// Nodes (other than the edge's own endpoints) that the polyline passes within
// corridorM of, sorted by fraction along the polyline so a blocked edge can be
// split into from → b1 → … → to hops.
function drivebyBlockers(coords, fromId, toId, blockerNodes, corridorM) {
  const nSegs = coords.length - 1;
  if (nSegs < 1) return [];
  const hits = [];
  for (const n of blockerNodes) {
    if (n.id === fromId || n.id === toId) continue;
    let bestD = Infinity, bestT = 0;
    for (let i = 0; i < nSegs; i++) {
      const { d, t } = ptSegDistM([n.lng, n.lat], coords[i], coords[i + 1]);
      if (d < bestD) { bestD = d; bestT = (i + t) / nSegs; }
    }
    if (bestD < corridorM) hits.push({ n, t: bestT });
  }
  hits.sort((x, y) => x.t - y.t || x.n.id - y.n.id);
  return hits.map(h => h.n);
}

// True when [lng,lat] lies within corridorM of any BUS/ESCOOTER edge polyline —
// used to refuse creating new nodes that would retroactively violate the
// drive-by rule for edges that already exist.
function nearRoadModeEdge(lng, lat, corridorM) {
  for (const e of edges) {
    if (!e.modes.includes('BUS') && !e.modes.includes('ESCOOTER')) continue;
    const c = e.coordinates;
    for (let i = 0; i < c.length - 1; i++) {
      if (ptSegDistM([lng, lat], c[i], c[i + 1]).d < corridorM) return true;
    }
  }
  return false;
}

// Strip all edges of the given mode, then remove any nodes left with no edges.
function clearModeEdges(mode) {
  edges = edges
    .map(e => ({ ...e, modes: e.modes.filter(m => m !== mode) }))
    .filter(e => e.modes.length > 0);
  const connected = new Set(edges.flatMap(e => [e.from, e.to]));
  nodes = nodes.filter(n => connected.has(n.id));
  refreshSources();
  updateCounts();
}

// ── Entry points (button handlers) ───────────────────────

// Keep segments that have at least one point inside the game area.
// Stops the Kapiti Line at Porirua (lat ≈ -41.13) and Hutt/Wairarapa at Upper Hutt (lat ≈ -41.12).
function inTrainGameArea([lng, lat]) {
  return lat >= -41.40 && lat <= -41.12 && lng >= 174.70 && lng <= 175.15;
}

// Sort an array of [[lng,lat],...] polylines into a geographically coherent
// order starting from Wellington Station, so that consecutive ways share their
// endpoints. Each way is also oriented (possibly reversed) so its first coord
// is the one nearest to the previous way's last coord. This ensures upsertNode
// merges consecutive way endpoints rather than leaving gaps that connectTrainComponents
// would bridge with wrong straight-line edges.
function sortWaysFromOrigin(ways) {
  if (ways.length <= 1) return ways;
  const WGN = [174.7795, -41.2790]; // Wellington Station

  function orientToward(way, ref) {
    const dF = haversine(way[0], ref);
    const dL = haversine(way[way.length - 1], ref);
    return dL < dF ? [...way].reverse() : way;
  }

  // Seed: pick the way whose nearest endpoint is closest to Wellington Station
  let seedIdx = 0, seedD = Infinity;
  for (let i = 0; i < ways.length; i++) {
    const d = Math.min(haversine(ways[i][0], WGN), haversine(ways[i][ways[i].length - 1], WGN));
    if (d < seedD) { seedD = d; seedIdx = i; }
  }

  const sorted = [orientToward(ways[seedIdx], WGN)];
  const used   = new Set([seedIdx]);

  // Greedy nearest-neighbour: at each step extend the chain from its current
  // end to the closest unused way (handles straight lines and branches alike).
  while (used.size < ways.length) {
    const end = sorted[sorted.length - 1];
    const ref = end[end.length - 1];

    let bestD = Infinity, bestIdx = -1, bestRev = false;
    for (let i = 0; i < ways.length; i++) {
      if (used.has(i)) continue;
      const dS = haversine(ways[i][0], ref);
      const dE = haversine(ways[i][ways[i].length - 1], ref);
      if (dS < bestD) { bestD = dS; bestIdx = i; bestRev = false; }
      if (dE < bestD) { bestD = dE; bestIdx = i; bestRev = true;  }
    }
    if (bestIdx === -1) break;
    sorted.push(bestRev ? [...ways[bestIdx]].reverse() : ways[bestIdx]);
    used.add(bestIdx);
  }
  return sorted;
}

// Sample 5 evenly-spaced points from a polyline (start, 25%, 50%, 75%, end).
function sampleWayPts(line) {
  return [0, 0.25, 0.5, 0.75, 1].map(t =>
    line[Math.min(Math.floor(t * line.length), line.length - 1)]
  );
}

// Drop near-duplicate rail way segments that represent parallel tracks
// (northbound / southbound).
// Two ways are considered parallel if ≥2 of their 5 sample-point pairs are
// within radiusM of each other. When a parallel is found, the way whose
// midpoint is MORE SOUTHERN (lower latitude = lower on a north-up map) is
// kept; the other is discarded.
function deduplicateParallelWays(lines, radiusM = 80) {
  const kept     = [];
  const keptPts  = [];

  outer: for (const line of lines) {
    const pts = sampleWayPts(line);

    for (let ki = 0; ki < kept.length; ki++) {
      const kpts = keptPts[ki];
      let closeCount = 0;
      for (const p of pts) {
        if (kpts.some(kp => haversine(p, kp) < radiusM)) closeCount++;
      }
      if (closeCount >= 2) {
        // Parallel — keep whichever midpoint is more southern (lower latitude).
        if (pts[2][1] < kpts[2][1]) {
          kept[ki]    = line;
          keptPts[ki] = pts;
        }
        continue outer;
      }
    }

    kept.push(line);
    keptPts.push(pts);
  }
  return kept;
}

async function genTrain() {
  if (!graphAdj.size) { setStatus('Road graph not ready'); return; }
  if (typeof WELLINGTON_TRAIN_LINES === 'undefined') {
    setStatus('wellington-train-stops.js not loaded'); return;
  }
  saveUndo();
  const btn = document.getElementById('btn-gen-train');
  btn.disabled = true;
  clearModeEdges('TRAIN');
  rebuildUsedSegs();
  window.__mcStats = { drivebySplits: 0, splitDepthFails: 0, exclusiveFails: 0, trainFallbacks: 0, mstRetries: 0 };

  setStatus('Placing train nodes at actual station positions…');

  // ── helpers ────────────────────────────────────────────────────────────────

  function addTrainNode(lng, lat) {
    const snap = snapToRoad(lng, lat);
    const n = { id: nextNodeId++, lng, lat, label: String(nextNodeId - 1),
                segAKey: snap?.segAKey ?? null, segBKey: snap?.segBKey ?? null,
                offRoad: true };
    nodes.push(n);
    return n;
  }

  function nearestTrain(coord, radiusM) {
    let best = null, bestD = radiusM;
    for (const n of nodes) {
      const d = haversine([n.lng, n.lat], coord);
      if (d < bestD) { bestD = d; best = n; }
    }
    return best;
  }

  // All unique station nodes across every line — the drive-by blocker set for
  // TRAIN edges (a train may not pass a station without stopping there).
  const trainStations = [];

  function trainFail(from, to, depth, reason) {
    if ((__mcStats.trainFails ??= []).length < 100) {
      __mcStats.trainFails.push({ f: from.id, t: to.id, d: depth, r: reason });
    }
    return false;
  }

  function tryAddTrainEdge(from, to, depth = 0) {
    if (from.id === to.id) return false;
    // Mode-aware dedup: only an existing TRAIN-carrying edge counts as success.
    const existingT = edges.find(e => (e.from===from.id&&e.to===to.id)||(e.from===to.id&&e.to===from.id));
    if (existingT) return existingT.modes.includes('TRAIN') || trainFail(from, to, depth, 'dedup-mode');
    if (!from.segAKey || !to.segAKey) return trainFail(from, to, depth, 'nosnap');
    const dist = haversine([from.lng, from.lat], [to.lng, to.lat]);
    const excl = MAPGEN().roadExclusive ?? true;
    const opts = excl ? {
      exclusive: true,
      endpointIds: [from.id, to.id],
      endpointPts: [[from.lng, from.lat], [to.lng, to.lat]],
      exemptM: MAPGEN().exclusiveExemptEndM ?? 100,
      ...(ANCHOR_HUB ? { hubPt: ANCHOR_HUB.pt, hubM: ANCHOR_HUB.m } : {}),
    } : {};
    let coords = findRoadPath(from, to, dist * 3, opts);
    if (coords.length <= 2 && excl) {
      // Rail corridors legitimately share track (e.g. Wellington approach) —
      // fall back to a non-exclusive route rather than losing the line.
      __mcStats.trainFallbacks++;
      coords = findRoadPath(from, to, dist * 3, {});
    }
    if (coords.length <= 2) return trainFail(from, to, depth, 'noroute'); // no road path found, skip beeline
    const blockers = drivebyBlockers(coords, from.id, to.id, trainStations,
                                     MAPGEN().drivebyCorridorTrainM ?? 150);
    if (blockers.length) {
      if (depth >= (MAPGEN().drivebySplitDepth ?? 2)) { __mcStats.splitDepthFails++; return trainFail(from, to, depth, 'splitdepth'); }
      __mcStats.drivebySplits++;
      if ((__mcStats.trainSplits ??= []).length < 50) {
        __mcStats.trainSplits.push({ f: from.id, t: to.id, d: depth, via: blockers.map(b => b.id) });
      }
      let prev = from, ok = true;
      for (const b of [...blockers, to]) { ok = tryAddTrainEdge(prev, b, depth + 1) && ok; prev = b; }
      return ok;
    }
    const e = { id: nextEdgeId++, from: from.id, to: to.id, modes: ['TRAIN'], coordinates: coords };
    edges.push(e);
    registerSegs(e);
    if ((__mcStats.trainAdds ??= []).length < 60) __mcStats.trainAdds.push([from.id, to.id]);
    return true;
  }

  // ── pass 1: place/merge every station across all lines ─────────────────────
  // Edges are only added once ALL stations exist, so the drive-by blocker set
  // is complete even for the first line processed.

  const stationIds = new Set();
  const lineNodes  = [];
  for (const line of WELLINGTON_TRAIN_LINES) {
    const resolved = [];
    for (const coord of line) {
      // Merge stations shared across lines (e.g. Wellington Station, Petone)
      const node = nearestTrain(coord, MERGE_RADIUS_M) ?? addTrainNode(coord[0], coord[1]);
      resolved.push(node);
      if (!stationIds.has(node.id)) { stationIds.add(node.id); trainStations.push(node); }
    }
    lineNodes.push(resolved);
  }

  // ── pass 2: connect consecutive stops per line ──────────────────────────────

  for (let li = 0; li < lineNodes.length; li++) {
    const resolved = lineNodes[li];
    for (let i = 1; i < resolved.length; i++) tryAddTrainEdge(resolved[i - 1], resolved[i]);
    setStatus(`Train: line ${li + 1}/${lineNodes.length}…`);
    refreshSources(); updateCounts();
    await yld();
  }

  refreshSources();
  updateCounts();
  document.getElementById('btn-save-map').disabled = false;
  const tEdges = edges.filter(e => e.modes.includes('TRAIN'));
  const tNodes = new Set(tEdges.flatMap(e => [e.from, e.to]));
  setStatus(`Train done — ${tNodes.size} nodes, ${tEdges.length} edges`);
  btn.disabled = false;
}

async function genBus() {
  if (!graphAdj.size) { setStatus('Road graph not ready'); return; }
  saveUndo();
  const btn = document.getElementById('btn-gen-bus');
  btn.disabled = true;
  clearModeEdges('BUS');
  rebuildUsedSegs();

  // Anchor every train station to a non-train node via "park & ride" spurs.
  // Runs pre-mesh (start of genBus): for each rail hop with an unanchored
  // endpoint, place a spur node at the MIDPOINT OF THE RAIL POLYLINE ITSELF —
  // the road path is already proven (the train routes it), rail-only
  // ownership never blocks road modes, and pre-mesh there is nothing nearby
  // to brush, so both half-edges are valid by construction. One spur anchors
  // both of its stations.
  if ((MAPGEN().anchorStationsMaxM ?? 4000) > 0) {
    const trainEdges = edges.filter(e => e.modes.includes('TRAIN'));
    const trainIdSet = new Set(trainEdges.flatMap(e => [e.from, e.to]));
    const anchoredSet = new Set();
    for (const e of edges) {
      if (trainIdSet.has(e.from) && !trainIdSet.has(e.to)) anchoredSet.add(e.from);
      if (trainIdSet.has(e.to) && !trainIdSet.has(e.from)) anchoredSet.add(e.to);
    }
    // Greedy: rail hops covering two unanchored stations first.
    const railSorted = [...trainEdges].sort((a, b) => {
      const ua = (anchoredSet.has(a.from) ? 0 : 1) + (anchoredSet.has(a.to) ? 0 : 1);
      const ub = (anchoredSet.has(b.from) ? 0 : 1) + (anchoredSet.has(b.to) ? 0 : 1);
      return ub - ua || a.from - b.from || a.to - b.to;
    });
    // A spur point must keep clear of every existing node AND every other
    // polyline — parallel rail lines co-run out of shared stations (e.g.
    // Petone), and a spur on one line placed inside the shared stretch sits
    // in the drive-by corridor of the other line's half-edges.
    const clearM = (MAPGEN().drivebyCorridorM ?? 120) + 15;
    const spurPointOk = (pt, re) => {
      if (!nodes.every(o => haversine([o.lng, o.lat], pt) >= clearM)) return false;
      for (const e of edges) {
        if (e === re || !e.coordinates) continue;
        const pc = e.coordinates;
        for (let i = 0; i < pc.length - 1; i++) {
          if (ptSegDistM(pt, pc[i], pc[i + 1]).d < clearM) return false;
        }
      }
      return true;
    };
    let spurs = 0;
    for (const re of railSorted) {
      if (anchoredSet.has(re.from) && anchoredSet.has(re.to)) continue;
      const c = re.coordinates;
      if (!c || c.length < 4) continue;
      // Middle-out search for a clear spur point on the rail polyline.
      let mid = -1;
      const center = Math.floor(c.length / 2);
      for (let off = 0; off <= c.length / 2; off++) {
        for (const i of off === 0 ? [center] : [center - off, center + off]) {
          if (i < 1 || i > c.length - 2) continue;
          if (spurPointOk(c[i], re)) { mid = i; break; }
        }
        if (mid >= 0) break;
      }
      if (mid < 0) continue; // no clear point on this hop — try another hop
      const vk = coordKey(c[mid]);
      const nv = { id: nextNodeId++, lng: c[mid][0], lat: c[mid][1],
                   label: String(nextNodeId - 1), segAKey: vk, segBKey: vk };
      nodes.push(nv);
      const eA = { id: nextEdgeId++, from: re.from, to: nv.id, modes: ['BUS'],
                   coordinates: c.slice(0, mid + 1) };
      const eB = { id: nextEdgeId++, from: nv.id, to: re.to, modes: ['BUS'],
                   coordinates: c.slice(mid) };
      edges.push(eA); registerSegs(eA);
      edges.push(eB); registerSegs(eB);
      anchoredSet.add(re.from); anchoredSet.add(re.to);
      (__mcStats.spurNodes ??= []).push({ spur: nv.id, between: [re.from, re.to] });
      spurs++;
    }
    __mcStats.anchored = anchoredSet.size;
    (__mcStats.anchorFails ??= []).push(
      ...[...trainIdSet].filter(id => !anchoredSet.has(id)).map(id => ({ station: id, cands: 0 })));
    setStatus(`Anchored ${anchoredSet.size} stations via ${spurs} park-and-ride spurs…`);
    await yld();
  }

  // Four geographic clusters.  Nodes are sampled from road-graph vertices
  // inside each cluster radius, then connected via Dijkstra road routing.
  const CLUSTERS = MAPGEN().busClusters ?? [
    { name: 'Wellington',   center: [174.776, -41.286], radiusM: 2800, count: 20 },
    { name: 'Lower Hutt',   center: [174.908, -41.213], radiusM: 3200, count: 20 },
    { name: 'Johnsonville', center: [174.804, -41.228], radiusM: 2000, count: 15 },
    { name: 'Porirua',      center: [174.843, -41.137], radiusM: 2400, count: 15 },
  ];

  // ── helpers ────────────────────────────────────────────────────────────────

  function makeNode(key) {
    const [lng, lat] = coordFromKey(key);
    // Reuse any existing node (e.g. a train station) within merge radius so bus
    // stops don't duplicate stations and the tiers share hubs.
    for (const n of nodes) {
      if (haversine([n.lng, n.lat], [lng, lat]) <= MERGE_RADIUS_M) return n;
    }
    // A brand-new node sitting inside an existing edge's drive-by corridor would
    // retroactively violate the rule — refuse to create it.
    if (nearRoadModeEdge(lng, lat, MAPGEN().drivebyCorridorM ?? 120)) return null;
    // Node is placed exactly at a road-graph vertex: segAKey = segBKey = the vertex key.
    // findRoadPath initialises Dijkstra at distance 0 from this vertex, so routing works perfectly.
    const n = { id: nextNodeId++, lng, lat, label: String(nextNodeId - 1),
                segAKey: key, segBKey: key };
    nodes.push(n);
    return n;
  }

  function addBusEdge(from, to, depth = 0) {
    if (from.id === to.id) return false;
    // Mode-aware dedup: an existing edge only counts if it carries (or can
    // safely gain) this mode — otherwise a stitch/split "succeeds" without
    // giving the endpoints a BUS-traversable link.
    const existing = edges.find(e => (e.from===from.id&&e.to===to.id)||(e.from===to.id&&e.to===from.id));
    if (existing) {
      if (existing.modes.includes('BUS')) return true;
      const blk = drivebyBlockers(existing.coordinates, existing.from, existing.to, nodes, MAPGEN().drivebyCorridorM ?? 120);
      if (blk.length) return false; // polyline passes other nodes — unsafe as a road-mode edge
      if (!promotionSafe(existing)) return false; // its road is already taken by a road-mode edge
      existing.modes = [...existing.modes, 'BUS'];
      return true;
    }
    const d = haversine([from.lng, from.lat], [to.lng, to.lat]);
    const excl = MAPGEN().roadExclusive ?? true;
    const opts = excl ? {
      exclusive: true,
      endpointIds: [from.id, to.id],
      endpointPts: [[from.lng, from.lat], [to.lng, to.lat]],
      exemptM: MAPGEN().exclusiveExemptEndM ?? 100,
      ...(ANCHOR_HUB ? { hubPt: ANCHOR_HUB.pt, hubM: ANCHOR_HUB.m } : {}),
    } : {};
    const coords = findRoadPath(from, to, d * ROUTE_DIST_MULT, opts);
    if (coords.length <= 2) { __mcStats.exclusiveFails++; return false; } // no usable road path, skip beeline
    const blockers = drivebyBlockers(coords, from.id, to.id, nodes, MAPGEN().drivebyCorridorM ?? 120);
    if (blockers.length) {
      // The route passes other nodes — split it into hops that stop at each.
      if (depth >= (MAPGEN().drivebySplitDepth ?? 2)) { __mcStats.splitDepthFails++; return false; }
      __mcStats.drivebySplits++;
      let prev = from, ok = true;
      for (const b of [...blockers, to]) { ok = addBusEdge(prev, b, depth + 1) && ok; prev = b; }
      return ok;
    }
    const e = { id: nextEdgeId++, from: from.id, to: to.id, modes: ['BUS'], coordinates: coords };
    edges.push(e);
    registerSegs(e);
    return true;
  }

  // ── build each cluster ─────────────────────────────────────────────────────

  for (const cl of CLUSTERS) {
    setStatus(`Bus: sampling ${cl.name}…`);
    await yld();

    const verts   = sampleVertices(cl.center, cl.radiusM, cl.count);
    const cluster = verts.map(v => makeNode(v.key)).filter(Boolean);

    // Train stations inside (or near) the cluster join it as first-class bus
    // stops — real interchanges. The MST/extraNN machinery then wires them in
    // with proper endpoint exemptions, instead of a later pass fighting for
    // streets the bus network has already claimed.
    {
      const inCluster = new Set(cluster.map(n => n.id));
      const stations = edges.filter(e => e.modes.includes('TRAIN')).flatMap(e => [e.from, e.to]);
      const stationIds = [...new Set(stations)].sort((a, b) => a - b);
      const byId = new Map(nodes.map(n => [n.id, n]));
      for (const sid of stationIds) {
        if (inCluster.has(sid)) continue;
        const s = byId.get(sid);
        if (!s || !s.segAKey) continue;
        if (haversine([s.lng, s.lat], cl.center) <= cl.radiusM * 1.5) {
          cluster.push(s);
          inCluster.add(sid);
        }
      }
    }

    if (cluster.length < 2) continue;

    // Prim's MST with retry — under exclusivity/drive-by rejection the closest
    // pair may fail to route, so try candidate pairs in ascending distance
    // until one succeeds instead of adding the single best pair blindly.
    const inTree = new Set([cluster[0].id]);

    while (inTree.size < cluster.length) {
      const cands = [];
      for (const from of cluster) {
        if (!inTree.has(from.id)) continue;
        for (const to of cluster) {
          if (inTree.has(to.id)) continue;
          cands.push({ from, to, d: haversine([from.lng, from.lat], [to.lng, to.lat]) });
        }
      }
      if (!cands.length) break;
      cands.sort((a, b) => a.d - b.d || a.from.id - b.from.id || a.to.id - b.to.id);
      let added = false;
      for (const c of cands) {
        if (addBusEdge(c.from, c.to)) { inTree.add(c.to.id); added = true; break; }
        __mcStats.mstRetries++;
      }
      if (!added) break; // no routable pair left — remaining nodes stay out of tree
    }

    // Extra connections: each node also links to its 2 nearest cluster neighbours
    // (beyond the MST edges) so the network has cycles and looks richer.
    for (const from of cluster) {
      const sorted = cluster
        .filter(n => n.id !== from.id)
        .sort((a, b) => haversine([from.lng, from.lat], [a.lng, a.lat])
                      - haversine([from.lng, from.lat], [b.lng, b.lat]));
      for (const to of sorted.slice(0, MAPGEN().busExtraNN ?? 2)) addBusEdge(from, to);
    }

    // Enforce minimum degree of 2: keep trying further neighbours, then drop
    // nodes that still can't reach 2 routable connections.
    {
      const deg = new Map(cluster.map(n => [n.id, 0]));
      for (const e of edges) {
        if (!e.modes.includes('BUS')) continue;
        if (deg.has(e.from)) deg.set(e.from, deg.get(e.from) + 1);
        if (deg.has(e.to))   deg.set(e.to,   deg.get(e.to)   + 1);
      }
      const byDist = new Map(cluster.map(n => [n.id,
        cluster.filter(o => o.id !== n.id)
               .sort((a, b) => haversine([n.lng, n.lat], [a.lng, a.lat])
                             - haversine([n.lng, n.lat], [b.lng, b.lat]))]));
      for (const n of cluster) {
        for (const cand of byDist.get(n.id)) {
          if (deg.get(n.id) >= 2) break;
          // Skip already-connected pairs: addBusEdge now reports those as
          // success, which would double-count degrees already tallied above.
          if (edges.some(e => (e.from===n.id&&e.to===cand.id)||(e.from===cand.id&&e.to===n.id))) continue;
          if (addBusEdge(n, cand)) {
            deg.set(n.id,    deg.get(n.id)    + 1);
            deg.set(cand.id, (deg.get(cand.id) ?? 0) + 1);
          }
        }
      }
      // Never orphan-drop a node carrying a TRAIN edge — deleting it would
      // silently sever the rail line (stations merged into clusters rarely
      // win 2 same-mode edges; the stitch/repair passes handle their degree).
      const trainTouched = new Set(edges.filter(e => e.modes.includes('TRAIN')).flatMap(e => [e.from, e.to]));
      const orphans = new Set(cluster.filter(n => deg.get(n.id) < 2 && !trainTouched.has(n.id)).map(n => n.id));
      if (orphans.size) {
        edges = edges.filter(e => !orphans.has(e.from) && !orphans.has(e.to));
        nodes = nodes.filter(n => !orphans.has(n.id));
      }
    }

    refreshSources(); updateCounts();
    setStatus(`Bus: ${cl.name} done (${cluster.length} nodes)…`);
    await yld();
  }

  refreshSources();
  updateCounts();
  document.getElementById('btn-save-map').disabled = false;
  const bEdges = edges.filter(e => e.modes.includes('BUS'));
  const bNodes = new Set(bEdges.flatMap(e => [e.from, e.to]));
  setStatus(`Bus done — ${bNodes.size} nodes, ${bEdges.length} edges`);
  btn.disabled = false;
}

async function genEscooter() {
  if (!graphAdj.size) { setStatus('Road graph not ready'); return; }
  saveUndo();
  const btn = document.getElementById('btn-gen-escooter');
  btn.disabled = true;
  clearModeEdges('ESCOOTER');
  rebuildUsedSegs();

  // Most bus edges are also valid escooter routes — add the mode directly.
  // Edges longer than escooterPromoteMaxM stay BUS-only, preserving a mid tier.
  const maxPromoteM = MAPGEN().escooterPromoteMaxM ?? Infinity;
  const promoteNodeById = new Map(nodes.map(n => [n.id, n]));
  let busPromoted = 0;
  for (const e of edges) {
    if (e.modes.includes('BUS') && !e.modes.includes('ESCOOTER')) {
      const a = promoteNodeById.get(e.from), b = promoteNodeById.get(e.to);
      if (a && b && haversine([a.lng, a.lat], [b.lng, b.lat]) > maxPromoteM) continue;
      e.modes = [...e.modes, 'ESCOOTER'];
      busPromoted++;
    }
  }


  // 90 dedicated escooter nodes across the same 4 clusters as bus, but with
  // higher node density (shorter inter-node spacing) due to more nodes per cluster.
  const CLUSTERS = MAPGEN().escooterClusters ?? [
    { name: 'Wellington',   center: [174.776, -41.286], radiusM: 2500, count: 28 },
    { name: 'Lower Hutt',   center: [174.908, -41.213], radiusM: 2800, count: 25 },
    { name: 'Johnsonville', center: [174.804, -41.228], radiusM: 1800, count: 20 },
    { name: 'Porirua',      center: [174.843, -41.137], radiusM: 2000, count: 17 },
  ];

  // Returns the existing node within MERGE_RADIUS_M, or creates a new one at `key`.
  function getOrMakeNode(key) {
    const [lng, lat] = coordFromKey(key);
    for (const n of nodes) {
      if (haversine([n.lng, n.lat], [lng, lat]) <= MERGE_RADIUS_M) return n;
    }
    // A brand-new node sitting inside an existing edge's drive-by corridor would
    // retroactively violate the rule — refuse to create it.
    if (nearRoadModeEdge(lng, lat, MAPGEN().drivebyCorridorM ?? 120)) return null;
    const n = { id: nextNodeId++, lng, lat, label: String(nextNodeId - 1),
                segAKey: key, segBKey: key };
    nodes.push(n);
    return n;
  }

  function addEscooterEdge(from, to, depth = 0) {
    if (from.id === to.id) return false;
    // Mode-aware dedup (see addBusEdge): only count existing edges that carry
    // or can safely gain ESCOOTER.
    const existing = edges.find(e => (e.from===from.id&&e.to===to.id)||(e.from===to.id&&e.to===from.id));
    if (existing) {
      if (existing.modes.includes('ESCOOTER')) return true;
      const blk = drivebyBlockers(existing.coordinates, existing.from, existing.to, nodes, MAPGEN().drivebyCorridorM ?? 120);
      if (blk.length) return false; // polyline passes other nodes — unsafe as a road-mode edge
      if (!promotionSafe(existing)) return false; // its road is already taken by a road-mode edge
      existing.modes = [...existing.modes, 'ESCOOTER'];
      return true;
    }
    const d = haversine([from.lng, from.lat], [to.lng, to.lat]);
    const excl = MAPGEN().roadExclusive ?? true;
    const opts = excl ? {
      exclusive: true,
      endpointIds: [from.id, to.id],
      endpointPts: [[from.lng, from.lat], [to.lng, to.lat]],
      exemptM: MAPGEN().exclusiveExemptEndM ?? 100,
      ...(ANCHOR_HUB ? { hubPt: ANCHOR_HUB.pt, hubM: ANCHOR_HUB.m } : {}),
    } : {};
    const coords = findRoadPath(from, to, d * ROUTE_DIST_MULT, opts);
    if (coords.length <= 2) { __mcStats.exclusiveFails++; return false; } // no usable road path, skip beeline
    const blockers = drivebyBlockers(coords, from.id, to.id, nodes, MAPGEN().drivebyCorridorM ?? 120);
    if (blockers.length) {
      // The route passes other nodes — split it into hops that stop at each.
      if (depth >= (MAPGEN().drivebySplitDepth ?? 2)) { __mcStats.splitDepthFails++; return false; }
      __mcStats.drivebySplits++;
      let prev = from, ok = true;
      for (const b of [...blockers, to]) { ok = addEscooterEdge(prev, b, depth + 1) && ok; prev = b; }
      return ok;
    }
    const e = { id: nextEdgeId++, from: from.id, to: to.id, modes: ['ESCOOTER'], coordinates: coords };
    edges.push(e);
    registerSegs(e);
    return true;
  }

  for (const cl of CLUSTERS) {
    setStatus(`Escooter: sampling ${cl.name}…`);
    await yld();

    const verts   = sampleVertices(cl.center, cl.radiusM, cl.count);
    const cluster = verts.map(v => getOrMakeNode(v.key)).filter(Boolean);

    if (cluster.length < 2) continue;

    // Prim's MST with retry — under exclusivity/drive-by rejection the closest
    // pair may fail to route, so try candidate pairs in ascending distance
    // until one succeeds instead of adding the single best pair blindly.
    const inTree = new Set([cluster[0].id]);

    while (inTree.size < cluster.length) {
      const cands = [];
      for (const from of cluster) {
        if (!inTree.has(from.id)) continue;
        for (const to of cluster) {
          if (inTree.has(to.id)) continue;
          cands.push({ from, to, d: haversine([from.lng, from.lat], [to.lng, to.lat]) });
        }
      }
      if (!cands.length) break;
      cands.sort((a, b) => a.d - b.d || a.from.id - b.from.id || a.to.id - b.to.id);
      let added = false;
      for (const c of cands) {
        if (addEscooterEdge(c.from, c.to)) { inTree.add(c.to.id); added = true; break; }
        __mcStats.mstRetries++;
      }
      if (!added) break; // no routable pair left — remaining nodes stay out of tree
    }

    // Each node also connects to its 3 nearest cluster neighbours for richer topology
    for (const from of cluster) {
      const sorted = cluster
        .filter(n => n.id !== from.id)
        .sort((a, b) => haversine([from.lng, from.lat], [a.lng, a.lat])
                      - haversine([from.lng, from.lat], [b.lng, b.lat]));
      for (const to of sorted.slice(0, MAPGEN().escooterExtraNN ?? 3)) addEscooterEdge(from, to);
    }

    // Enforce minimum degree of 2: keep trying further neighbours, then drop
    // nodes that still can't reach 2 routable connections.
    {
      const deg = new Map(cluster.map(n => [n.id, 0]));
      for (const e of edges) {
        if (!e.modes.includes('ESCOOTER')) continue;
        if (deg.has(e.from)) deg.set(e.from, deg.get(e.from) + 1);
        if (deg.has(e.to))   deg.set(e.to,   deg.get(e.to)   + 1);
      }
      const byDist = new Map(cluster.map(n => [n.id,
        cluster.filter(o => o.id !== n.id)
               .sort((a, b) => haversine([n.lng, n.lat], [a.lng, a.lat])
                             - haversine([n.lng, n.lat], [b.lng, b.lat]))]));
      for (const n of cluster) {
        for (const cand of byDist.get(n.id)) {
          if (deg.get(n.id) >= 2) break;
          // Skip already-connected pairs: addEscooterEdge now reports those as
          // success, which would double-count degrees already tallied above.
          if (edges.some(e => (e.from===n.id&&e.to===cand.id)||(e.from===cand.id&&e.to===n.id))) continue;
          if (addEscooterEdge(n, cand)) {
            deg.set(n.id,    deg.get(n.id)    + 1);
            deg.set(cand.id, (deg.get(cand.id) ?? 0) + 1);
          }
        }
      }
      // Never orphan-drop a node carrying a TRAIN edge — deleting it would
      // silently sever the rail line (stations merged into clusters rarely
      // win 2 same-mode edges; the stitch/repair passes handle their degree).
      const trainTouched = new Set(edges.filter(e => e.modes.includes('TRAIN')).flatMap(e => [e.from, e.to]));
      const orphans = new Set(cluster.filter(n => deg.get(n.id) < 2 && !trainTouched.has(n.id)).map(n => n.id));
      if (orphans.size) {
        edges = edges.filter(e => !orphans.has(e.from) && !orphans.has(e.to));
        nodes = nodes.filter(n => !orphans.has(n.id));
      }
    }

    refreshSources(); updateCounts();
    setStatus(`Escooter: ${cl.name} done (${cluster.length} nodes)…`);
    await yld();
  }

  // Stitch train stations into the local mesh. A station with no BUS/ESCOOTER
  // edge is uncontestable by detectives and leaves the rail spine as its own
  // component; connect each such station to its nearest mesh nodes by road.
  const stitchMaxM = MAPGEN().stitchStationsMaxM ?? 1500;
  const stitchDeg  = MAPGEN().stitchStationsDegree ?? 2;
  if (stitchMaxM > 0) {
    const trainIds = new Set(), meshIds = new Set();
    for (const e of edges) {
      if (e.modes.includes('TRAIN')) { trainIds.add(e.from); trainIds.add(e.to); }
      if (e.modes.includes('BUS') || e.modes.includes('ESCOOTER')) { meshIds.add(e.from); meshIds.add(e.to); }
    }
    const byId = new Map(nodes.map(n => [n.id, n]));
    let stitched = 0;
    for (const tid of trainIds) {
      if (meshIds.has(tid)) continue;
      const t = byId.get(tid);
      if (!t) continue;
      const cands = nodes
        .filter(n => meshIds.has(n.id))
        .map(n => ({ n, d: haversine([t.lng, t.lat], [n.lng, n.lat]) }))
        .filter(c => c.d <= stitchMaxM)
        .sort((a, b) => a.d - b.d);
      let added = 0;
      for (const { n } of cands) {
        if (added >= stitchDeg) break;
        if (addEscooterEdge(t, n)) added++;
      }
      if (added) { meshIds.add(tid); stitched++; }
      else (__mcStats.stitchFails ??= []).push({ station: tid, cands: cands.length });
    }
    setStatus(`Stitched ${stitched} train stations into the mesh…`);
    await yld();
  }

  // Bridge any remaining disconnected components into the largest one, so the
  // final graph is always a single board. Closest road-routable pair wins.
  const bridgeMaxM = MAPGEN().bridgeComponentsMaxM ?? 3000;
  if (bridgeMaxM > 0) {
    for (let guard = 0; guard < 10; guard++) {
      const adj = new Map();
      for (const e of edges) {
        if (!adj.has(e.from)) adj.set(e.from, []);
        if (!adj.has(e.to))   adj.set(e.to, []);
        adj.get(e.from).push(e.to);
        adj.get(e.to).push(e.from);
      }
      const seen = new Set(), comps = [];
      for (const n of nodes) {
        if (seen.has(n.id) || !adj.has(n.id)) continue;
        const comp = [], stack = [n.id];
        seen.add(n.id);
        while (stack.length) {
          const id = stack.pop();
          comp.push(id);
          for (const nb of adj.get(id) ?? []) {
            if (!seen.has(nb)) { seen.add(nb); stack.push(nb); }
          }
        }
        comps.push(comp);
      }
      if (comps.length <= 1) break;
      comps.sort((a, b) => b.length - a.length);
      const main = new Set(comps[0]);
      const byId = new Map(nodes.map(n => [n.id, n]));
      const cands = [];
      for (const comp of comps.slice(1)) {
        for (const aid of comp) {
          const a = byId.get(aid);
          for (const bid of main) {
            const b = byId.get(bid);
            const d = haversine([a.lng, a.lat], [b.lng, b.lat]);
            if (d <= bridgeMaxM) cands.push({ a, b, d });
          }
        }
      }
      cands.sort((x, y) => x.d - y.d || x.a.id - y.a.id || x.b.id - y.b.id);
      let bridged = false;
      for (const c of cands) {
        if (addEscooterEdge(c.a, c.b)) { bridged = true; break; }
      }
      if (!bridged) {
        (__mcStats.bridgeFails ??= []).push({ comps: comps.map(c => c.length), cands: cands.length });
        // Unbridgeable stray components without stations are expendable —
        // dropping them beats shipping a multi-component board.
        const stationIds2 = new Set(edges.filter(e => e.modes.includes('TRAIN')).flatMap(e => [e.from, e.to]));
        const strays = new Set(comps.slice(1).filter(c => !c.some(id => stationIds2.has(id))).flat());
        if (strays.size) {
          __mcStats.strayDropped = (__mcStats.strayDropped ?? 0) + strays.size;
          edges = edges.filter(e => !strays.has(e.from) && !strays.has(e.to));
          nodes = nodes.filter(n => !strays.has(n.id));
          rebuildUsedSegs();
        }
        break; // nothing routable within range — give up rather than loop
      }
      setStatus('Bridged a disconnected component into the main graph…');
      await yld();
    }
  }

  // Final degree repair + leaf prune. Stations can end up degree-1 (line
  // terminus with a failed stitch) and mesh nodes can end up stranded when
  // every candidate edge was rejected by the road rules. Repair by linking
  // low-degree nodes to their nearest connected neighbours, then iteratively
  // drop non-station leaves that still can't reach degree 2 (safe for
  // connectivity: a leaf's removal never disconnects anyone else).
  {
    const degOf = () => {
      const d = new Map();
      for (const e of edges) {
        d.set(e.from, (d.get(e.from) ?? 0) + 1);
        d.set(e.to,   (d.get(e.to)   ?? 0) + 1);
      }
      return d;
    };
    const repairMaxM = MAPGEN().repairMaxM ?? 2500;
    let deg = degOf();
    for (const n of [...nodes]) {
      if ((deg.get(n.id) ?? 0) >= 2) continue;
      const cands = nodes
        .filter(o => o.id !== n.id && (deg.get(o.id) ?? 0) >= 2)
        .map(o => ({ o, d: haversine([n.lng, n.lat], [o.lng, o.lat]) }))
        .filter(c => c.d <= repairMaxM)
        .sort((a, b) => a.d - b.d || a.o.id - b.o.id);
      for (const { o } of cands) {
        if ((deg.get(n.id) ?? 0) >= 2) break;
        if (addEscooterEdge(n, o)) deg = degOf();
      }
    }
    const stationSet = new Set(edges.filter(e => e.modes.includes('TRAIN')).flatMap(e => [e.from, e.to]));
    let pruned = 0;
    for (let pass = 0; pass < 5; pass++) {
      deg = degOf();
      const drop = new Set(nodes.filter(n => (deg.get(n.id) ?? 0) < 2 && !stationSet.has(n.id)).map(n => n.id));
      if (!drop.size) break;
      pruned += drop.size;
      edges = edges.filter(e => !drop.has(e.from) && !drop.has(e.to));
      nodes = nodes.filter(n => !drop.has(n.id));
    }
    __mcStats.finalPruned = pruned;
    rebuildUsedSegs();
  }

  refreshSources();
  updateCounts();
  document.getElementById('btn-save-map').disabled = false;
  const eEdges = edges.filter(e => e.modes.includes('ESCOOTER'));
  const eNodes = new Set(eEdges.flatMap(e => [e.from, e.to]));
  setStatus(`Escooter done — ${eNodes.size} nodes, ${eEdges.length} edges (${busPromoted} bus edges promoted)`);
  btn.disabled = false;
}

// ── Core: traverse route line arrays and place nodes ─────

async function placeAlongRoutes(lines, mode, stepM, jitter = 0) {
  let newNodes = 0, newEdges = 0;

  for (let li = 0; li < lines.length; li++) {
    const segs = splitLineAtInterval(lines[li], stepM, jitter);

    for (const seg of segs) {
      const fromNode = upsertNode(seg.from);
      const toNode   = upsertNode(seg.to);
      if (fromNode.id === toNode.id) continue;
      if (fromNode._new) { newNodes++; fromNode._new = false; }
      if (toNode._new)   { newNodes++; toNode._new   = false; }

      const ex = edges.find(e =>
        (e.from === fromNode.id && e.to === toNode.id) ||
        (e.from === toNode.id   && e.to === fromNode.id)
      );
      if (ex) {
        if (!ex.modes.includes(mode)) ex.modes.push(mode);
      } else {
        edges.push({ id: nextEdgeId++, from: fromNode.id, to: toNode.id,
                     modes: [mode], coordinates: seg.coords });
        newEdges++;
      }
    }

    if (li % 10 === 0) {
      updateCounts(); refreshSources();
      setStatus(`${mode}: ${li + 1}/${lines.length} segments — ${newNodes} nodes, ${newEdges} edges`);
      await yld();
    }
  }

  // Back-fill road-graph snap for new route nodes so escooter step can use Dijkstra
  for (const n of nodes) {
    if (n.segAKey == null) {
      const snap = snapToRoad(n.lng, n.lat);
      if (snap) { n.segAKey = snap.segAKey; n.segBKey = snap.segBKey; }
    }
  }

  refreshSources();
  updateCounts();
  document.getElementById('btn-save-map').disabled = false;
  setStatus(`${mode} done — ${nodes.length} nodes, ${edges.length} edges`);
}

// Split a [[lng,lat],...] polyline into segments of approximately stepM metres.
// jitter ∈ [0,1]: fraction by which each threshold is randomly varied (0 = uniform).
// Returns [{from, to, coords}] where coords are all intermediate points.
function splitLineAtInterval(coords, stepM, jitter = 0) {
  if (coords.length < 2) return [];
  const segs = [];
  let segCoords   = [coords[0]];
  let accumulated = 0;
  const nextThreshold = () =>
    jitter > 0 ? stepM * (1 - jitter + Math.random() * jitter * 2) : stepM;
  let threshold = nextThreshold();

  for (let i = 1; i < coords.length; i++) {
    const a   = coords[i - 1];
    const b   = coords[i];
    const len = haversine(a, b);
    if (len < 0.01) continue;

    let cursor = a;
    let rem    = len;

    while (accumulated + rem >= threshold) {
      const need = threshold - accumulated;
      const t    = need / rem;
      const pt   = [cursor[0] + t * (b[0] - cursor[0]), cursor[1] + t * (b[1] - cursor[1])];

      segCoords.push(pt);
      segs.push({ from: segCoords[0], to: pt, coords: [...segCoords] });
      segCoords   = [pt];
      cursor      = pt;
      rem        -= need;
      accumulated = 0;
      threshold   = nextThreshold();
    }

    accumulated += rem;
    segCoords.push(b);
  }

  // Trailing segment to end of line
  if (segs.length > 0 && segCoords.length >= 2 &&
      haversine(segCoords[0], segCoords[segCoords.length - 1]) > 30) {
    segs.push({ from: segCoords[0], to: segCoords[segCoords.length - 1], coords: [...segCoords] });
  }

  return segs;
}

// BFS over train-pass nodes; connects each disconnected component to the
// nearest node in the merged (main) component. Returns number of bridges added.
function connectTrainComponents(nodeIdBefore) {
  const nodeMap  = new Map(nodes.map(n => [n.id, n]));
  const trainIds = new Set(nodes.filter(n => n.id >= nodeIdBefore).map(n => n.id));
  if (trainIds.size === 0) return 0;

  // Adjacency restricted to train nodes and train edges
  const adj = new Map([...trainIds].map(id => [id, []]));
  for (const e of edges) {
    if (!e.modes.includes('TRAIN')) continue;
    if (adj.has(e.from) && adj.has(e.to)) {
      adj.get(e.from).push(e.to);
      adj.get(e.to).push(e.from);
    }
  }

  // Find connected components via BFS
  const visited    = new Set();
  const components = [];
  for (const id of trainIds) {
    if (visited.has(id)) continue;
    const comp = [];
    const q    = [id];
    visited.add(id);
    while (q.length) {
      const cur = q.shift();
      comp.push(cur);
      for (const nb of (adj.get(cur) ?? [])) {
        if (!visited.has(nb)) { visited.add(nb); q.push(nb); }
      }
    }
    components.push(comp);
  }

  if (components.length <= 1) return 0;

  // Greedily bridge each smaller component to the growing merged set.
  // Components within MAX_BRIDGE_M are bridged; farther ones are dropped as OSM strays.
  // No exception for "large" components — the topological sort in genTrain should have
  // already ensured real lines are connected by shared endpoints, not long bridges.
  components.sort((a, b) => b.length - a.length);
  const merged = new Set(components[0]);
  let bridges  = 0;

  for (let ci = 1; ci < components.length; ci++) {
    let bestD = Infinity, bestFrom = null, bestTo = null;
    for (const fromId of components[ci]) {
      const fn = nodeMap.get(fromId);
      for (const toId of merged) {
        const tn = nodeMap.get(toId);
        const d  = haversine([fn.lng, fn.lat], [tn.lng, tn.lat]);
        if (d < bestD) { bestD = d; bestFrom = fromId; bestTo = toId; }
      }
    }

    if (bestFrom !== null && bestD <= MAX_BRIDGE_M) {
      const fn = nodeMap.get(bestFrom);
      const tn = nodeMap.get(bestTo);
      edges.push({ id: nextEdgeId++, from: bestFrom, to: bestTo,
                   modes: ['TRAIN'], coordinates: [[fn.lng, fn.lat], [tn.lng, tn.lat]] });
      for (const id of components[ci]) merged.add(id);
      bridges++;
    } else {
      // Fragment too far from the main network — drop it
      const drop = new Set(components[ci]);
      nodes = nodes.filter(n => !drop.has(n.id));
      edges = edges.filter(e => !drop.has(e.from) && !drop.has(e.to));
      for (const id of drop) nodeMap.delete(id);
    }
  }

  return bridges;
}

// Return an existing node within MERGE_RADIUS_M, or create a new snapped one.
function upsertNode(coord) {
  const [lng, lat] = coord;
  for (const n of nodes) {
    if (haversine([n.lng, n.lat], [lng, lat]) <= MERGE_RADIUS_M) return n;
  }
  const snap = snapToRoad(lng, lat);
  const id   = nextNodeId++;
  const node = {
    id,
    lng: snap ? snap.lng : lng,
    lat: snap ? snap.lat : lat,
    label: String(id),
    segAKey: snap?.segAKey ?? null,
    segBKey: snap?.segBKey ?? null,
    _new: true,
  };
  nodes.push(node);
  return node;
}

const yld = () => new Promise(r => setTimeout(r, 0));

// ══════════════════════════════════════════════════════════
//  UI HELPERS
// ══════════════════════════════════════════════════════════

function setStatus(msg) {
  document.getElementById('status').textContent = msg;
}

function updateCounts() {
  document.getElementById('node-count').textContent = nodes.length;
  document.getElementById('edge-count').textContent = edges.length;
}

// ══════════════════════════════════════════════════════════
//  TOOLBAR WIRING
// ══════════════════════════════════════════════════════════

document.getElementById('btn-load-map').addEventListener('click', () => document.getElementById('file-map').click());
document.getElementById('btn-save-map').addEventListener('click', saveMap);
document.getElementById('btn-undo').addEventListener('click', undo);
document.getElementById('btn-auto-gen').addEventListener('click', autoGenerate);
document.getElementById('btn-show-components').addEventListener('click', toggleComponents);
document.getElementById('btn-renumber').addEventListener('click', renumberNodesReadingOrder);

document.getElementById('btn-search-node').addEventListener('click', () => {
  searchNode(document.getElementById('search-node').value);
});
document.getElementById('search-node').addEventListener('keydown', e => {
  if (e.key === 'Enter') { e.preventDefault(); searchNode(e.target.value); }
});
document.addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) { e.preventDefault(); undo(); }
});
document.getElementById('btn-gen-train').addEventListener('click', genTrain);
document.getElementById('btn-gen-bus').addEventListener('click', genBus);
document.getElementById('btn-gen-escooter').addEventListener('click', genEscooter);

document.getElementById('file-map').addEventListener('change', e => {
  if (e.target.files[0]) loadMapFile(e.target.files[0]);
  e.target.value = '';
});

document.querySelectorAll('.mode-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const m = btn.dataset.mode;
    if (activeModes.has(m)) {
      if (activeModes.size > 1) activeModes.delete(m);
    } else {
      activeModes.add(m);
    }
    document.querySelectorAll('.mode-btn').forEach(b =>
      b.classList.toggle('active', activeModes.has(b.dataset.mode))
    );
  });
});

// ══════════════════════════════════════════════════════════
//  BOOT
// ══════════════════════════════════════════════════════════

initMap();
