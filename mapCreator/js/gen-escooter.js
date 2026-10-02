'use strict';

// Escooter Links: promote short bus edges, mesh each cluster, then join
// everything into one connected map.

async function genEscooter() {
  clearModeEdges('ESCOOTER');
  rebuildUsedSegs();

  // Short bus edges also carry escooter.
  const byId = new Map(nodes.map(n => [n.id, n]));
  let promoted = 0;
  for (const e of edges) {
    if (!e.modes.includes('BUS') || e.modes.includes('ESCOOTER')) continue;
    const a = byId.get(e.from), b = byId.get(e.to);
    if (a && b && nodeDist(a, b) > ESCOOTER_PROMOTE_MAX_M) continue;
    e.modes = [...e.modes, 'ESCOOTER'];
    promoted++;
  }

  for (const cl of ESCOOTER_CLUSTERS) {
    setStatus(`Escooter: sampling ${cl.name}…`);
    await yld();
    const cluster = sampleVertices(cl.center, cl.radiusM, cl.count).map(v => genNode(v.key)).filter(Boolean);
    if (cluster.length < 2) continue;
    meshCluster(cluster, 'ESCOOTER', ESCOOTER_EXTRA_NN);
    refreshSources();
    setStatus(`Escooter: ${cl.name} done (${cluster.length} nodes)…`);
    await yld();
  }

  stitchStations();
  await yld();
  await bridgeComponents();
  repairDegrees();

  refreshSources();
  setStatus(`Escooter done — ${modeSummary('ESCOOTER')} (${promoted} bus edges promoted)`);
}

// Links stations with no road-mode edge to their nearest mesh nodes.
function stitchStations() {
  const meshIds = new Set(edges.filter(isRoadMode).flatMap(e => [e.from, e.to]));
  const byId = new Map(nodes.map(n => [n.id, n]));
  let stitched = 0;
  for (const tid of trainNodeIds()) {
    const t = byId.get(tid);
    if (meshIds.has(tid) || !t) continue;
    const cands = nodes
      .filter(n => meshIds.has(n.id))
      .map(n => ({ n, d: nodeDist(t, n) }))
      .filter(c => c.d <= STITCH_MAX_M)
      .sort((a, b) => a.d - b.d);
    let added = 0;
    for (const { n } of cands) {
      if (added >= STITCH_DEGREE) break;
      if (addRoadEdge(t, n, 'ESCOOTER')) added++;
    }
    if (added) { meshIds.add(tid); stitched++; }
  }
  setStatus(`Stitched ${stitched} train stations into the mesh…`);
}

// Links smaller components into the largest by their closest routable pair.
// Unbridgeable components without a station are dropped.
async function bridgeComponents() {
  for (let guard = 0; guard < 10; guard++) {
    const deg = degreeMap();
    const { comps } = computeComponents(nodes.filter(n => deg.has(n.id)));
    if (comps.length <= 1) return;
    comps.sort((a, b) => b.length - a.length);

    const byId = new Map(nodes.map(n => [n.id, n]));
    const cands = [];
    for (const comp of comps.slice(1)) {
      for (const aid of comp) {
        for (const bid of comps[0]) {
          const a = byId.get(aid), b = byId.get(bid), d = nodeDist(a, b);
          if (d <= BRIDGE_MAX_M) cands.push({ a, b, d });
        }
      }
    }
    cands.sort((x, y) => x.d - y.d || x.a.id - y.a.id || x.b.id - y.b.id);

    if (!cands.some(c => addRoadEdge(c.a, c.b, 'ESCOOTER'))) {
      const stations = trainNodeIds();
      const strays = new Set(comps.slice(1).filter(c => !c.some(id => stations.has(id))).flat());
      if (strays.size) {
        edges = edges.filter(e => !strays.has(e.from) && !strays.has(e.to));
        nodes = nodes.filter(n => !strays.has(n.id));
        rebuildUsedSegs();
      }
      return;
    }
    setStatus('Bridged a disconnected component into the main graph…');
    await yld();
  }
}

// Links degree < 2 nodes to nearby neighbours, then drops non-station nodes
// still below 2.
function repairDegrees() {
  let deg = degreeMap();
  for (const n of [...nodes]) {
    if ((deg.get(n.id) ?? 0) >= 2) continue;
    const cands = nodes
      .filter(o => o.id !== n.id && (deg.get(o.id) ?? 0) >= 2)
      .map(o => ({ o, d: nodeDist(n, o) }))
      .filter(c => c.d <= REPAIR_MAX_M)
      .sort((a, b) => a.d - b.d || a.o.id - b.o.id);
    for (const { o } of cands) {
      if ((deg.get(n.id) ?? 0) >= 2) break;
      if (addRoadEdge(n, o, 'ESCOOTER')) deg = degreeMap();
    }
  }

  const stations = trainNodeIds();
  for (let pass = 0; pass < 5; pass++) {
    deg = degreeMap();
    const drop = new Set(nodes.filter(n => (deg.get(n.id) ?? 0) < 2 && !stations.has(n.id)).map(n => n.id));
    if (!drop.size) break;
    edges = edges.filter(e => !drop.has(e.from) && !drop.has(e.to));
    nodes = nodes.filter(n => !drop.has(n.id));
  }
  rebuildUsedSegs();
}
