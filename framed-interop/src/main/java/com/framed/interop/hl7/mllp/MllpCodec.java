package com.framed.interop.hl7.mllp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Lower Layer Protocol (MLLP) framing.
 *
 * <p>An HL7 message is wrapped as {@code <VT> message <FS><CR>}, i.e. a start-block byte
 * ({@code 0x0B}), the message bytes, an end-block byte ({@code 0x1C}) and a carriage return
 * ({@code 0x0D}). This is the de-facto transport framing for HL7 v2 over TCP.</p>
 */
public final class MllpCodec {

  /** Start-of-block marker (vertical tab, {@code 0x0B}). */
  public static final int START_BLOCK = 0x0B;
  /** End-of-block marker (file separator, {@code 0x1C}). */
  public static final int END_BLOCK = 0x1C;
  /** Trailing carriage return ({@code 0x0D}). */
  public static final int CARRIAGE_RETURN = 0x0D;

  private MllpCodec() {}

  /**
   * Frames an HL7 message for MLLP transport.
   *
   * @param message the HL7 message text
   * @return the MLLP-framed bytes
   */
  public static byte[] frame(String message) {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[body.length + 3];
    out[0] = (byte) START_BLOCK;
    System.arraycopy(body, 0, out, 1, body.length);
    out[body.length + 1] = (byte) END_BLOCK;
    out[body.length + 2] = (byte) CARRIAGE_RETURN;
    return out;
  }

  /**
   * Reads a single MLLP-framed message from a stream, blocking until a full frame arrives.
   *
   * <p>Leading bytes before {@link #START_BLOCK} are skipped. The terminating {@link #END_BLOCK}
   * (and the trailing {@link #CARRIAGE_RETURN}, if present) are consumed but not returned. Tolerant
   * of fragmented reads, since the stream is consumed one byte at a time until the frame completes.</p>
   *
   * @param in the input stream
   * @return the message text, or {@code null} on clean EOF / truncated frame (connection closed)
   * @throws IOException if the underlying stream fails
   */
  public static String readMessage(InputStream in) throws IOException {
    int b;
    while ((b = in.read()) != START_BLOCK) {
      if (b == -1) {
        return null;
      }
    }
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    while (true) {
      b = in.read();
      if (b == -1) {
        return null; // truncated frame: peer closed mid-message
      }
      if (b == END_BLOCK) {
        // Consume the trailing CR if it is there; tolerate its absence.
        int cr = in.read();
        if (cr != CARRIAGE_RETURN && cr != -1) {
          // Not a CR: it belongs to the next frame's content; but per MLLP this is malformed.
          // We still return the message we have; the stray byte is dropped.
        }
        return buf.toString(StandardCharsets.UTF_8);
      }
      buf.write(b);
    }
  }
}
