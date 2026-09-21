package com.huntingmrxwellington.game;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.huntingmrxwellington.game.TicketType.*;
import static org.assertj.core.api.Assertions.*;

class MapGraphTest {

    final MapGraph small = TestMaps.small();

    static MapGraph parse(String json) {
        return MapGraph.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    static Player placed(Role role, int node, Map<TicketType, Integer> tickets) {
        Player p = new Player("P");
        p.assign(role, node, tickets);
        return p;
    }

    @Test
    void readsNodesAndUndirectedEdges() {
        assertThat(small.nodeIds()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(small.modesBetween(1, 2)).containsExactlyInAnyOrder(BUS, ESCOOTER);
        assertThat(small.modesBetween(2, 1)).containsExactlyInAnyOrder(BUS, ESCOOTER);
        assertThat(small.modesBetween(1, 3)).isEmpty();
    }

    @Test
    void modesOnARepeatedNodePairAreMerged() {
        MapGraph g = parse("""
                {"nodes": [{"id": 1}, {"id": 2}],
                 "edges": [{"from": 1, "to": 2, "modes": ["BUS"]}, {"from": 2, "to": 1, "modes": ["TRAIN"]}]}""");
        assertThat(g.modesBetween(1, 2)).containsExactlyInAnyOrder(BUS, TRAIN);
    }

    @Test
    void anUnknownModeFailsTheLoad() {
        assertThatThrownBy(() -> parse("""
                {"nodes": [{"id": 1}, {"id": 2}], "edges": [{"from": 1, "to": 2, "modes": ["TAXI"]}]}"""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TAXI");
    }

    @Test
    void invisibleAndDoubleAreNotEdgeModes() {
        assertThatThrownBy(() -> parse("""
                {"nodes": [{"id": 1}, {"id": 2}], "edges": [{"from": 1, "to": 2, "modes": ["BLACK"]}]}"""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEdgeToAMissingNodeFailsTheLoad() {
        assertThatThrownBy(() -> parse("""
                {"nodes": [{"id": 1}], "edges": [{"from": 1, "to": 9, "modes": ["BUS"]}]}"""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1-9");
    }

    @Test
    void validMovesOfferMatchingTicketsPlusInvisible() {
        Player mrX = placed(Role.MR_X, 1, Map.of(ESCOOTER, -1, BUS, -1, TRAIN, -1, FERRY, -1, BLACK, 1));
        assertThat(small.validMoves(mrX, Set.of())).containsExactly(
                new ValidMove(2, List.of(ESCOOTER, BUS, BLACK)),
                new ValidMove(5, List.of(FERRY, BLACK)));
    }

    @Test
    void blockedNodesAndUsedUpTicketsAreLeftOut() {
        Player detective = placed(Role.DETECTIVE, 2, Map.of(ESCOOTER, 0, BUS, 3, TRAIN, 0, FERRY, 0));
        assertThat(small.validMoves(detective, Set.of(6))).containsExactly(new ValidMove(1, List.of(BUS)));
    }

    @Test
    void validMovesAreSortedByNodeId() {
        MapGraph g = parse("""
                {"nodes": [{"id": 1}, {"id": 100}, {"id": 3}, {"id": 50}],
                 "edges": [{"from": 1, "to": 100, "modes": ["BUS"]}, {"from": 1, "to": 3, "modes": ["BUS"]},
                           {"from": 1, "to": 50, "modes": ["BUS"]}]}""");
        Player p = placed(Role.DETECTIVE, 1, Map.of(BUS, 5));
        assertThat(g.validMoves(p, Set.of())).extracting(ValidMove::nodeId).containsExactly(3, 50, 100);
    }

    @Test
    void theShippedMapsLoad() throws Exception {
        for (String file : List.of("map.json", "test-map.json")) {
            byte[] json = new ClassPathResource("static/" + file).getContentAsByteArray();
            MapGraph g = MapGraph.parse(json);
            assertThat(g.nodeIds()).isNotEmpty();
            assertThat(g.json()).isEqualTo(json);
        }
    }
}
