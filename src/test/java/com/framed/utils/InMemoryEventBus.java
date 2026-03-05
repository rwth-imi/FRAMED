package com.framed.utils;

import com.framed.core.EventBus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Minimal in-memory EventBus for tests.
 * Adjust signatures if your EventBus differs.
 */
public class InMemoryEventBus implements EventBus {

    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();
    private final List<Published> published = Collections.synchronizedList(new ArrayList<>());

    public record Published(String channel, Object message) {}

    @Override
    public void register(String channel, Consumer<Object> handler) {
        subscribers.computeIfAbsent(channel, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(handler);
    }

    @Override
    public void send(String address, Object message) {
        // Not implemented
    }

    @Override
    public void publish(String channel, Object message) {
        published.add(new Published(channel, message));
        List<Consumer<Object>> subs = subscribers.get(channel);
        if (subs != null) {
            // Deliver synchronously (as your Actor expects)
            for (Consumer<Object> c : List.copyOf(subs)) {
                c.accept(message);
            }
        }
    }

    @Override
    public void shutdown() {
        // Not implemented
    }

    public List<Published> getPublished() {
        return published;
    }

    public void clearPublished() {
        published.clear();
    }
}