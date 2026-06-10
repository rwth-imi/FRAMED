package com.framed.core.remote;

/**
 * Represents a network peer identified by a host (name or IP address) and a port.
 *
 * <p>This record is typically used to encapsulate connection details for remote communication
 * between services, JVMs, or devices.</p>
 *
 * @param host the hostname or IP address of the peer
 * @param port the * @param port the port number of the peer
 */
public record Peer(String host, int port) {
}
