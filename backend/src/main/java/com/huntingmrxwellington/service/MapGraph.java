package com.huntingmrxwellington.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.huntingmrxwellington.dto.ValidMoveDTO;
import com.huntingmrxwellington.model.TicketType;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class MapGraph {

    @Value("${game.map-file:test-map.json}")
    private String mapFile;

    private record GraphEdge(int to, Set<TicketType> modes) {}

    // fromNodeId -> list of outgoing edges (undirected: stored in both directions)
    private Map<Integer, List<GraphEdge>> adjacency;
    private Set<Integer> nodeIds;

    @PostConstruct
    public void load() throws Exception {
        adjacency = new HashMap<>();
        nodeIds = new HashSet<>();

        ObjectMapper mapper = new ObjectMapper();
        ClassPathResource res = new ClassPathResource("static/" + mapFile);
        try (InputStream is = res.getInputStream()) {
            JsonNode root = mapper.readTree(is);

            for (JsonNode n : root.get("nodes")) {
                int id = n.get("id").asInt();
                nodeIds.add(id);
                adjacency.put(id, new ArrayList<>());
            }

            for (JsonNode e : root.get("edges")) {
                int from = e.get("from").asInt();
                int to   = e.get("to").asInt();
                Set<TicketType> modes = new HashSet<>();
                for (JsonNode m : e.get("modes")) {
                    try { modes.add(TicketType.valueOf(m.asText())); }
                    catch (IllegalArgumentException ignored) {}
                }
                if (modes.isEmpty()) continue;
                adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(new GraphEdge(to, modes));
                adjacency.computeIfAbsent(to,   k -> new ArrayList<>()).add(new GraphEdge(from, modes));
            }
        }
    }

    public Set<Integer> getNodeIds() {
        return Collections.unmodifiableSet(nodeIds);
    }

    public boolean isAdjacent(int from, int to) {
        return adjacency.getOrDefault(from, List.of())
                .stream().anyMatch(e -> e.to() == to);
    }

    public Set<TicketType> getEdgeModes(int from, int to) {
        return adjacency.getOrDefault(from, List.of()).stream()
                .filter(e -> e.to() == to)
                .map(GraphEdge::modes)
                .findFirst()
                .orElse(Set.of());
    }

    /**
     * Returns all valid moves from {@code fromNodeId} given the player's tickets.
     *
     * @param isMrX            true for Mr X (can use BLACK and DOUBLE)
     * @param doubleMovePending true when Mr X is submitting the second leg of a double move
     * @param blockedNodes      nodes the mover cannot move to (detectives' positions for Mr X)
     */
    public List<ValidMoveDTO> validMoves(int fromNodeId,
                                         Map<TicketType, Integer> tickets,
                                         boolean isMrX,
                                         boolean doubleMovePending,
                                         Set<Integer> blockedNodes) {
        List<ValidMoveDTO> result = new ArrayList<>();

        for (GraphEdge edge : adjacency.getOrDefault(fromNodeId, List.of())) {
            if (blockedNodes.contains(edge.to())) continue;

            Set<String> opts = new LinkedHashSet<>();

            // Transport tickets matching edge modes
            for (TicketType mode : edge.modes()) {
                Integer count = tickets.getOrDefault(mode, 0);
                if (count != null && count != 0) { // -1 = unlimited, >0 = has tickets
                    opts.add(mode.name());
                }
            }

            // Mr X: BLACK can be used on any edge
            if (isMrX) {
                int black = tickets.getOrDefault(TicketType.BLACK, 0);
                if (black > 0) opts.add("BLACK");
            }

            if (opts.isEmpty()) continue; // can't reach this node at all

            result.add(new ValidMoveDTO(edge.to(), new ArrayList<>(opts)));
        }

        return result;
    }

    /**
     * Returns all node IDs reachable from {@code fromNodeId} using any ticket,
     * restricted to nodes NOT in {@code blockedNodes}. Used for DOUBLE first-leg
     * adjacency checks (any adjacent, non-blocked node is valid).
     */
    public boolean isReachableForDouble(int fromNodeId, int toNodeId, Set<Integer> blockedNodes) {
        if (blockedNodes.contains(toNodeId)) return false;
        return isAdjacent(fromNodeId, toNodeId);
    }

    /** Pick {@code count} random distinct node IDs from the graph. */
    public List<Integer> randomNodes(int count, Random rng) {
        List<Integer> all = new ArrayList<>(nodeIds);
        Collections.shuffle(all, rng);
        return all.subList(0, Math.min(count, all.size()));
    }
}
