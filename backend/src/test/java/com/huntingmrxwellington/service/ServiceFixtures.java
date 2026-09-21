package com.huntingmrxwellington.service;

import com.huntingmrxwellington.config.GameSettings;

import java.util.Random;

final class ServiceFixtures {

    /** Same idle limit as application.properties; 30 of each detective ticket so scripted games never run out. */
    static final GameSettings SETTINGS = new GameSettings("test-map.json", 900, 30, 30, 30, 30);

    /** Always picks the current position, so Collections.shuffle leaves lists as they are (see its
     *  Javadoc): the host becomes Mr X and players start on nodes 1, 2, 3 ... in join order.
     *  Every join code comes out as "999999". */
    static final Random NO_SHUFFLE = new Random() {
        @Override
        public int nextInt(int bound) {
            return bound - 1;
        }
    };

    private ServiceFixtures() {}
}
