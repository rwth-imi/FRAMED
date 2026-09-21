package com.framed.interop.hl7.mllp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * MLLP client (the message initiator): connects to a remote MLLP endpoint, sends a framed HL7
 * message and reads back the framed acknowledgement.
 *
 * <p>The connection is opened lazily and reused; on any I/O error it is dropped so the next call
 * reconnects. Instances are synchronised so a single client can be shared across threads.</p>
 */
public final class MllpClient implements Closeable {

  private final String host;
  private final int port;
  private final int timeoutMs;

  private Socket socket;
  private OutputStream out;
  private InputStream in;

  /**
   * @param host      target host
   * @param port      target port
   * @param timeoutMs connect and read timeout in milliseconds
   */
  public MllpClient(String host, int port, int timeoutMs) {
    this.host = host;
    this.port = port;
    this.timeoutMs = timeoutMs;
  }

  /**
   * Sends an HL7 message and returns the acknowledgement message.
   *
   * @param message the HL7 message to send
   * @return the acknowledgement (ACK) message text
   * @throws IOException if the connection fails, times out, or the peer closes before replying
   */
  public synchronized String sendAndReceive(String message) throws IOException {
    try {
      ensureConnected();
      out.write(MllpCodec.frame(message));
      out.flush();
      String ack = MllpCodec.readMessage(in);
      if (ack == null) {
        throw new IOException("MLLP peer closed before sending an acknowledgement");
      }
      return ack;
    } catch (IOException e) {
      closeQuietly();
      throw e;
    }
  }

  private void ensureConnected() throws IOException {
    if (socket != null && socket.isConnected() && !socket.isClosed()) {
      return;
    }
    Socket s = new Socket();
    s.connect(new InetSocketAddress(host, port), timeoutMs);
    s.setSoTimeout(timeoutMs);
    this.socket = s;
    this.out = s.getOutputStream();
    this.in = s.getInputStream();
  }

  private void closeQuietly() {
    Socket s = this.socket;
    this.socket = null;
    this.in = null;
    this.out = null;
    if (s != null) {
      try {
        s.close();
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  @Override
  public synchronized void close() {
    closeQuietly();
  }
}
