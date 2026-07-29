package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broadcasts server-initiated MCP notifications (currently sing-box log lines as
 * {@code notifications/message}) to every connected SSE stream.
 *
 * <p>Each subscriber owns a bounded queue; a slow or dead client can only fall
 * behind by {@link #QUEUE_CAPACITY} messages before further ones are dropped for
 * that client — it never blocks the producer or other clients.</p>
 */
public class McpNotifier {

    private static final Logger log = LoggerFactory.getLogger(McpNotifier.class);
    private static final int QUEUE_CAPACITY = 1000;

    private final ObjectMapper mapper;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public McpNotifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A single SSE stream's outbound queue. */
    public static final class Subscriber {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

        /**
         * Waits up to {@code timeoutMs} for the next frame to write.
         *
         * @param timeoutMs the maximum time to wait, in milliseconds
         * @return the next frame to write, or {@code null} if none arrived in time
         * @throws InterruptedException if interrupted while waiting
         */
        public String poll(long timeoutMs) throws InterruptedException {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Registers a new SSE stream and returns its subscriber handle.
     *
     * @return the newly registered subscriber
     */
    public Subscriber subscribe() {
        Subscriber subscriber = new Subscriber();
        subscribers.add(subscriber);
        return subscriber;
    }

    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** Broadcasts one log line as an MCP {@code notifications/message}. */
    public void broadcastLog(String line) {
        if (subscribers.isEmpty()) {
            return;
        }
        String frame = logFrame(line);
        if (frame == null) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            // offer (non-blocking): drop for a client that has fallen too far behind.
            subscriber.queue.offer(frame);
        }
    }

    private String logFrame(String line) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("level", "info");
            params.put("logger", "sing-box");
            params.put("data", line);
            ObjectNode message = mapper.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("method", "notifications/message");
            message.set("params", params);
            return mapper.writeValueAsString(message);
        } catch (Exception e) {
            log.debug("Failed to build log notification frame: {}", e.getMessage());
            return null;
        }
    }
}
