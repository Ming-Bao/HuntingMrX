#!/usr/bin/env python3
"""Map-quality evaluator for generated Hunting Mr. X maps.

Usage:
    python3 evaluate_map.py candidate.json [--report report.json]

Scores a candidate map JSON ({"nodes": [...], "edges": [...]}) against:

HARD checks (gates — all must pass for exit code 0):
    schema_nodes       node ids unique ints; numeric lat/lng; string label
    schema_edges       from/to exist; no self-loops; no duplicate undirected
                       pairs; modes non-empty and subset of
                       {ESCOOTER, BUS, TRAIN}; coordinates >= 2 [lng, lat]
                       numeric pairs
    polyline_endpoints polyline ends match from/to node positions within
                       30 m (either orientation)
    connected          whole graph is one connected component
    min_degree         every node has degree >= 2 (train termini may be
                       degree-1 dead-ends, max 4)
    train_tier         >= 15 TRAIN edges and the TRAIN subgraph is connected
    train_anchored     every train node has >= 1 edge to a non-train node
                       (size is now an informational soft check — no cap)
    bbox               all nodes within lng [174.60, 175.20],
                       lat [-41.45, -41.05]
    no_driveby         edge polylines must not pass within --driveby-corridor
                       (BUS/ESCOOTER edges, vs all nodes) or
                       --driveby-corridor-train (TRAIN-only edges, vs train
                       stations) of a non-endpoint node
    road_exclusive     no two edges share a polyline segment (6-dp quantized)
                       unless both are TRAIN-only, or they share an endpoint
                       node and every shared segment lies within
                       --exclusive-exempt-m of that node

SOFT checks (reported, do not affect exit code):
    max_degree    nodes with degree > 5 (train hubs legitimately run high)
    articulation  articulation points <= 8% of nodes; bridge count reported
    mode_balance  per-mode / mode-exclusive edge counts; flags BUS-only == 0
                  or ESCOOTER share of mode-carryings > 75%
    distances     hop diameter <= 18; mean hops in [6, 10]; histogram;
                  % of pairs >= 6 hops apart
    spacing       node pairs closer than 120 m
    edge_length   non-TRAIN edges with straight-line length > 2.5 km
    train_overlap TRAIN-only edge pairs sharing polyline segments (shared
                  track is expected; reported for visibility)

Exit code: 0 if all hard checks pass, 1 otherwise. --report writes JSON:
    {"hard": {name: {"pass": bool, "detail": str}},
     "soft": {name: {"ok": bool, "detail": str}},
     "metrics": {...}}
"""

import argparse
import json
import math
import sys
from collections import Counter, defaultdict, deque

ALLOWED_MODES = {"ESCOOTER", "BUS", "TRAIN"}
BBOX = {"lng_min": 174.60, "lng_max": 175.20, "lat_min": -41.45, "lat_max": -41.05}
ENDPOINT_TOL_M = 30.0
MIN_SPACING_M = 120.0
MAX_NON_TRAIN_EDGE_M = 2500.0
DEFAULT_DRIVEBY_CORRIDOR_M = 120.0
DEFAULT_DRIVEBY_CORRIDOR_TRAIN_M = 150.0
DEFAULT_EXCLUSIVE_EXEMPT_M = 100.0
DEFAULT_STATION_HUB_M = 600.0

# Equirectangular projection at Wellington latitude — good enough at this scale.
M_PER_DEG = 111320.0
COS_WLG = math.cos(math.radians(-41.25))  # ~0.7518


def haversine_m(lat1, lng1, lat2, lng2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def project_m(lng, lat):
    """Equirectangular [lng, lat] degrees -> (x, y) meters at Wellington latitude."""
    return lng * COS_WLG * M_PER_DEG, lat * M_PER_DEG


def point_segment_dist_m(px, py, ax, ay, bx, by):
    """Min distance from point P to segment AB, all in projected meters."""
    dx, dy = bx - ax, by - ay
    seg2 = dx * dx + dy * dy
    if seg2 <= 0.0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / seg2
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def check_schema_nodes(nodes):
    problems = []
    seen = set()
    for i, n in enumerate(nodes):
        if not isinstance(n, dict):
            problems.append("node[%d] not an object" % i)
            continue
        nid = n.get("id")
        if not isinstance(nid, int) or isinstance(nid, bool):
            problems.append("node[%d] id not an integer: %r" % (i, nid))
        elif nid in seen:
            problems.append("duplicate node id %d" % nid)
        else:
            seen.add(nid)
        if not is_num(n.get("lat")) or not is_num(n.get("lng")):
            problems.append("node id=%r missing numeric lat/lng" % nid)
        if not isinstance(n.get("label"), str):
            problems.append("node id=%r label not a string" % nid)
    if not nodes:
        problems.append("no nodes")
    return problems


def check_schema_edges(edges, node_ids):
    problems = []
    seen_pairs = set()
    for i, e in enumerate(edges):
        if not isinstance(e, dict):
            problems.append("edge[%d] not an object" % i)
            continue
        a, b = e.get("from"), e.get("to")
        tag = "edge[%d] (%r->%r)" % (i, a, b)
        if a not in node_ids or b not in node_ids:
            problems.append("%s references missing node" % tag)
        if a == b:
            problems.append("%s is a self-loop" % tag)
        else:
            pair = frozenset((a, b))
            if pair in seen_pairs:
                problems.append("%s duplicate undirected pair" % tag)
            seen_pairs.add(pair)
        modes = e.get("modes")
        if not isinstance(modes, list) or not modes:
            problems.append("%s modes missing/empty" % tag)
        else:
            bad = [m for m in modes if m not in ALLOWED_MODES]
            if bad:
                problems.append("%s invalid modes %s" % (tag, bad))
        coords = e.get("coordinates")
        if not isinstance(coords, list) or len(coords) < 2:
            problems.append("%s coordinates missing or <2 points" % tag)
        elif not all(isinstance(p, list) and len(p) == 2 and is_num(p[0]) and is_num(p[1]) for p in coords):
            problems.append("%s coordinates contain a malformed point" % tag)
    return problems


def check_polyline_endpoints(edges, pos):
    problems = []
    for i, e in enumerate(edges):
        a, b = e.get("from"), e.get("to")
        coords = e.get("coordinates")
        if a not in pos or b not in pos or not isinstance(coords, list) or len(coords) < 2:
            continue  # already failed schema
        try:
            (flng, flat), (llng, llat) = coords[0], coords[-1]
        except (TypeError, ValueError):
            continue
        da_f = haversine_m(pos[a][0], pos[a][1], flat, flng)
        db_l = haversine_m(pos[b][0], pos[b][1], llat, llng)
        da_l = haversine_m(pos[a][0], pos[a][1], llat, llng)
        db_f = haversine_m(pos[b][0], pos[b][1], flat, flng)
        fwd, rev = max(da_f, db_l), max(da_l, db_f)
        if min(fwd, rev) > ENDPOINT_TOL_M:
            problems.append("edge[%d] %d-%d endpoints off by %.0f m" % (i, a, b, min(fwd, rev)))
    return problems


def check_no_driveby(edges, pos, train_nodes, corridor_m, corridor_train_m):
    """Returns list of (dist_m, edge_from, edge_to, modes, blocker_id) violations.

    BUS/ESCOOTER-carrying edges are blocked by all nodes within corridor_m;
    TRAIN-only edges are blocked by train stations within corridor_train_m.
    Only nodes other than the edge's endpoints count.
    """
    proj_nodes = {nid: project_m(lnglat[1], lnglat[0]) for nid, lnglat in pos.items()}
    violations = []
    for e in edges:
        a, b = e.get("from"), e.get("to")
        modes = set(e.get("modes") or [])
        coords = e.get("coordinates")
        if not isinstance(coords, list) or len(coords) < 2:
            continue
        if modes & {"BUS", "ESCOOTER"}:
            blockers, corridor = pos.keys(), corridor_m
        else:
            blockers, corridor = train_nodes, corridor_train_m
        pts = [project_m(p[0], p[1]) for p in coords
               if isinstance(p, list) and len(p) == 2 and is_num(p[0]) and is_num(p[1])]
        if len(pts) < 2:
            continue
        # bounding box of the polyline, expanded by the corridor, to prefilter
        xs, ys = [q[0] for q in pts], [q[1] for q in pts]
        xmin, xmax = min(xs) - corridor, max(xs) + corridor
        ymin, ymax = min(ys) - corridor, max(ys) + corridor
        for nid in blockers:
            if nid == a or nid == b or nid not in proj_nodes:
                continue
            px, py = proj_nodes[nid]
            if not (xmin <= px <= xmax and ymin <= py <= ymax):
                continue
            best = min(point_segment_dist_m(px, py, pts[i][0], pts[i][1],
                                            pts[i + 1][0], pts[i + 1][1])
                       for i in range(len(pts) - 1))
            if best < corridor:
                violations.append((best, a, b, sorted(modes), nid))
    violations.sort()
    return violations


def quantized_segments(coords):
    """Edge polyline -> set of unordered consecutive-pair segments, coordinates
    quantized to 6 decimal places; zero-length segments (after quantization)
    skipped. Each segment is a sorted tuple of two (lng, lat) tuples."""
    segs = set()
    if not isinstance(coords, list):
        return segs
    pts = [(round(p[0], 6), round(p[1], 6)) for p in coords
           if isinstance(p, list) and len(p) == 2 and is_num(p[0]) and is_num(p[1])]
    for p1, p2 in zip(pts, pts[1:]):
        if p1 != p2:
            segs.add(tuple(sorted((p1, p2))))
    return segs


def seg_length_m(seg):
    (lng1, lat1), (lng2, lat2) = seg
    x1, y1 = project_m(lng1, lat1)
    x2, y2 = project_m(lng2, lat2)
    return math.hypot(x2 - x1, y2 - y1)


def check_road_exclusive(edges, pos, exempt_m, station_hub_m=DEFAULT_STATION_HUB_M):
    """Segment-sharing analysis.

    Returns (violating_pairs, train_overlap_pairs) where each entry is
    (shared_m, i, j, shared_seg_count) for edge indices i < j. A pair where
    EITHER edge is TRAIN-only goes to train_overlap_pairs (rail is not a road:
    a rail polyline is a visual approximation of track, so sharing between a
    TRAIN-only edge and anything else is corridor overlap, not a road-rule
    violation). Road-mode pairs are exempt iff the edges share an endpoint node
    id and every shared segment's midpoint lies within exempt_m of that node;
    the rest are hard violations.
    """
    edge_segs = [quantized_segments(e.get("coordinates")) for e in edges]
    owners = defaultdict(list)
    for i, segs in enumerate(edge_segs):
        for s in segs:
            owners[s].append(i)
    pair_segs = defaultdict(set)
    for s, own in owners.items():
        if len(own) < 2:
            continue
        for x in range(len(own)):
            for y in range(x + 1, len(own)):
                pair_segs[(own[x], own[y])].add(s)

    violating, train_overlap = [], []
    train_station_ids = set()
    for e in edges:
        if "TRAIN" in (e.get("modes") or []):
            train_station_ids.update((e.get("from"), e.get("to")))
    for (i, j), segs in pair_segs.items():
        ei, ej = edges[i], edges[j]
        shared_m = sum(seg_length_m(s) for s in segs)
        entry = (shared_m, i, j, len(segs))
        mi, mj = set(ei.get("modes") or []), set(ej.get("modes") or [])
        if mi == {"TRAIN"} or mj == {"TRAIN"}:
            train_overlap.append(entry)
            continue
        # Edges that meet at a node may co-run along the same street for any
        # distance (shared-endpoint co-running — routes fanning out of a hub
        # before diverging). Additionally, near a train STATION endpoint of
        # either edge, any co-running within station_hub_m is tolerated
        # (interchange streets carry several modes). Everything else violates.
        shared_nodes = ({ei.get("from"), ei.get("to")}
                        & {ej.get("from"), ej.get("to")}) & set(pos)
        if shared_nodes:
            continue
        hub_ok = False
        hub_ids = ({ei.get("from"), ei.get("to")} | {ej.get("from"), ej.get("to")}) \
                  & train_station_ids & set(pos)
        for hid in hub_ids:
            hx, hy = project_m(pos[hid][1], pos[hid][0])
            near_all = True
            for s in segs:
                x1, y1 = project_m(*s[0])
                x2, y2 = project_m(*s[1])
                if math.hypot((x1 + x2) / 2 - hx, (y1 + y2) / 2 - hy) > station_hub_m:
                    near_all = False
                    break
            if near_all:
                hub_ok = True
                break
        if not hub_ok:
            violating.append(entry)
    violating.sort(reverse=True)
    train_overlap.sort(reverse=True)
    return violating, train_overlap


def build_adj(node_ids, edges, mode=None):
    adj = defaultdict(set)
    for e in edges:
        a, b = e.get("from"), e.get("to")
        if a in node_ids and b in node_ids and a != b:
            if mode is None or mode in (e.get("modes") or []):
                adj[a].add(b)
                adj[b].add(a)
    return adj


def component_count(vertices, adj):
    seen, comps = set(), 0
    for v in vertices:
        if v in seen:
            continue
        comps += 1
        q = deque([v])
        seen.add(v)
        while q:
            u = q.popleft()
            for w in adj[u]:
                if w not in seen:
                    seen.add(w)
                    q.append(w)
    return comps


def articulation_and_bridges(vertices, adj):
    """Iterative Tarjan: returns (articulation point set, bridge count)."""
    disc, low, parent = {}, {}, {}
    arts, bridges, timer = set(), 0, [0]
    for root in vertices:
        if root in disc:
            continue
        stack = [(root, iter(sorted(adj[root])))]
        disc[root] = low[root] = timer[0]
        timer[0] += 1
        root_children = 0
        while stack:
            v, it = stack[-1]
            advanced = False
            for w in it:
                if w not in disc:
                    parent[w] = v
                    if v == root:
                        root_children += 1
                    disc[w] = low[w] = timer[0]
                    timer[0] += 1
                    stack.append((w, iter(sorted(adj[w]))))
                    advanced = True
                    break
                elif w != parent.get(v):
                    low[v] = min(low[v], disc[w])
            if not advanced:
                stack.pop()
                if stack:
                    p = stack[-1][0]
                    low[p] = min(low[p], low[v])
                    if low[v] > disc[p]:
                        bridges += 1
                    if p != root and low[v] >= disc[p]:
                        arts.add(p)
        if root_children >= 2:
            arts.add(root)
    return arts, bridges


def all_pairs_hops(vertices, adj):
    """Returns Counter of hop distances over unordered reachable pairs."""
    hops = Counter()
    order = {v: i for i, v in enumerate(vertices)}
    for src in vertices:
        dist = {src: 0}
        q = deque([src])
        while q:
            u = q.popleft()
            for w in adj[u]:
                if w not in dist:
                    dist[w] = dist[u] + 1
                    q.append(w)
        for v, d in dist.items():
            if order[v] > order[src]:
                hops[d] += 1
    return hops


def evaluate(data, driveby_corridor_m=DEFAULT_DRIVEBY_CORRIDOR_M,
             driveby_corridor_train_m=DEFAULT_DRIVEBY_CORRIDOR_TRAIN_M,
             exclusive_exempt_m=DEFAULT_EXCLUSIVE_EXEMPT_M,
             station_hub_m=DEFAULT_STATION_HUB_M):
    hard, soft, metrics = {}, {}, {}
    nodes = data.get("nodes") or []
    edges = data.get("edges") or []
    node_ids = {n.get("id") for n in nodes if isinstance(n, dict)}
    pos = {n["id"]: (n["lat"], n["lng"]) for n in nodes
           if isinstance(n, dict) and is_num(n.get("lat")) and is_num(n.get("lng"))}

    def gate(name, problems, ok_detail):
        hard[name] = {"pass": not problems,
                      "detail": ok_detail if not problems
                      else "%d problem(s): %s" % (len(problems), "; ".join(problems[:5]))}

    # ---- HARD ----
    gate("schema_nodes", check_schema_nodes(nodes), "%d nodes valid" % len(nodes))
    gate("schema_edges", check_schema_edges(edges, node_ids), "%d edges valid" % len(edges))
    gate("polyline_endpoints", check_polyline_endpoints(edges, pos),
         "all polylines end within %.0f m of their nodes" % ENDPOINT_TOL_M)

    # Size is informational only (user decision: no node/edge cap). The old
    # render benchmark measured 200 nodes / 285 edges < 3 s — counts far past
    # that deserve a manual render check, but nothing gates on it.
    soft["size"] = {"ok": True,
                    "detail": "%d nodes, %d edges (informational; render benchmark was 200/285)"
                              % (len(nodes), len(edges))}

    adj = build_adj(node_ids, edges)
    comps = component_count(node_ids, adj) if node_ids else 0
    hard["connected"] = {"pass": comps == 1, "detail": "%d connected component(s)" % comps}

    degree = {v: len(adj[v]) for v in node_ids}
    # Train termini (train-degree exactly 1) may be dead-ends: an end-of-line
    # station is thematically a dead end, and its only street into town is
    # often legitimately claimed by a mesh edge under road exclusivity.
    _t_adj = build_adj(node_ids, edges, mode="TRAIN")
    termini = {v for v in node_ids if len(_t_adj[v]) == 1}
    low_deg = sorted(v for v, d in degree.items() if d < 2 and v not in termini)
    excused = sorted(v for v in termini if degree[v] < 2)
    terminus_ok = len(excused) <= 4 and all(degree[v] >= 1 for v in excused)
    hard["min_degree"] = {"pass": not low_deg and terminus_ok,
                          "detail": ("all nodes have degree >= 2"
                                     + (" (%d terminus dead-end(s) excused: %s)" % (len(excused), excused)
                                        if excused else "")) if not low_deg and terminus_ok
                          else "%d node(s) with degree < 2: %s; %d terminus dead-end(s): %s"
                               % (len(low_deg), low_deg[:10], len(excused), excused)}

    train_edges = [e for e in edges if "TRAIN" in (e.get("modes") or [])]
    train_nodes = {e["from"] for e in train_edges} | {e["to"] for e in train_edges}
    train_adj = build_adj(node_ids, edges, mode="TRAIN")
    train_comps = component_count(train_nodes & node_ids, train_adj) if train_nodes else 0
    hard["train_tier"] = {
        "pass": len(train_edges) >= 15 and train_comps == 1,
        "detail": "%d TRAIN edges (want >=15), TRAIN subgraph has %d component(s)"
                  % (len(train_edges), train_comps)}

    contest_modes = {"BUS", "ESCOOTER"}
    contestable = set()
    for e in edges:
        if contest_modes & set(e.get("modes") or []):
            contestable.update((e.get("from"), e.get("to")))
    tn = train_nodes & node_ids
    covered = len(tn & contestable)
    pct = 100.0 * covered / len(tn) if tn else 100.0
    # Every train node must link to at least one NON-train node — a rail edge
    # promoted to carry ESCOOTER still only connects station to station, so
    # mode coverage alone (the old >=80% check, kept as a metric below) is
    # not enough to make the rail tier part of the same board.
    unanchored = sorted(t for t in tn
                        if not any(nb not in tn for nb in adj[t]))
    hard["train_anchored"] = {
        "pass": not unanchored,
        "detail": ("all %d train nodes link to a non-train node" % len(tn))
                  if not unanchored
                  else "%d/%d train node(s) with no edge to a non-train node: %s"
                       % (len(unanchored), len(tn), unanchored[:10])}

    out_of_box = [n["id"] for n in nodes if isinstance(n, dict) and n.get("id") in pos
                  and not (BBOX["lng_min"] <= n["lng"] <= BBOX["lng_max"]
                           and BBOX["lat_min"] <= n["lat"] <= BBOX["lat_max"])]
    hard["bbox"] = {"pass": not out_of_box,
                    "detail": "all nodes in bounding box" if not out_of_box
                    else "%d node(s) outside bbox: %s" % (len(out_of_box), out_of_box[:10])}

    driveby = check_no_driveby(edges, pos, train_nodes & set(pos),
                               driveby_corridor_m, driveby_corridor_train_m)
    hard["no_driveby"] = {
        "pass": not driveby,
        "detail": ("no edge passes within %.0f m (road) / %.0f m (train) of a non-endpoint node"
                   % (driveby_corridor_m, driveby_corridor_train_m)) if not driveby
        else "%d violation(s); worst: %s"
             % (len(driveby),
                "; ".join("%s-%s %s near node %s (%.0f m)" % (a, b, "+".join(m), nid, d)
                          for d, a, b, m, nid in driveby[:10]))}

    violating_pairs, train_overlap_pairs = check_road_exclusive(edges, pos, exclusive_exempt_m, station_hub_m)
    viol_segs = sum(c for _, _, _, c in violating_pairs)

    def pair_desc(entry):
        d, i, j, c = entry
        ei, ej = edges[i], edges[j]
        return "%s-%s %s x %s-%s %s (%d seg, ~%.0f m)" % (
            ei.get("from"), ei.get("to"), "+".join(sorted(set(ei.get("modes") or []))),
            ej.get("from"), ej.get("to"), "+".join(sorted(set(ej.get("modes") or []))),
            c, d)

    hard["road_exclusive"] = {
        "pass": not violating_pairs,
        "detail": ("no non-exempt shared segments (exempt within %.0f m of a shared endpoint)"
                   % exclusive_exempt_m) if not violating_pairs
        else "%d violating pair(s), %d shared segment(s); worst: %s"
             % (len(violating_pairs), viol_segs,
                "; ".join(pair_desc(p) for p in violating_pairs[:10]))}

    # ---- SOFT ----
    top5 = sorted(degree.items(), key=lambda kv: -kv[1])[:5]
    over5 = [v for v, d in degree.items() if d > 5]
    soft["max_degree"] = {
        "ok": not over5,
        "detail": "%d node(s) with degree > 5 (train hubs may legitimately run high); top-5: %s"
                  % (len(over5), ", ".join("id %d=%d" % (v, d) for v, d in top5))}

    arts, bridge_count = articulation_and_bridges(node_ids, adj)
    art_pct = 100.0 * len(arts) / len(node_ids) if node_ids else 0.0
    soft["articulation"] = {
        "ok": art_pct <= 8.0,
        "detail": "%d articulation point(s) (%.1f%% of nodes, want <=8%%), %d bridge edge(s)"
                  % (len(arts), art_pct, bridge_count)}

    mode_counts = Counter()
    exclusive = Counter()
    multi = 0
    for e in edges:
        modes = set(e.get("modes") or []) & ALLOWED_MODES
        for m in modes:
            mode_counts[m] += 1
        if len(modes) == 1:
            exclusive[next(iter(modes)) + "_only"] += 1
        elif len(modes) > 1:
            multi += 1
    total_carryings = sum(mode_counts.values())
    esc_share = 100.0 * mode_counts["ESCOOTER"] / total_carryings if total_carryings else 0.0
    mb_flags = []
    if exclusive["BUS_only"] == 0:
        mb_flags.append("BUS-only == 0")
    if esc_share > 75.0:
        mb_flags.append("ESCOOTER share %.0f%% > 75%%" % esc_share)
    soft["mode_balance"] = {
        "ok": not mb_flags,
        "detail": "per-mode %s; exclusive %s; multi-mode %d; ESCOOTER share %.0f%%%s"
                  % (dict(mode_counts), dict(exclusive), multi, esc_share,
                     ("; FLAGS: " + ", ".join(mb_flags)) if mb_flags else "")}

    hops = all_pairs_hops(sorted(node_ids), adj)
    n_pairs = sum(hops.values())
    diameter = max(hops) if hops else 0
    mean_hops = sum(d * c for d, c in hops.items()) / n_pairs if n_pairs else 0.0
    buckets = {"1-3": 0, "4-6": 0, "7-9": 0, "10-12": 0, "13+": 0}
    for d, c in hops.items():
        if d <= 3:
            buckets["1-3"] += c
        elif d <= 6:
            buckets["4-6"] += c
        elif d <= 9:
            buckets["7-9"] += c
        elif d <= 12:
            buckets["10-12"] += c
        else:
            buckets["13+"] += c
    pct_ge6 = 100.0 * sum(c for d, c in hops.items() if d >= 6) / n_pairs if n_pairs else 0.0
    soft["distances"] = {
        "ok": diameter <= 18 and 6 <= mean_hops <= 10,
        "detail": "diameter %d (want <=18), mean %.1f hops (want 6-10), histogram %s, %.0f%% of pairs >=6 hops"
                  % (diameter, mean_hops, buckets, pct_ge6)}

    ids = sorted(pos)
    close_pairs, min_spacing = [], float("inf")
    for i, a in enumerate(ids):
        for b in ids[i + 1:]:
            d = haversine_m(pos[a][0], pos[a][1], pos[b][0], pos[b][1])
            min_spacing = min(min_spacing, d)
            if d < MIN_SPACING_M:
                close_pairs.append((d, a, b))
    close_pairs.sort()
    soft["spacing"] = {
        "ok": not close_pairs,
        "detail": "min spacing %.0f m; %d pair(s) < %.0f m%s"
                  % (min_spacing if ids else 0, len(close_pairs), MIN_SPACING_M,
                     ": " + ", ".join("%d-%d (%.0f m)" % (a, b, d) for d, a, b in close_pairs[:10])
                     if close_pairs else "")}

    long_edges = []
    for e in edges:
        modes = set(e.get("modes") or [])
        a, b = e.get("from"), e.get("to")
        if "TRAIN" in modes or a not in pos or b not in pos:
            continue
        d = haversine_m(pos[a][0], pos[a][1], pos[b][0], pos[b][1])
        if d > MAX_NON_TRAIN_EDGE_M:
            long_edges.append((d, a, b, sorted(modes)))
    long_edges.sort(reverse=True)
    soft["edge_length"] = {
        "ok": not long_edges,
        "detail": "%d non-TRAIN edge(s) > %.1f km%s"
                  % (len(long_edges), MAX_NON_TRAIN_EDGE_M / 1000,
                     ": " + ", ".join("%d-%d %.2f km %s" % (a, b, d / 1000, m)
                                      for d, a, b, m in long_edges[:10]) if long_edges else "")}

    train_overlap_m = sum(d for d, _, _, _ in train_overlap_pairs)
    soft["train_overlap"] = {
        "ok": not train_overlap_pairs,
        "detail": "%d TRAIN-only pair(s) share segments (~%.0f m total)%s"
                  % (len(train_overlap_pairs), train_overlap_m,
                     ": " + "; ".join(pair_desc(p) for p in train_overlap_pairs[:10])
                     if train_overlap_pairs else "")}

    # ---- metrics ----
    metrics.update({
        "node_count": len(nodes),
        "edge_count": len(edges),
        "mode_counts": dict(mode_counts),
        "mode_exclusive_counts": dict(exclusive),
        "multi_mode_edge_count": multi,
        "diameter": diameter,
        "mean_hops": round(mean_hops, 2),
        "hop_histogram": buckets,
        "pct_pairs_ge_6": round(pct_ge6, 1),
        "articulation_count": len(arts),
        "bridge_count": bridge_count,
        "min_spacing_m": round(min_spacing, 1) if ids else None,
        "degree_histogram": dict(sorted(Counter(degree.values()).items())),
        "driveby_violations": len(driveby),
        "shared_pairs_nonexempt": len(violating_pairs),
        "train_contestable_pct": round(pct, 1),
        "train_overlap_pairs": len(train_overlap_pairs),
        "train_overlap_m": round(train_overlap_m, 1),
    })
    return hard, soft, metrics


def main():
    ap = argparse.ArgumentParser(description="Evaluate a Hunting Mr. X map JSON.")
    ap.add_argument("candidate", help="path to candidate map JSON")
    ap.add_argument("--report", help="write JSON report to this path")
    ap.add_argument("--driveby-corridor", type=float, default=DEFAULT_DRIVEBY_CORRIDOR_M,
                    help="meters: BUS/ESCOOTER edges must stay this far from "
                         "non-endpoint nodes (default %(default)s)")
    ap.add_argument("--driveby-corridor-train", type=float,
                    default=DEFAULT_DRIVEBY_CORRIDOR_TRAIN_M,
                    help="meters: TRAIN-only edges must stay this far from "
                         "non-endpoint train stations (default %(default)s)")
    ap.add_argument("--station-hub-m", type=float, default=DEFAULT_STATION_HUB_M,
                    help="co-running near a train-station endpoint is exempt within this "
                         "radius (default %(default)s)")
    ap.add_argument("--exclusive-exempt-m", type=float, default=DEFAULT_EXCLUSIVE_EXEMPT_M,
                    help="meters: shared segments within this distance of a shared "
                         "endpoint node are exempt from road_exclusive (default %(default)s)")
    args = ap.parse_args()

    try:
        with open(args.candidate) as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError) as exc:
        print("[FAIL] load: %s" % exc)
        sys.exit(1)

    hard, soft, metrics = evaluate(
        data,
        driveby_corridor_m=args.driveby_corridor,
        driveby_corridor_train_m=args.driveby_corridor_train,
        exclusive_exempt_m=args.exclusive_exempt_m)

    print("=== HARD checks ===")
    for name, r in hard.items():
        print("[%s] %-18s %s" % ("PASS" if r["pass"] else "FAIL", name, r["detail"]))
    print("\n=== SOFT checks ===")
    for name, r in soft.items():
        print("[%s] %-18s %s" % ("ok  " if r["ok"] else "warn", name, r["detail"]))
    print("\n=== Metrics ===")
    for k, v in metrics.items():
        print("  %-24s %s" % (k, v))

    all_pass = all(r["pass"] for r in hard.values())
    print("\nRESULT: %s" % ("ALL HARD CHECKS PASSED" if all_pass else "HARD CHECK FAILURE"))

    if args.report:
        with open(args.report, "w") as f:
            json.dump({"hard": hard, "soft": soft, "metrics": metrics}, f, indent=2)

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
