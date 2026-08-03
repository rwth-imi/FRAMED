package com.framed.communicator.driver.protocol.mimic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed <a href="https://archive.physionet.org/physiotools/wag/header-5.htm">WFDB header</a>
 * ({@code .hea}) file, covering the three header flavours used by the
 * <a href="https://physionet.org/content/mimic3wdb/1.0/">MIMIC-III Waveform Database</a>:
 *
 * <ul>
 *   <li><b>Master (multi-segment) header</b> &mdash; its record line carries a {@code /numSegments}
 *       suffix and it lists one {@link Segment} per line instead of signal specifications. Segments
 *       whose name is {@code "~"} are gaps ({@link Segment#gap()}); the {@code _layout} segment has
 *       zero length and points at the layout header.</li>
 *   <li><b>Layout header</b> &mdash; a single-segment header whose signal lines use the placeholder
 *       file {@code "~"} and format {@code 0}; it establishes the canonical signal order, gains and
 *       names for a variable-layout record.</li>
 *   <li><b>Segment header</b> &mdash; a single-segment header whose signal lines reference a real
 *       {@code .dat} file; this is the source of truth for decoding that segment.</li>
 * </ul>
 *
 * <p>The parser is deliberately lenient: comment lines (starting with {@code #}) and blank lines are
 * skipped, and trailing optional numeric fields on a signal line may be omitted.</p>
 */
public final class WfdbHeader {

    /** A segment reference on a master (multi-segment) header line. */
    public record Segment(String name, long numSamples, boolean gap) {}

    // Signal line: <file> <format> <gain(baseline)/units> [adcRes] [adcZero] ... [description]
    // The gain token groups gain, an optional (baseline) and an optional /units.
    private static final Pattern GAIN_TOKEN =
            Pattern.compile("^([-+0-9.eE]+)(?:\\((-?[0-9]+)\\))?(?:/(.+))?$");
    // The format token may carry samples-per-frame (xN), skew (:N) and byte offset (+N) decorators.
    private static final Pattern FORMAT_TOKEN =
            Pattern.compile("^(\\d+)(?:x(\\d+))?(?::(\\d+))?(?:\\+(\\d+))?$");

    private final String recordName;
    private final boolean multiSegment;
    private final double samplingFrequency;
    private final long numSamplesPerSignal;
    private final String baseTime;
    private final List<Segment> segments;
    private final List<WfdbSignal> signals;

    private WfdbHeader(String recordName, boolean multiSegment, double samplingFrequency,
                       long numSamplesPerSignal, String baseTime,
                       List<Segment> segments, List<WfdbSignal> signals) {
        this.recordName = recordName;
        this.multiSegment = multiSegment;
        this.samplingFrequency = samplingFrequency;
        this.numSamplesPerSignal = numSamplesPerSignal;
        this.baseTime = baseTime;
        this.segments = List.copyOf(segments);
        this.signals = List.copyOf(signals);
    }

    /**
     * Parses the header file at {@code path}.
     *
     * @param path the {@code .hea} file to read
     * @return the parsed header
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the record line is malformed
     */
    public static WfdbHeader parse(Path path) throws IOException {
        return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    /**
     * Parses header content already read into lines. Exposed for testing without touching the
     * file system.
     *
     * @param rawLines the lines of a {@code .hea} file, in order
     * @return the parsed header
     * @throws IllegalArgumentException if no valid record line is present
     */
    public static WfdbHeader parse(List<String> rawLines) {
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            lines.add(trimmed);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Empty WFDB header");
        }

        // Record line: <name>[/<numSegments>] <numSignals> [<fs>] [<nsamp>] [<basetime>] [<basedate>]
        String[] rec = lines.get(0).split("\\s+");
        String nameField = rec[0];
        boolean multi = nameField.contains("/");
        String recordName = multi ? nameField.substring(0, nameField.indexOf('/')) : nameField;
        int numSignals = rec.length > 1 ? parseIntLead(rec[1]) : 0;
        double fs = rec.length > 2 ? parseDoubleLead(rec[2]) : 250.0; // WFDB default
        long nsamp = rec.length > 3 ? parseLongLead(rec[3]) : 0L;
        String baseTime = rec.length > 4 ? rec[4] : null;

        List<Segment> segments = new ArrayList<>();
        List<WfdbSignal> signals = new ArrayList<>();

        if (multi) {
            for (int i = 1; i < lines.size(); i++) {
                String[] tok = lines.get(i).split("\\s+");
                boolean gap = "~".equals(tok[0]);
                long segSamples = tok.length > 1 ? parseLongLead(tok[1]) : 0L;
                segments.add(new Segment(gap ? "~" : tok[0], segSamples, gap));
            }
        } else {
            for (int i = 1; i < lines.size() && signals.size() < numSignals; i++) {
                signals.add(parseSignalLine(lines.get(i)));
            }
        }

        return new WfdbHeader(recordName, multi, fs, nsamp, baseTime, segments, signals);
    }

    private static WfdbSignal parseSignalLine(String line) {
        String[] t = line.split("\\s+");
        String fileName = t[0];

        Matcher fm = FORMAT_TOKEN.matcher(t.length > 1 ? t[1] : "0");
        int format = 0;
        int byteOffset = 0;
        if (fm.matches()) {
            format = Integer.parseInt(fm.group(1));
            if (fm.group(4) != null) byteOffset = Integer.parseInt(fm.group(4));
        }

        double gain = 0.0;
        int baseline = 0;
        String units = null;
        boolean baselineSeen = false;
        if (t.length > 2) {
            Matcher gm = GAIN_TOKEN.matcher(t[2]);
            if (gm.matches()) {
                gain = Double.parseDouble(gm.group(1));
                if (gm.group(2) != null) {
                    baseline = Integer.parseInt(gm.group(2));
                    baselineSeen = true;
                }
                units = gm.group(3);
            }
        }

        // ADC zero (field index 4) doubles as the baseline when no explicit (baseline) was given.
        int adcZero = t.length > 4 ? parseIntSafe(t[4], 0) : 0;
        if (!baselineSeen) baseline = adcZero;

        // Description is everything after the block-size field (index 7). Fall back to the last
        // token when the optional numeric fields are truncated.
        String description = null;
        if (t.length > 8) {
            StringBuilder sb = new StringBuilder();
            for (int i = 8; i < t.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t[i]);
            }
            description = sb.toString();
        } else if (t.length > 2) {
            description = t[t.length - 1];
        }

        return new WfdbSignal(fileName, format, byteOffset, gain, baseline, units, description);
    }

    // ---- accessors -----------------------------------------------------------------------------

    /** @return the record identifier (without any {@code /numSegments} suffix) */
    public String recordName() { return recordName; }

    /** @return {@code true} if this is a master multi-segment header (has {@link #segments()}) */
    public boolean isMultiSegment() { return multiSegment; }

    /** @return the sampling frequency in Hz (WFDB default {@code 250} if the field was absent) */
    public double samplingFrequency() { return samplingFrequency; }

    /** @return the declared number of samples per signal ({@code 0} if unspecified) */
    public long numSamplesPerSignal() { return numSamplesPerSignal; }

    /** @return the base time string ({@code HH:MM:SS[.fff]}) from the record line, or {@code null} */
    public String baseTime() { return baseTime; }

    /** @return the ordered segment list for a master header (empty for single-segment headers) */
    public List<Segment> segments() { return segments; }

    /** @return the ordered signal specifications for a single-segment/layout header (empty otherwise) */
    public List<WfdbSignal> signals() { return signals; }

    private static int parseIntLead(String s) { return (int) parseLongLead(s); }

    /** Parses a leading, optionally signed integer, ignoring any {@code /} or {@code (} decorators. */
    private static long parseLongLead(String s) {
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i == 0 ? 0L : Long.parseLong(s.substring(0, i));
    }

    /** Parses a leading floating-point number, ignoring a trailing {@code /counterfreq} decorator. */
    private static double parseDoubleLead(String s) {
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        String num = s.substring(0, i);
        return num.isEmpty() || num.equals(".") ? 0.0 : Double.parseDouble(num);
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}