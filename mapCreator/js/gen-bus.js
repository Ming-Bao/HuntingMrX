'use strict';

// Bus Stops: park-and-ride spurs for stations, then a bus mesh per cluster.

async function genBus() {
  clearModeEdges('BUS');
  rebuildUsedSegs();
  anchorStations();
  await yld();

  for (const cl of BUS_CLUSTERS) {
    setStatus(`Bus: sampling ${cl.name}…`);
    await yld();

    const cluster = sampleVertices(cl.center, cl.radiusM, cl.count).map(v => genNode(v.key)).filter(Boolean);

    // Nearby train stations join the cluster as interchanges
    const inCluster = new Set(cluster.map(n => n.id));
    const byId = new Map(nodes.map(n => [n.id, n]));
    for (const sid of [...trainNodeIds()].sort((a, b) => a - b)) {
      const s = byId.get(sid);
      if (inCluster.has(sid) || !s?.segAKey) continue;
      if (haversine([s.lng, s.lat], cl.center) <= cl.radiusM * 1.5) {
        cluster.push(s);
        inCluster.add(sid);
      }
    }

    if (cluster.length < 2) continue;
    meshCluster(cluster, 'BUS', BUS_EXTRA_NN);
    refreshSources();
    setStatus(`Bus: ${cl.name} done (${cluster.length} nodes)…`);
    await yld();
  }

  refreshSources();
  setStatus(`Bus done — ${modeSummary('BUS')}`);
}

// Gives stations a road-mode link: a spur node on the middle of a rail hop,
// with BUS edges along the rail route to both of its stations.
function anchorStations() {
  const trainEdges = edges.filter(e => e.modes.includes('TRAIN'));
  const stationIds = new Set(trainEdges.flatMap(e => [e.from, e.to]));
  const anchored = new Set();
  for (const e of edges) {
    if (stationIds.has(e.from) && !stationIds.has(e.to)) anchored.add(e.from);
    if (stationIds.has(e.to) && !stationIds.has(e.from)) anchored.add(e.to);
  }
  const unanchored = e => (anchored.has(e.from) ? 0 : 1) + (anchored.has(e.to) ? 0 : 1);
  // Hops with two unanchored stations first
  const railSorted = [...trainEdges].sort((a, b) => unanchored(b) - unanchored(a) || a.from - b.from || a.to - b.to);

  // Spurs keep clear of all nodes and other edges (the drive-by rule)
  const clearM = DRIVEBY_M + 15;
  const spurPointOk = (pt, rail) =>
    nodes.every(o => haversine([o.lng, o.lat], pt) >= clearM) &&
    edges.every(e => e === rail || e.coordinates.every((c, i) => i === 0 || ptSegDistM(pt, e.coordinates[i - 1], c).d >= clearM));

  let spurs = 0;
  for (const rail of railSorted) {
    if (anchored.has(rail.from) && anchored.has(rail.to)) continue;
    const c = rail.coordinates;
    if (c.length < 4) continue;

    // Search outward from the middle for a clear point
    let mid = -1;
    const center = Math.floor(c.length / 2);
    for (let off = 0; off <= c.length / 2 && mid < 0; off++) {
      for (const i of off === 0 ? [center] : [center - off, center + off]) {
        if (i >= 1 && i <= c.length - 2 && spurPointOk(c[i], rail)) { mid = i; break; }
      }
    }
    if (mid < 0) continue;

    const key = coordKey(c[mid]);
    const spur = pushNode({ lng: c[mid][0], lat: c[mid][1], segAKey: key, segBKey: key });
    registerSegs(pushEdge(rail.from, spur.id, ['BUS'], c.slice(0, mid + 1)));
    registerSegs(pushEdge(spur.id, rail.to, ['BUS'], c.slice(mid)));
    anchored.add(rail.from);
    anchored.add(rail.to);
    spurs++;
  }
  setStatus(`Anchored ${anchored.size} stations via ${spurs} park-and-ride spurs…`);
}
