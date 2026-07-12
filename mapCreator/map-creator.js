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

function findRoadPath(fromNode, toNode, maxDist = Infinity) {
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
    for (const { key: v, dist: w } of (graphAdj.get(u) || [])) {
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
function sampleVertices(center, radiusM, count) {
  const cands = [];
  for (const key of graphAdj.keys()) {
    const coord = coordFromKey(key);
    if (haversine(coord, center) <= radiusM) cands.push({ key, coord });
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

  // Straight line only when exactly one end is a train node (train↔road mix).
  // Train↔train uses road geometry; road↔road always uses road geometry.
  const mixedTrainRoad = (from.offRoad && !to.offRoad) || (!from.offRoad && to.offRoad);
  if (!roadData || !graphAdj.size || mixedTrainRoad) {
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
        },
        geometry: { type: 'Point', coordinates: [n.lng, n.lat] },
      };
    }),
  };
}

function edgesGJ() {
  const SPACING = 4.5;
  const features = [];

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
      features.push({
        type: 'Feature',
        properties: { id: e.id, mode, lineOffset: n === 1 ? 0 : (i - (n - 1) / 2) * SPACING },
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
        'line-opacity': 1,
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
      paint: { 'text-color': '#ffffff' },
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
      renameNode(nodeHit[0].properties.id);
      return;
    }

    selectEdge(null);

    const snap = snapToRoad(e.lngLat.lng, e.lngLat.lat);
    if (!snap) { setStatus('No road found nearby — zoom in and click closer to a road'); return; }

    addNode(snap);
    setStatus(`Node ${nextNodeId - 1} added`);
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
  Object.assign(document.createElement('a'), { href: url, download: 'scotland-yard-map.json' }).click();
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
const MERGE_RADIUS_M = 150;   // nodes closer than this are merged, not duplicated
const ESCOOTER_MAX_M = 400;   // max straight-line distance for an escooter edge
const MAX_BRIDGE_M   = 500;   // max gap to bridge between disconnected train components

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

  function tryAddTrainEdge(from, to) {
    if (from.id === to.id) return;
    if (edges.some(e => (e.from===from.id&&e.to===to.id)||(e.from===to.id&&e.to===from.id))) return;
    if (!from.segAKey || !to.segAKey) return;
    const dist = haversine([from.lng, from.lat], [to.lng, to.lat]);
    const coords = findRoadPath(from, to, dist * 3);
    if (coords.length <= 2) return; // no road path found, skip beeline
    edges.push({ id: nextEdgeId++, from: from.id, to: to.id, modes: ['TRAIN'], coordinates: coords });
  }

  // ── place one node per station, connect consecutive stops per line ──────────

  for (let li = 0; li < WELLINGTON_TRAIN_LINES.length; li++) {
    const line = WELLINGTON_TRAIN_LINES[li];
    let prev = null;
    for (const coord of line) {
      // Merge stations shared across lines (e.g. Wellington Station, Petone)
      const node = nearestTrain(coord, MERGE_RADIUS_M) ?? addTrainNode(coord[0], coord[1]);
      if (prev) tryAddTrainEdge(prev, node);
      prev = node;
    }
    setStatus(`Train: line ${li + 1}/${WELLINGTON_TRAIN_LINES.length}…`);
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

  // Four geographic clusters.  Nodes are sampled from road-graph vertices
  // inside each cluster radius, then connected via Dijkstra road routing.
  const CLUSTERS = [
    { name: 'Wellington',   center: [174.776, -41.286], radiusM: 2800, count: 20 },
    { name: 'Lower Hutt',   center: [174.908, -41.213], radiusM: 3200, count: 20 },
    { name: 'Johnsonville', center: [174.804, -41.228], radiusM: 2000, count: 15 },
    { name: 'Porirua',      center: [174.843, -41.137], radiusM: 2400, count: 15 },
  ];

  // ── helpers ────────────────────────────────────────────────────────────────

  function makeNode(key) {
    const [lng, lat] = coordFromKey(key);
    // Node is placed exactly at a road-graph vertex: segAKey = segBKey = the vertex key.
    // findRoadPath initialises Dijkstra at distance 0 from this vertex, so routing works perfectly.
    const n = { id: nextNodeId++, lng, lat, label: String(nextNodeId - 1),
                segAKey: key, segBKey: key };
    nodes.push(n);
    return n;
  }

  function addBusEdge(from, to) {
    if (from.id === to.id) return false;
    if (edges.some(e => (e.from===from.id&&e.to===to.id)||(e.from===to.id&&e.to===from.id))) return false;
    const d = haversine([from.lng, from.lat], [to.lng, to.lat]);
    const coords = findRoadPath(from, to, d * 5);
    if (coords.length <= 2) return false; // no road path found, skip beeline
    edges.push({ id: nextEdgeId++, from: from.id, to: to.id, modes: ['BUS'], coordinates: coords });
    return true;
  }

  // ── build each cluster ─────────────────────────────────────────────────────

  for (const cl of CLUSTERS) {
    setStatus(`Bus: sampling ${cl.name}…`);
    await yld();

    const verts   = sampleVertices(cl.center, cl.radiusM, cl.count);
    const cluster = verts.map(v => makeNode(v.key));

    if (cluster.length < 2) continue;

    // Prim's MST — guarantees full connectivity
    const inTree = new Set([cluster[0].id]);
    const nodeById = new Map(cluster.map(n => [n.id, n]));

    while (inTree.size < cluster.length) {
      let bestDist = Infinity, bestFrom = null, bestTo = null;
      for (const fromId of inTree) {
        const from = nodeById.get(fromId);
        for (const to of cluster) {
          if (inTree.has(to.id)) continue;
          const d = haversine([from.lng, from.lat], [to.lng, to.lat]);
          if (d < bestDist) { bestDist = d; bestFrom = from; bestTo = to; }
        }
      }
      if (!bestFrom) break;
      addBusEdge(bestFrom, bestTo);
      inTree.add(bestTo.id);
    }

    // Extra connections: each node also links to its 2 nearest cluster neighbours
    // (beyond the MST edges) so the network has cycles and looks richer.
    for (const from of cluster) {
      const sorted = cluster
        .filter(n => n.id !== from.id)
        .sort((a, b) => haversine([from.lng, from.lat], [a.lng, a.lat])
                      - haversine([from.lng, from.lat], [b.lng, b.lat]));
      for (const to of sorted.slice(0, 2)) addBusEdge(from, to);
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
          if (addBusEdge(n, cand)) {
            deg.set(n.id,    deg.get(n.id)    + 1);
            deg.set(cand.id, (deg.get(cand.id) ?? 0) + 1);
          }
        }
      }
      const orphans = new Set(cluster.filter(n => deg.get(n.id) < 2).map(n => n.id));
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

  // Most bus edges are also valid escooter routes — add the mode directly.
  let busPromoted = 0;
  for (const e of edges) {
    if (e.modes.includes('BUS') && !e.modes.includes('ESCOOTER')) {
      e.modes = [...e.modes, 'ESCOOTER'];
      busPromoted++;
    }
  }

  // 90 dedicated escooter nodes across the same 4 clusters as bus, but with
  // higher node density (shorter inter-node spacing) due to more nodes per cluster.
  const CLUSTERS = [
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
    const n = { id: nextNodeId++, lng, lat, label: String(nextNodeId - 1),
                segAKey: key, segBKey: key };
    nodes.push(n);
    return n;
  }

  function addEscooterEdge(from, to) {
    if (from.id === to.id) return false;
    if (edges.some(e => (e.from===from.id&&e.to===to.id)||(e.from===to.id&&e.to===from.id))) return false;
    const d = haversine([from.lng, from.lat], [to.lng, to.lat]);
    const coords = findRoadPath(from, to, d * 5);
    if (coords.length <= 2) return false; // no road path found, skip beeline
    edges.push({ id: nextEdgeId++, from: from.id, to: to.id, modes: ['ESCOOTER'], coordinates: coords });
    return true;
  }

  for (const cl of CLUSTERS) {
    setStatus(`Escooter: sampling ${cl.name}…`);
    await yld();

    const verts   = sampleVertices(cl.center, cl.radiusM, cl.count);
    const cluster = verts.map(v => getOrMakeNode(v.key));

    if (cluster.length < 2) continue;

    // Prim's MST — guarantees full connectivity within the cluster
    const inTree = new Set([cluster[0].id]);
    const nodeById = new Map(cluster.map(n => [n.id, n]));

    while (inTree.size < cluster.length) {
      let bestDist = Infinity, bestFrom = null, bestTo = null;
      for (const fromId of inTree) {
        const from = nodeById.get(fromId);
        for (const to of cluster) {
          if (inTree.has(to.id)) continue;
          const d = haversine([from.lng, from.lat], [to.lng, to.lat]);
          if (d < bestDist) { bestDist = d; bestFrom = from; bestTo = to; }
        }
      }
      if (!bestFrom) break;
      addEscooterEdge(bestFrom, bestTo);
      inTree.add(bestTo.id);
    }

    // Each node also connects to its 3 nearest cluster neighbours for richer topology
    for (const from of cluster) {
      const sorted = cluster
        .filter(n => n.id !== from.id)
        .sort((a, b) => haversine([from.lng, from.lat], [a.lng, a.lat])
                      - haversine([from.lng, from.lat], [b.lng, b.lat]));
      for (const to of sorted.slice(0, 3)) addEscooterEdge(from, to);
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
          if (addEscooterEdge(n, cand)) {
            deg.set(n.id,    deg.get(n.id)    + 1);
            deg.set(cand.id, (deg.get(cand.id) ?? 0) + 1);
          }
        }
      }
      const orphans = new Set(cluster.filter(n => deg.get(n.id) < 2).map(n => n.id));
      if (orphans.size) {
        edges = edges.filter(e => !orphans.has(e.from) && !orphans.has(e.to));
        nodes = nodes.filter(n => !orphans.has(n.id));
      }
    }

    refreshSources(); updateCounts();
    setStatus(`Escooter: ${cl.name} done (${cluster.length} nodes)…`);
    await yld();
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
