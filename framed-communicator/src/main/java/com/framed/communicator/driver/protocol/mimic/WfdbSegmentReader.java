package com.framed.communicator.driver.protocol.mimic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Decodes the raw ADC samples of a single WFDB segment from its {@code .dat} file.
 *
 * <p>WFDB stores samples <em>frame-interleaved</em>: for {@code n} signals the on-disk sample
 * sequence is {@code sig0[0], sig1[0], …, sig(n-1)[0], sig0[1], …}. The flat sample index of
 * signal {@code s} in frame {@code f} is therefore {@code k = f*n + s}. This reader supports the
 * three formats occurring in the MIMIC-III Waveform Database:</p>
 *
 * <ul>
 *   <li><b>80</b> &mdash; 8-bit offset binary; one byte per sample, {@code adc = byte - 128}.</li>
 *   <li><b>16</b> &mdash; 16-bit little-endian two's complement; two bytes per sample.</li>
 *   <li><b>212</b> &mdash; two 12-bit two's-complement samples packed into three bytes.</li>
 * </ul>
 *
 * <p>The whole segment file is held in memory; segments in this database are individually small
 * enough (tens of MB at most) for this to be acceptable and it keeps random frame access simple.
 * All signals of a segment are expected to share one {@code .dat} file and one format, as MIMIC-III
 * segments do.</p>
 */
public final class WfdbSegmentReader {

    private final byte[] data;
    private final int numSignals;
    private final int format;
    private final long numFrames;
    private final int byteOffset;

    private WfdbSegmentReader(byte[] data, int numSignals, int format, int byteOffset, long numFrames) {
        this.data = data;
        this.numSignals = numSignals;
        this.format = format;
        this.byteOffset = byteOffset;
        this.numFrames = numFrames;
    }

    /**
     * Opens the {@code .dat} file for a segment described by {@code signals}.
     *
     * @param datPath      the segment's signal file
     * @param signals      the segment header's signal specifications (all sharing {@code datPath})
     * @param numFramesHint the samples-per-signal count from the segment header; when {@code <= 0}
     *                      the frame count is derived from the file length
     * @return a reader positioned over the decoded samples
     * @throws IOException                   if the file cannot be read
     * @throws UnsupportedOperationException if the signals use a format this reader cannot decode
     * @throws IllegalArgumentException      if the signals mix formats or files
     */
    public static WfdbSegmentReader open(Path datPath, List<WfdbSignal> signals, long numFramesHint)
            throws IOException {
        if (signals.isEmpty()) {
            throw new IllegalArgumentException("Segment has no signals: " + datPath);
        }
        int format = signals.get(0).format();
        int byteOffset = signals.get(0).byteOffset();
        for (WfdbSignal s : signals) {
            if (s.format() != format) {
                throw new IllegalArgumentException(
                        "Mixed formats in one segment are not supported: %d vs %d".formatted(format, s.format()));
            }
        }
        if (format != 80 && format != 16 && format != 212) {
            throw new UnsupportedOperationException(
                    "Unsupported WFDB format %d in %s (supported: 80, 16, 212)".formatted(format, datPath));
        }

        byte[] data = Files.readAllBytes(datPath);
        int n = signals.size();
        long payloadBytes = (long) data.length - byteOffset;
        long framesFromFile = switch (format) {
            case 80 -> payloadBytes / n;
            case 16 -> payloadBytes / (2L * n);
            case 212 -> (payloadBytes * 2) / (3L * n);
            default -> 0L;
        };
        long numFrames = numFramesHint > 0 ? Math.min(numFramesHint, framesFromFile) : framesFromFile;

        return new WfdbSegmentReader(data, n, format, byteOffset, numFrames);
    }

    /** @return the number of decodable frames (samples per signal) in this segment */
    public long numFrames() { return numFrames; }

    /** @return the number of signals per frame */
    public int numSignals() { return numSignals; }

    /**
     * Decodes the raw ADC value of signal {@code signalIndex} in frame {@code frame}.
     *
     * @param signalIndex zero-based signal (column) index
     * @param frame       zero-based frame index, {@code 0 <= frame < }{@link #numFrames()}
     * @return the sign-corrected raw ADC sample value
     */
    public int adc(int signalIndex, long frame) {
        long k = frame * numSignals + signalIndex;
        return switch (format) {
            case 80 -> (data[byteOffset + (int) k] & 0xFF) - 128;
            case 16 -> {
                int p = byteOffset + (int) (k * 2);
                yield (short) ((data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8));
            }
            case 212 -> decode212((int) k);
            default -> throw new IllegalStateException("Unsupported format " + format);
        };
    }

    private int decode212(int k) {
        int pair = k / 2;
        int base = byteOffset + pair * 3;
        int b1 = data[base + 1] & 0xFF;
        int v;
        if ((k & 1) == 0) {
            v = (data[base] & 0xFF) | ((b1 & 0x0F) << 8);
        } else {
            v = (data[base + 2] & 0xFF) | ((b1 & 0xF0) << 4);
        }
        if ((v & 0x800) != 0) v -= 0x1000; // sign-extend 12-bit two's complement
        return v;
    }
}