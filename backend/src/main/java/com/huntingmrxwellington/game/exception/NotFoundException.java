package com.huntingmrxwellington.game.exception;

/** No game with that id or join code, or no player with that id. Sent as HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
