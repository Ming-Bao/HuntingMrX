package com.huntingmrxwellington.controller;

import com.huntingmrxwellington.game.exception.ConflictException;
import com.huntingmrxwellington.game.exception.ForbiddenException;
import com.huntingmrxwellington.game.exception.NotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps errors to HTTP statuses, each with an {"error": "..."} body, in one place. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 404: no such game or player.
     *
     * @param e the error thrown
     * @return a 404 with the error's message
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * 403: the caller may not do this.
     *
     * @param e the error thrown
     * @return a 403 with the error's message
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * 409: the game is in the wrong state for this.
     *
     * @param e the error thrown
     * @return a 409 with the error's message
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * 400: bad input, such as an illegal move or a blank name.
     *
     * @param e the error thrown
     * @return a 400 with the error's message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 400: a missing, malformed or wrongly typed JSON body.
     *
     * @param e the parse error, whose message isn't shown
     * @return a 400 saying the body is malformed
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    /**
     * An error response.
     *
     * @param status the HTTP status
     * @param message the text for the body
     * @return a response with that status and an {"error": message} body
     */
    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
