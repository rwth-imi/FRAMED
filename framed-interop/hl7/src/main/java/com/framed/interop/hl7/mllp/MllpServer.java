package com.framed.interop.hl7.mllp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MLLP server (the message responder): listens for connections, reads each framed HL7 message and
 * replies with the acknowledgement produced by the supplied handler.
 *
 * <p>Each connection is serviced on its own thread and may carry multiple messages. The accept loop
 * runs on a daemon thread started in the constructor.</p>
 */
public final class MllpServer implements Closeable {

  private static final Logger LOGGER = Logger.getLogger(MllpServer.class.getName());

  private final ServerSocket serverSocket;
  private final ExecutorService connections;
  private final UnaryOperator<String> handler;
  private final Thread acceptThread;

  private volatile boolean running = true;

  /**
   * Binds and starts accepting connections immediately.
   *
   * @param port    the port to bind ({@code 0} for an ephemeral port; see {@link #getPort()})
   * @param handler maps a received HL7 message to the acknowledgement to send back
   * @throws IOException if the socket cannot be bound
   */
  public MllpServer(int port, UnaryOperator<String> handler) throws IOException {
    this.serverSocket = new ServerSocket(port);
    this.handler = handler;
    AtomicInteger n = new AtomicInteger();
    this.connections = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "mllp-conn-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
    this.acceptThread = new Thread(this::acceptLoop, "mllp-accept");
    this.acceptThread.setDaemon(true);
    this.acceptThread.start();
  }

  /** @return the actual bound port (useful when constructed with port 0). */
  public int getPort() {
    return serverSocket.getLocalPort();
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket socket = serverSocket.accept();
        connections.submit(() -> serve(socket));
      } catch (IOException e) {
        if (running) {
          LOGGER.log(Level.WARNING, "MLLP accept failed", e);
        }
      }
    }
  }

  private void serve(Socket socket) {
    try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
      String message;
      while ((message = MllpCodec.readMessage(in)) != null) {
        String ack = handler.apply(message);
        if (ack != null) {
          out.write(MllpCodec.frame(ack));
          out.flush();
        }
      }
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "MLLP connection ended", e);
    }
  }

  @Override
  public void close() {
    running = false;
    try {
      serverSocket.close();
    } catch (IOException ignored) {
      // best effort
    }
    connections.shutdownNow();
  }
}
