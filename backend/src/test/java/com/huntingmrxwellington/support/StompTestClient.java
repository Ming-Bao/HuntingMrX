package com.huntingmrxwellington.support;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A real STOMP-over-SockJS client for tests, connected the way the frontend connects. */
public final class StompTestClient implements AutoCloseable {

    private static final String PROBE = "__probe__";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebSocketStompClient client = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
    private final SimpMessagingTemplate server;
    private final StompSession session;

    /** server is the app's own messaging template, used to probe new subscriptions. */
    public StompTestClient(int port, SimpMessagingTemplate server) throws Exception {
        this.server = server;
        this.session = client.connectAsync("http://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
    }

    /** Subscribes, and returns once the broker is delivering to it. The simple broker sends no
     *  RECEIPT for SUBSCRIBE, so this publishes probes to the topic until one arrives. */
    public Inbox subscribe(String topic) throws InterruptedException {
        Inbox inbox = new Inbox();
        session.subscribe(topic, inbox);
        for (int attempt = 0; attempt < 50; attempt++) {
            server.convertAndSend(topic, PROBE);
            if (inbox.probed.await(100, TimeUnit.MILLISECONDS)) return inbox;
        }
        throw new AssertionError("Subscription to " + topic + " never became active");
    }

    /** Sends a raw STOMP SEND frame, as a misbehaving client could. */
    public void send(String destination, String body) {
        session.send(destination, body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        session.disconnect();
        client.stop();
    }

    /** The messages that arrive on one subscription, parsed as JSON. Probes are dropped. */
    public static final class Inbox implements StompFrameHandler {

        private final BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
        private final CountDownLatch probed = new CountDownLatch(1);

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String body = new String((byte[]) payload, StandardCharsets.UTF_8);
            if (body.equals(PROBE)) probed.countDown();
            else bodies.add(body);
        }

        /** The next message, or null if none arrives within the timeout. */
        public JsonNode poll(long millis) throws InterruptedException {
            String body = bodies.poll(millis, TimeUnit.MILLISECONDS);
            return body == null ? null : JSON.readTree(body);
        }

        /** The next message; fails the test if none arrives within 5 s. */
        public JsonNode next() throws InterruptedException {
            JsonNode message = poll(5_000);
            if (message == null) throw new AssertionError("No STOMP message within 5 s");
            return message;
        }

        /** Skips messages until one matches; fails the test if none does within 5 s. */
        public JsonNode awaitMatching(Predicate<JsonNode> matches) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode message = poll(Math.max(1, deadline - System.currentTimeMillis()));
                if (message != null && matches.test(message)) return message;
            }
            throw new AssertionError("No matching STOMP message within 5 s");
        }
    }
}
