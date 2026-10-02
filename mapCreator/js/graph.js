'use strict';

// The map being built: nodes, edges, undo and edits.

// {id, lng, lat, label, segAKey, segBKey, offRoad?}
let nodes      = [];
// {id, from, to, modes, coordinates}
let edges      = [];
let nextNodeId = 1;
let nextEdgeId = 1;

// Modes given to new edges
let activeModes    = new Set(['ESCOOTER', 'BUS']);
let selectedEdgeId = null;
// Clicked node; everything except it and its neighbours is dimmed
let exploreNodeId  = null;

function pushNode(fields) {
  const id = nextNodeId++;
  const node = { id, label: String(id), ...fields };
  nodes.push(node);
  return node;
}

function pushEdge(from, to, modes, coordinates) {
  const edge = { id: nextEdgeId++, from, to, modes, coordinates };
  edges.push(edge);
  return edge;
}

const findEdge = (a, b) => edges.find(e => (e.from === a && e.to === b) || (e.from === b && e.to === a));

// nodeId → edge count, optionally only edges carrying `mode`
function degreeMap(mode) {
  const d = new Map();
  for (const e of edges) {
    if (mode && !e.modes.includes(mode)) continue;
    d.set(e.from, (d.get(e.from) ?? 0) + 1);
    d.set(e.to,   (d.get(e.to)   ?? 0) + 1);
  }
  return d;
}

const trainNodeIds = () => new Set(edges.filter(e => e.modes.includes('TRAIN')).flatMap(e => [e.from, e.to]));

function neighbourIdSet(id) {
  const s = new Set();
  for (const e of edges) {
    if (e.from === id) s.add(e.to);
    if (e.to === id)   s.add(e.from);
  }
  return s;
}

// compOf: nodeId → component index; comps: [[nodeId, ...], ...]
function computeComponents(nodeList = nodes) {
  const adj = new Map();
  for (const n of nodeList) adj.set(n.id, []);
  for (const e of edges) {
    adj.get(e.from)?.push(e.to);
    adj.get(e.to)?.push(e.from);
  }

  const compOf = new Map();
  const comps  = [];
  for (const n of nodeList) {
    if (compOf.has(n.id)) continue;
    const comp = [], stack = [n.id];
    compOf.set(n.id, comps.length);
    while (stack.length) {
      const cur = stack.pop();
      comp.push(cur);
      for (const nb of adj.get(cur) ?? []) {
        if (!compOf.has(nb)) { compOf.set(nb, comps.length); stack.push(nb); }
      }
    }
    comps.push(comp);
  }
  return { compOf, comps };
}

// ── Undo ──────────────────────────────────────────────────

const undoStack = [];
const UNDO_MAX  = 20;

function saveUndo() {
  undoStack.push({ nodes: structuredClone(nodes), edges: structuredClone(edges), nextNodeId, nextEdgeId });
  if (undoStack.length > UNDO_MAX) undoStack.shift();
  document.getElementById('btn-undo').disabled = false;
}

function undo() {
  if (!undoStack.length) return;
  ({ nodes, edges, nextNodeId, nextEdgeId } = undoStack.pop());
  if (selectedEdgeId !== null) selectEdge(null);
  refreshSources();
  if (!undoStack.length) document.getElementById('btn-undo').disabled = true;
  setStatus(`Undo — ${nodes.length} nodes, ${edges.length} edges`);
}

// ── Manual edits ──────────────────────────────────────────

function addNode(snap) {
  saveUndo();
  const node = pushNode({ lng: snap.lng, lat: snap.lat, segAKey: snap.segAKey, segBKey: snap.segBKey });
  refreshSources();
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
}

// Finds a node by id or label, flies there and highlights its neighbours.
function searchNode(query) {
  const q = query.trim();
  if (!q) { setStatus('Enter a node id or name to search'); return; }

  let target = /^\d+$/.test(q) ? nodes.find(n => n.id === Number(q)) : null;
  if (!target) target = nodes.find(n => n.label.toLowerCase() === q.toLowerCase());
  if (!target) { setStatus(`Node "${q}" doesn't exist`); return; }

  exploreNodeId = target.id;
  refreshSources();
  map.flyTo({ center: [target.lng, target.lat], zoom: Math.max(map.getZoom(), 16) });
  const named = target.label !== String(target.id) ? ` (${target.label})` : '';
  setStatus(`Found node ${target.id}${named} — showing its neighbours`);
}

// Relabels nodes 1..N top-left to bottom-right, in ~440 m rows. Ids are unchanged.
function renumberNodesReadingOrder() {
  if (!nodes.length) { setStatus('No nodes to renumber'); return; }
  if (!confirm(`Relabel all ${nodes.length} nodes 1–${nodes.length}, top-left to bottom-right? This overwrites existing labels.`)) return;

  saveUndo();
  const ROW_HEIGHT_DEG = 0.004;
  const maxLat = Math.max(...nodes.map(n => n.lat));
  const rowOf = n => Math.floor((maxLat - n.lat) / ROW_HEIGHT_DEG);

  [...nodes].sort((a, b) => rowOf(a) - rowOf(b) || a.lng - b.lng)
    .forEach((n, i) => { n.label = String(i + 1); });

  refreshSources();
  setStatus(`Renumbered ${nodes.length} node labels, top-left → bottom-right`);
}

function addEdge(fromId, toId) {
  const from = nodes.find(n => n.id === fromId);
  const to   = nodes.find(n => n.id === toId);
  if (!from || !to || fromId === toId) return;
  if (findEdge(fromId, toId)) { setStatus('Edge already exists between these nodes'); return; }

  saveUndo();

  // Straight line for ferries and for road-mode edges between a station and a
  // road node; everything else follows the roads.
  const nodeHasTrain = id => edges.some(e => (e.from === id || e.to === id) && e.modes.includes('TRAIN'));
  const fromTrain = nodeHasTrain(fromId), toTrain = nodeHasTrain(toId);
  const mixedTrainRoad = fromTrain === toTrain
    ? !fromTrain && !!from.offRoad !== !!to.offRoad
    : !activeModes.has('TRAIN');

  const straight = [[from.lng, from.lat], [to.lng, to.lat]];
  let coordinates = straight;
  if (graphAdj.size && !activeModes.has('FERRY') && !mixedTrainRoad) {
    const fromSnap = snapToRoad(from.lng, from.lat);
    const toSnap   = snapToRoad(to.lng,   to.lat);
    if (fromSnap && toSnap) {
      const pts = findRoadPath(fromSnap, toSnap);
      if (haversine(straight[0], [fromSnap.lng, fromSnap.lat]) > 5) pts.unshift(straight[0]);
      if (haversine(straight[1], [toSnap.lng, toSnap.lat]) > 5)     pts.push(straight[1]);
      coordinates = pts.filter((p, i) => i === 0 || p[0] !== pts[i - 1][0] || p[1] !== pts[i - 1][1]);
    }
  }

  pushEdge(fromId, toId, [...activeModes], coordinates);
  refreshSources();
  setStatus('Ready');
}

function removeEdge(id) {
  saveUndo();
  edges = edges.filter(e => e.id !== id);
  refreshSources();
}

// Removes `mode` from every edge, then drops edges and nodes left empty.
function clearModeEdges(mode) {
  edges = edges
    .map(e => ({ ...e, modes: e.modes.filter(m => m !== mode) }))
    .filter(e => e.modes.length > 0);
  const connected = new Set(edges.flatMap(e => [e.from, e.to]));
  nodes = nodes.filter(n => connected.has(n.id));
  refreshSources();
}

// ── Drive-by rule ─────────────────────────────────────────

// Other nodes within corridorM of the polyline, in order along it.
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

// True when [lng, lat] is within corridorM of any road-mode edge.
function nearRoadModeEdge(lng, lat, corridorM) {
  return edges.some(e => isRoadMode(e) && e.coordinates.some((c, i) =>
    i > 0 && ptSegDistM([lng, lat], e.coordinates[i - 1], c).d < corridorM));
}

// ── Save / load ───────────────────────────────────────────

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
      for (const e of data.edges ?? []) pushEdge(e.from, e.to, e.modes, e.coordinates ?? []);

      refreshSources();
      setStatus(`Loaded — ${nodes.length} nodes, ${edges.length} edges`);
      if (nodes.length) fitTo(nodes.map(n => [n.lng, n.lat]), { padding: 80, maxZoom: 15 });
    } catch (err) {
      setStatus('Error loading map: ' + err.message);
    }
  };
  reader.readAsText(file);
}
