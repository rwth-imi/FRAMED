package com.framed.communicator.driver.protocol.mimic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Decoding tests for {@link WfdbSegmentReader} covering formats 80, 16 and 212. */
class WfdbSegmentReaderTest {

    @TempDir
    Path dir;

    private static WfdbSignal sig(String file, int format) {
        return new WfdbSignal(file, format, 0, 1.0, 0, "adu", "s");
    }

    @Test
    void decodesFormat80OffsetBinaryInterleaved() throws IOException {
        // 2 signals, 2 frames: frame0=[0,1], frame1=[2,-2]  (stored as byte+128)
        Path dat = dir.resolve("f80.dat");
        Files.write(dat, new byte[]{(byte) 128, (byte) 129, (byte) 130, (byte) 126});

        WfdbSegmentReader r = WfdbSegmentReader.open(dat, List.of(sig("f80.dat", 80), sig("f80.dat", 80)), 0);
        assertEquals(2, r.numSignals());
        assertEquals(2, r.numFrames());
        assertEquals(0, r.adc(0, 0));
        assertEquals(1, r.adc(1, 0));
        assertEquals(2, r.adc(0, 1));
        assertEquals(-2, r.adc(1, 1));
    }

    @Test
    void decodesFormat16LittleEndianSigned() throws IOException {
        // 2 signals, 1 frame: sig0=300 (0x012C -> 2C 01), sig1=-1 (FF FF)
        Path dat = dir.resolve("f16.dat");
        Files.write(dat, new byte[]{(byte) 0x2C, (byte) 0x01, (byte) 0xFF, (byte) 0xFF});

        WfdbSegmentReader r = WfdbSegmentReader.open(dat, List.of(sig("f16.dat", 16), sig("f16.dat", 16)), 0);
        assertEquals(1, r.numFrames());
        assertEquals(300, r.adc(0, 0));
        assertEquals(-1, r.adc(1, 0));
    }

    @Test
    void decodesFormat212PackedTwelveBit() throws IOException {
        // 2 signals, 1 frame: sample0=1, sample1=-1 (0xFFF) packed into 3 bytes.
        Path dat = dir.resolve("f212.dat");
        Files.write(dat, new byte[]{(byte) 0x01, (byte) 0xF0, (byte) 0xFF});

        WfdbSegmentReader r = WfdbSegmentReader.open(dat, List.of(sig("f212.dat", 212), sig("f212.dat", 212)), 0);
        assertEquals(1, r.numFrames());
        assertEquals(1, r.adc(0, 0));
        assertEquals(-1, r.adc(1, 0));
    }

    @Test
    void rejectsUnsupportedFormat() throws IOException {
        Path dat = dir.resolve("f8.dat");
        Files.write(dat, new byte[]{0, 0});
        assertThrows(UnsupportedOperationException.class,
                () -> WfdbSegmentReader.open(dat, List.of(sig("f8.dat", 8)), 0));
    }
}