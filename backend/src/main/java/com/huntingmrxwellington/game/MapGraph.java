package com.huntingmrxwellington.game;

import com.huntingmrxwellington.game.enums.TicketType;
import com.huntingmrxwellington.game.view.ValidMove;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The static board, parsed once from the map JSON. Edges are undirected and modes on a
 *  repeated node pair are merged. An unknown mode or node fails the load instead of
 *  silently dropping the edge. */
public final class MapGraph {

    /** The tickets an edge can carry. BLACK and DOUBLE aren't transport modes. */
    private static final Set<TicketType> EDGE_MODES =
            EnumSet.of(TicketType.ESCOOTER, TicketType.BUS, TicketType.TRAIN, TicketType.FERRY);

    private final byte[] json;
    private final List<Integer> nodeIds;
    private final Map<Integer, Map<Integer, Set<TicketType>>> neighbours; // node -> neighbour -> modes

    private MapGraph(byte[] json, List<Integer> nodeIds, Map<Integer, Map<Integer, Set<TicketType>>> neighbours) {
        this.json = json;
        this.nodeIds = nodeIds;
        this.neighbours = neighbours;
    }

    /**
     * Reads the board from map JSON: {"nodes": [{"id": ...}], "edges": [{"from", "to", "modes"}]}.
     *
     * @param json the raw map file
     * @return the parsed board
     * @throws IllegalStateException if an edge names an unknown node or mode
     */
    public static MapGraph parse(byte[] json) {
        JsonNode root = new ObjectMapper().readTree(new String(json, StandardCharsets.UTF_8));
        List<Integer> ids = new ArrayList<>();
        Map<Integer, Map<Integer, Set<TicketType>>> neighbours = new HashMap<>();
        for (JsonNode node : root.get("nodes")) {
            int id = node.get("id").asInt();
            ids.add(id);
            neighbours.put(id, new HashMap<>());
        }
        for (JsonNode edge : root.get("edges")) {
            int from = edge.get("from").asInt();
            int to = edge.get("to").asInt();
            if (!neighbours.containsKey(from) || !neighbours.containsKey(to))
                throw new IllegalStateException("Edge " + from + "-" + to + " uses a node that isn't in the map");
            for (JsonNode mode : edge.get("modes")) {
                TicketType ticket = edgeMode(mode.asString(), from, to);
                neighbours.get(from).computeIfAbsent(to, k -> EnumSet.noneOf(TicketType.class)).add(ticket);
                neighbours.get(to).computeIfAbsent(from, k -> EnumSet.noneOf(TicketType.class)).add(ticket);
            }
        }
        return new MapGraph(json, List.copyOf(ids), neighbours);
    }

    /**
     * The ticket for an edge's mode name.
     *
     * @param name the mode name from the map file
     * @param from one end of the edge, for the error message
     * @param to the other end of the edge, for the error message
     * @return the matching transport ticket
     * @throws IllegalStateException if the name isn't a transport mode
     */
    private static TicketType edgeMode(String name, int from, int to) {
        for (TicketType t : EDGE_MODES) if (t.name().equals(name)) return t;
        throw new IllegalStateException("Edge " + from + "-" + to + " has unknown mode " + name);
    }

    /** @return the raw map JSON, served as-is to the frontend; callers must not modify it */
    public byte[] json() {
        return json;
    }

    /** @return every node id on the board, in file order */
    public List<Integer> nodeIds() {
        return nodeIds;
    }

    /**
     * The transport modes on the edge between two nodes.
     *
     * @param a one node
     * @param b the other node
     * @return the modes, or an empty set if the nodes aren't adjacent
     */
    public Set<TicketType> modesBetween(int a, int b) {
        return Collections.unmodifiableSet(neighbours.getOrDefault(a, Map.of()).getOrDefault(b, Set.of()));
    }

    /**
     * Every node the player can reach in one leg, with the tickets that would pay for it:
     * matching transport tickets they hold, plus Invisible (BLACK) on any edge if they hold one.
     *
     * @param player the player moving
     * @param blocked nodes the player may not move to
     * @return one entry per reachable node, sorted by node id
     */
    public List<ValidMove> validMoves(Player player, Set<Integer> blocked) {
        List<ValidMove> moves = new ArrayList<>();
        neighbours.getOrDefault(player.node(), Map.of()).forEach((to, modes) -> {
            if (blocked.contains(to)) return;
            List<TicketType> options = new ArrayList<>();
            for (TicketType mode : modes) if (player.has(mode)) options.add(mode);
            if (player.has(TicketType.BLACK)) options.add(TicketType.BLACK);
            if (!options.isEmpty()) moves.add(new ValidMove(to, List.copyOf(options)));
        });
        moves.sort(Comparator.comparingInt(ValidMove::nodeId));
        return moves;
    }
}
