'use strict';

// Geometry helpers and a min-heap. Coordinates are [lng, lat].

function coordKey([lng, lat]) {
  return `${lng.toFixed(6)},${lat.toFixed(6)}`;
}

function coordFromKey(key) {
  return key.split(',').map(Number);
}

function haversine([lng1, lat1], [lng2, lat2]) {
  const R  = 6_371_000;
  const φ1 = lat1 * Math.PI / 180, φ2 = lat2 * Math.PI / 180;
  const Δφ = (lat2 - lat1) * Math.PI / 180;
  const Δλ = (lng2 - lng1) * Math.PI / 180;
  const a  = Math.sin(Δφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// Metres between two nodes
const nodeDist = (a, b) => haversine([a.lng, a.lat], [b.lng, b.lat]);

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

// Point-to-segment distance in metres (flat-earth approximation at Wellington's
// latitude) and the fraction t along the segment.
const WLG_COS   = Math.cos(-41.25 * Math.PI / 180);
const M_PER_DEG = 6_371_000 * Math.PI / 180;

function ptSegDistM(p, a, b) {
  const px = (p[0] - a[0]) * WLG_COS, py = p[1] - a[1];
  const bx = (b[0] - a[0]) * WLG_COS, by = b[1] - a[1];
  const lenSq = bx * bx + by * by;
  const t = lenSq === 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / lenSq));
  const dx = px - t * bx, dy = py - t * by;
  return { d: Math.sqrt(dx * dx + dy * dy) * M_PER_DEG, t };
}

function getCoordLines(geometry) {
  if (geometry.type === 'LineString')      return [geometry.coordinates];
  if (geometry.type === 'MultiLineString') return geometry.coordinates;
  return [];
}

// Min-heap of [priority, value] pairs
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
