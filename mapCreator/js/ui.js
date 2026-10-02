'use strict';

// Mouse, toolbar and edge-panel wiring, then boot.

const $ = id => document.getElementById(id);

function setStatus(msg) {
  $('status').textContent = msg;
}

const drag = { active: false, nodeId: null, moved: false, justDragged: false };

function endDrag() {
  setPreview(null);
  map.dragPan.enable();
  drag.active = false;
  drag.nodeId = null;
  drag.moved  = false;
}

function setupInteractions() {
  const nodeAt = point => map.queryRenderedFeatures(point, { layers: ['nodes'] })[0]?.properties.id;
  const cursor = c => () => { map.getCanvas().style.cursor = c; };
  map.on('mouseenter', 'nodes', cursor('grab'));
  map.on('mouseleave', 'nodes', cursor(''));
  map.on('mouseenter', 'edges', cursor('pointer'));
  map.on('mouseleave', 'edges', cursor(''));

  // Drag node → node to add an edge
  map.on('mousedown', e => {
    if (e.originalEvent.button !== 0) return;
    const id = nodeAt(e.point);
    if (id === undefined) return;
    Object.assign(drag, { active: true, nodeId: id, moved: false });
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
    const from = drag.nodeId, moved = drag.moved;
    endDrag();
    if (!moved) return;
    // Swallow the click that follows
    drag.justDragged = true;
    const to = nodeAt(e.point);
    if (to !== undefined && to !== from) addEdge(from, to);
  });

  map.getCanvas().addEventListener('mouseleave', () => {
    if (!drag.active) return;
    endDrag();
    drag.justDragged = false;
  });

  // Node: toggle neighbour highlight. Road: add a node.
  map.on('click', e => {
    if (drag.justDragged) { drag.justDragged = false; return; }
    selectEdge(null);

    const id = nodeAt(e.point);
    if (id !== undefined) {
      exploreNodeId = exploreNodeId === id ? null : id;
      refreshSources();
      return;
    }
    if (exploreNodeId !== null) { exploreNodeId = null; refreshSources(); }

    const snap = snapToRoad(e.lngLat.lng, e.lngLat.lat);
    if (!snap) { setStatus('No road found nearby — zoom in and click closer to a road'); return; }
    setStatus(`Node ${addNode(snap).id} added`);
  });

  // preventDefault stops the double-click zoom
  map.on('dblclick', e => {
    const id = nodeAt(e.point);
    if (id === undefined) return;
    e.preventDefault();
    selectEdge(null);
    renameNode(id);
  });

  map.on('contextmenu', e => {
    e.preventDefault();
    const id = nodeAt(e.point);
    if (id !== undefined) {
      selectEdge(null);
      removeNode(id);
      return;
    }
    const edgeId = map.queryRenderedFeatures(e.point, { layers: ['edges'] })[0]?.properties.id;
    if (edgeId !== undefined) selectEdge(edgeId);
  });
}

// Toggles `mode`, never removing the last one.
function toggleMode(modes, mode) {
  if (!modes.includes(mode)) return [...modes, mode];
  return modes.length > 1 ? modes.filter(m => m !== mode) : modes;
}

// ── Edge panel ────────────────────────────────────────────

document.querySelectorAll('.ep-mode-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const edge = edges.find(e => e.id === selectedEdgeId);
    if (!edge) return;
    saveUndo();
    edge.modes = toggleMode(edge.modes, btn.dataset.mode);
    btn.classList.toggle('active', edge.modes.includes(btn.dataset.mode));
    refreshSources();
  });
});

$('ep-delete').addEventListener('click', () => {
  if (selectedEdgeId !== null) { removeEdge(selectedEdgeId); selectEdge(null); }
});
$('ep-close').addEventListener('click', () => selectEdge(null));

// ── Toolbar ───────────────────────────────────────────────

$('btn-load-map').addEventListener('click', () => $('file-map').click());
$('file-map').addEventListener('change', e => {
  if (e.target.files[0]) loadMapFile(e.target.files[0]);
  e.target.value = '';
});
$('btn-save-map').addEventListener('click', saveMap);
$('btn-undo').addEventListener('click', undo);
$('btn-show-components').addEventListener('click', toggleComponents);
$('btn-renumber').addEventListener('click', renumberNodesReadingOrder);

$('btn-search-node').addEventListener('click', () => searchNode($('search-node').value));
$('search-node').addEventListener('keydown', e => {
  if (e.key === 'Enter') { e.preventDefault(); searchNode(e.target.value); }
});

document.addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) { e.preventDefault(); undo(); }
});

for (const [id, gen] of [['btn-gen-train', genTrain], ['btn-gen-bus', genBus], ['btn-gen-escooter', genEscooter]]) {
  const btn = $(id);
  btn.addEventListener('click', async () => {
    saveUndo();
    btn.disabled = true;
    try { await gen(); } finally { btn.disabled = false; }
  });
}

// Scoped to the toolbar: the edge panel's buttons share the class
const toolbarModeBtns = document.querySelectorAll('#toolbar .mode-btn');
toolbarModeBtns.forEach(btn => {
  btn.addEventListener('click', () => {
    activeModes = new Set(toggleMode([...activeModes], btn.dataset.mode));
    toolbarModeBtns.forEach(b => b.classList.toggle('active', activeModes.has(b.dataset.mode)));
  });
});

initMap();
