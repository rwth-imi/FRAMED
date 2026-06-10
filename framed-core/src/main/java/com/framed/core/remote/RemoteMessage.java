package com.framed.core.remote;

/**
 * A decoded message exchanged over a remote transport between event bus instances.
 *
 * @param address the bus address the message is routed to
 * @param payload the message payload
 * @param type    the messaging pattern, e.g. {@code "send"} (single handler) or
 *                {@code "publish"} (all handlers)
 */
public record RemoteMessage(String address, Object payload, String type) {}
