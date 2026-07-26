package com.huntingmrxwellington.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/map")
public class MapController {

    @Value("classpath:static/${game.map-file:test-map.json}")
    private Resource mapResource;

    @GetMapping
    public ResponseEntity<byte[]> getMap() throws IOException {
        byte[] bytes = mapResource.getInputStream().readAllBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(bytes);
    }
}
