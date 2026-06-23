package com.framed.interop.hl7.mllp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MllpCodecTest {

  @Test
  void framesWithStartEndAndCarriageReturn() {
    byte[] framed = MllpCodec.frame("HELLO");
    assertEquals(MllpCodec.START_BLOCK, framed[0] & 0xFF);
    assertEquals(MllpCodec.END_BLOCK, framed[framed.length - 2] & 0xFF);
    assertEquals(MllpCodec.CARRIAGE_RETURN, framed[framed.length - 1] & 0xFF);
  }

  @Test
  void readMessageRoundTrips() throws IOException {
    String msg = "MSH|^~\\&|A|B|C|D|20260101000000||ORU^R01|1|P|2.5\rOBX|1|NM|x||5||||||F\r";
    String read = MllpCodec.readMessage(new ByteArrayInputStream(MllpCodec.frame(msg)));
    assertEquals(msg, read);
  }

  @Test
  void readsTwoMessagesFromOneStream() throws IOException {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    stream.writeBytes(MllpCodec.frame("first"));
    stream.writeBytes(MllpCodec.frame("second"));
    ByteArrayInputStream in = new ByteArrayInputStream(stream.toByteArray());

    assertEquals("first", MllpCodec.readMessage(in));
    assertEquals("second", MllpCodec.readMessage(in));
    assertNull(MllpCodec.readMessage(in), "stream is exhausted");
  }

  @Test
  void skipsLeadingNoiseBeforeStartBlock() throws IOException {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    stream.writeBytes(new byte[] {0x00, 0x20, 0x20}); // junk before the frame
    stream.writeBytes(MllpCodec.frame("payload"));
    assertEquals("payload", MllpCodec.readMessage(new ByteArrayInputStream(stream.toByteArray())));
  }

  @Test
  void returnsNullOnEmptyStream() throws IOException {
    assertNull(MllpCodec.readMessage(new ByteArrayInputStream(new byte[0])));
  }

  @Test
  void frameAndReadAreInverse() throws IOException {
    String original = "café ünïcode ✓"; // exercises UTF-8
    byte[] framed = MllpCodec.frame(original);
    assertEquals(original, MllpCodec.readMessage(new ByteArrayInputStream(framed)));
    // round-trip the bytes too
    assertArrayEquals(framed, MllpCodec.frame(MllpCodec.readMessage(new ByteArrayInputStream(framed))));
  }
}
