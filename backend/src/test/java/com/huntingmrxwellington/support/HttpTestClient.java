package com.huntingmrxwellington.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** JSON over HTTP against a running test server, using the JDK's HttpClient. */
public final class HttpTestClient {

    public static final String TOKEN_HEADER = "X-Player-Token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;

    public HttpTestClient(int port) {
        this.base = "http://localhost:" + port;
    }

    public record Response(int status, JsonNode body) {}

    /** Sends the request; token and body may be null. */
    public Response call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (token != null) request.header(TOKEN_HEADER, token);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body().isBlank() ? null : JSON.readTree(response.body()));
    }

    /** Like call, but fails the test unless the response is 2xx; returns the body. */
    public JsonNode ok(String method, String path, String token, Object body) throws Exception {
        Response response = call(method, path, token, body);
        if (response.status() / 100 != 2)
            throw new AssertionError(method + " " + path + " returned " + response.status() + ": " + response.body());
        return response.body();
    }
}
