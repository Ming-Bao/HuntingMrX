'use strict';

// Train Stops: a node per station, consecutive stops linked by TRAIN edges.

async function genTrain() {
  clearModeEdges('TRAIN');
  rebuildUsedSegs();
  setStatus('Placing train nodes at actual station positions…');

  // Trains may not pass a station without stopping.
  const stations = [];

  function nearestNode(coord, radiusM) {
    let best = null, bestD = radiusM;
    for (const n of nodes) {
      const d = haversine([n.lng, n.lat], coord);
      if (d < bestD) { bestD = d; best = n; }
    }
    return best;
  }

  function addTrainEdge(from, to, depth = 0) {
    if (from.id === to.id) return false;
    const existing = findEdge(from.id, to.id);
    if (existing) return existing.modes.includes('TRAIN');
    if (!from.segAKey || !to.segAKey) return false;
    const maxDist = nodeDist(from, to) * 3;
    let coords = findRoadPath(from, to, maxDist, [from.id, to.id]);
    // Rail lines can share track, so retry without exclusivity.
    if (coords.length <= 2) coords = findRoadPath(from, to, maxDist);
    if (coords.length <= 2) return false;
    const blockers = drivebyBlockers(coords, from.id, to.id, stations, DRIVEBY_TRAIN_M);
    if (blockers.length) return splitVia(from, [...blockers, to], depth, addTrainEdge);
    registerSegs(pushEdge(from.id, to.id, ['TRAIN'], coords));
    return true;
  }

  // Place all stations before any edges so the drive-by check sees them all.
  // Stations shared by several lines merge into one node.
  const lineNodes = WELLINGTON_TRAIN_LINES.map(line => line.map(coord => {
    let node = nearestNode(coord, MERGE_RADIUS_M);
    if (!node) {
      const snap = snapToRoad(coord[0], coord[1]);
      node = pushNode({ lng: coord[0], lat: coord[1], segAKey: snap?.segAKey ?? null, segBKey: snap?.segBKey ?? null, offRoad: true });
    }
    if (!stations.includes(node)) stations.push(node);
    return node;
  }));

  for (let li = 0; li < lineNodes.length; li++) {
    const line = lineNodes[li];
    for (let i = 1; i < line.length; i++) addTrainEdge(line[i - 1], line[i]);
    setStatus(`Train: line ${li + 1}/${lineNodes.length}…`);
    refreshSources();
    await yld();
  }

  refreshSources();
  setStatus(`Train done — ${modeSummary('TRAIN')}`);
}
