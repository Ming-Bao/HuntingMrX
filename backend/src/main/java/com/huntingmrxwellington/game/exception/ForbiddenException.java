package com.huntingmrxwellington.game.exception;

/** The caller isn't allowed to do this: an unknown token, moving out of turn, or a
 *  non-host starting the game or kicking. Sent as HTTP 403. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
