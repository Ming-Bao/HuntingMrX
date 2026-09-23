package com.huntingmrxwellington.controller;

import com.huntingmrxwellington.game.MapGraph;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the board JSON that MapGraph read once at startup. */
@RestController
@RequestMapping("/api/map")
public class MapController {

    private final MapGraph map;

    public MapController(MapGraph map) {
        this.map = map;
    }

    /** @return the raw map JSON: nodes with coordinates, and edges with their transport modes */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] getMap() {
        return map.json();
    }
}
