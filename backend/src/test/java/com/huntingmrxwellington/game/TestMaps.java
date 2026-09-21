package com.huntingmrxwellington.game;

import java.nio.charset.StandardCharsets;

/** A small hand-drawn board for tests:
 *
 *    1 -BUS/ESCOOTER- 2 -ESCOOTER- 3 -TRAIN- 4 -BUS- 5
 *    |                |                               |
 *    |               BUS                              |
 *    |                6 -ESCOOTER- 7                  |
 *    +------------------ FERRY -----------------------+
 *
 *  Node 7 is a dead end: a detective on 6 boxes in Mr X on 7. */
public final class TestMaps {

    public static final String SMALL = """
            {"nodes": [{"id": 1}, {"id": 2}, {"id": 3}, {"id": 4}, {"id": 5}, {"id": 6}, {"id": 7}],
             "edges": [
               {"from": 1, "to": 2, "modes": ["BUS", "ESCOOTER"]},
               {"from": 2, "to": 3, "modes": ["ESCOOTER"]},
               {"from": 3, "to": 4, "modes": ["TRAIN"]},
               {"from": 4, "to": 5, "modes": ["BUS"]},
               {"from": 5, "to": 1, "modes": ["FERRY"]},
               {"from": 2, "to": 6, "modes": ["BUS"]},
               {"from": 6, "to": 7, "modes": ["ESCOOTER"]}]}
            """;

    private TestMaps() {}

    public static MapGraph small() {
        return MapGraph.parse(SMALL.getBytes(StandardCharsets.UTF_8));
    }
}
