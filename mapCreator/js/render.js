'use strict';

// MapLibre map, layers and GeoJSON.

const MODE_COLORS = {
  ESCOOTER: '#22c55e',
  BUS:      '#ef4444',
  TRAIN:    '#8b5cf6',
  FERRY:    '#06b6d4',
};
const MODE_ORDER = ['ESCOOTER', 'BUS', 'TRAIN', 'FERRY'];

// Wellington / Lower Hutt midpoint
const CENTER = [174.85, -41.21];

const COMPONENT_PALETTE = [
  '#ef4444', '#3b82f6', '#22c55e', '#eab308', '#a855f7',
  '#ec4899', '#06b6d4', '#f97316', '#84cc16', '#6366f1',
];

// Opacity when a clicked node dims the rest
const dimmedOr = (dim, lit) => ['case', ['boolean', ['get', 'dimmed'], false], dim, lit];

let map = null;
let componentsMode = false;

const featureCollection = features => ({ type: 'FeatureCollection', features });
const lineFeature = (coordinates, properties = {}) =>
  ({ type: 'Feature', properties, geometry: { type: 'LineString', coordinates } });
const pointFeature = (n, properties) =>
  ({ type: 'Feature', properties, geometry: { type: 'Point', coordinates: [n.lng, n.lat] } });

// Icon name suffix for a set of modes, e.g. "EB" or "none"
function modesKey(modes) {
  return MODE_ORDER.filter(m => modes.has(m)).map(m => m[0]).join('') || 'none';
}

// Pie-chart node icon, one slice per mode
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
    ctx.fillStyle = MODE_COLORS[mode];
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

// The clicked node may since have been deleted
const exploring = () => exploreNodeId != null && nodes.some(n => n.id === exploreNodeId);

function nodesGJ() {
  const nodeModes = new Map();
  for (const e of edges) {
    for (const id of [e.from, e.to]) {
      if (!nodeModes.has(id)) nodeModes.set(id, new Set());
      for (const m of e.modes) nodeModes.get(id).add(m);
    }
  }

  const nbrs = exploring() ? neighbourIdSet(exploreNodeId) : null;
  return featureCollection(nodes.map(n => pointFeature(n, {
    id: n.id,
    label: n.label,
    modesKey: modesKey(nodeModes.get(n.id) ?? new Set()),
    dimmed: !!nbrs && n.id !== exploreNodeId && !nbrs.has(n.id),
  })));
}

// One line per edge mode, offset side by side when they share a node pair
function edgesGJ() {
  const SPACING = 4.5;
  const lit = exploring();

  // "smallerId-largerId" → [{e, mode}]
  const pairMap = new Map();
  for (const e of edges) {
    const key = `${Math.min(e.from, e.to)}-${Math.max(e.from, e.to)}`;
    if (!pairMap.has(key)) pairMap.set(key, []);
    for (const mode of e.modes.length ? e.modes : ['BUS']) pairMap.get(key).push({ e, mode });
  }

  const features = [];
  for (const items of pairMap.values()) {
    items.sort((a, b) => MODE_ORDER.indexOf(a.mode) - MODE_ORDER.indexOf(b.mode));
    const n = items.length;
    items.forEach(({ e, mode }, i) => features.push(lineFeature(e.coordinates, {
      id: e.id,
      mode,
      lineOffset: n === 1 ? 0 : (i - (n - 1) / 2) * SPACING,
      dimmed: lit && e.from !== exploreNodeId && e.to !== exploreNodeId,
    })));
  }
  return featureCollection(features);
}

function fitTo(points, options) {
  let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
  for (const [lng, lat] of points) {
    w = Math.min(w, lng); e = Math.max(e, lng);
    s = Math.min(s, lat); n = Math.max(n, lat);
  }
  map.fitBounds([[w, s], [e, n]], options);
}

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
    const empty = featureCollection([]);
    for (const id of ['roads-display', 'edges', 'selected-edge', 'preview', 'nodes', 'components-edges', 'components-nodes']) {
      map.addSource(id, { type: 'geojson', data: empty });
    }

    // Pie icons for all 16 mode combinations
    for (let mask = 0; mask < 16; mask++) {
      const modes = MODE_ORDER.filter((_, i) => mask & (1 << i));
      map.addImage(`node-pie-${modesKey(new Set(modes))}`, makePieIcon(modes));
    }

    map.addLayer({
      id: 'roads-display', type: 'line', source: 'roads-display',
      paint: { 'line-color': '#94a3b8', 'line-width': 1.5, 'line-opacity': 0.5 },
    });

    // Selected edge halo, below the edges
    map.addLayer({
      id: 'selected-edge', type: 'line', source: 'selected-edge',
      paint: { 'line-color': '#ffffff', 'line-width': 11, 'line-opacity': 0.35 },
    });

    // Wide train casing so bus edges on the rail route don't hide it
    map.addLayer({
      id: 'edges-train-casing', type: 'line', source: 'edges',
      filter: ['==', ['get', 'mode'], 'TRAIN'],
      paint: {
        'line-color': MODE_COLORS.TRAIN,
        'line-width': 10,
        'line-offset': ['get', 'lineOffset'],
        'line-opacity': dimmedOr(0.08, 0.8),
      },
    });

    map.addLayer({
      id: 'edges', type: 'line', source: 'edges',
      paint: {
        'line-color': ['match', ['get', 'mode'], ...Object.entries(MODE_COLORS).flat(), '#94a3b8'],
        'line-width': 4,
        'line-offset': ['get', 'lineOffset'],
        'line-opacity': dimmedOr(0.1, 1),
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
      paint: { 'icon-opacity': dimmedOr(0.18, 1) },
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
      paint: { 'text-color': '#ffffff', 'text-opacity': dimmedOr(0.18, 1) },
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
        'text-opacity': dimmedOr(0.18, 1),
      },
    });

    // "Show Components" overlay, hidden until toggled
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
    refreshSources();
    loadRoads();
  });
}

function loadRoads() {
  map.getSource('roads-display').setData(WELLINGTON_GEOJSON);
  fitTo(WELLINGTON_GEOJSON.features.flatMap(f => getCoordLines(f.geometry).flat()), { padding: 40 });

  setStatus('Building road graph…');
  setTimeout(() => {
    buildIndex(WELLINGTON_GEOJSON);
    for (const id of ['btn-gen-train', 'btn-gen-bus', 'btn-gen-escooter']) {
      document.getElementById(id).disabled = false;
    }
    setStatus(`Ready — ${graphAdj.size.toLocaleString()} road vertices. Click a road to place a node.`);
  }, 30);
}

// Redraws nodes and edges and updates the counts.
function refreshSources() {
  document.getElementById('node-count').textContent = nodes.length;
  document.getElementById('edge-count').textContent = edges.length;
  if (!map) return;
  map.getSource('nodes')?.setData(nodesGJ());
  map.getSource('edges')?.setData(edgesGJ());
}

function setPreview(coords) {
  map?.getSource('preview')?.setData(featureCollection(coords ? [lineFeature(coords)] : []));
}

// Colours each connected component differently so islands stand out.
function toggleComponents() {
  componentsMode = !componentsMode;
  const btn = document.getElementById('btn-show-components');
  const graphLayers = ['edges-train-casing', 'edges', 'nodes', 'node-labels', 'node-name-labels'];
  const compLayers  = ['components-edges', 'components-nodes'];
  const show = (ids, on) => { for (const id of ids) map.setLayoutProperty(id, 'visibility', on ? 'visible' : 'none'); };

  show(graphLayers, !componentsMode);
  show(compLayers, componentsMode);
  btn.classList.toggle('active', componentsMode);
  btn.textContent = componentsMode ? 'Hide Components' : 'Show Components';

  if (!componentsMode) { setStatus('Ready'); return; }

  const { compOf, comps } = computeComponents();
  const color = id => COMPONENT_PALETTE[(compOf.get(id) ?? 0) % COMPONENT_PALETTE.length];
  map.getSource('components-nodes').setData(featureCollection(nodes.map(n => pointFeature(n, { color: color(n.id) }))));
  map.getSource('components-edges').setData(featureCollection(edges.map(e => lineFeature(e.coordinates, { color: color(e.from) }))));

  const sizes = comps.map(c => c.length).sort((a, b) => b - a);
  setStatus(sizes.length === 1
    ? `1 connected component — fully connected (${sizes[0]} nodes)`
    : `${sizes.length} connected components — sizes: ${sizes.join(', ')}`);
}

function selectEdge(id) {
  selectedEdgeId = id;
  const panel = document.getElementById('edge-panel');
  const edge  = id === null ? null : edges.find(e => e.id === id);

  if (!edge) {
    selectedEdgeId = null;
    panel.style.display = 'none';
    map.getSource('selected-edge')?.setData(featureCollection([]));
    return;
  }

  map.getSource('selected-edge')?.setData(featureCollection([lineFeature(edge.coordinates)]));
  const label = nodeId => nodes.find(n => n.id === nodeId)?.label ?? nodeId;
  document.getElementById('ep-label').textContent = `${label(edge.from)} → ${label(edge.to)}`;
  document.querySelectorAll('.ep-mode-btn').forEach(btn =>
    btn.classList.toggle('active', edge.modes.includes(btn.dataset.mode)));
  panel.style.display = 'flex';
}
