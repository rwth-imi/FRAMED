package com.framed.communicator.driver.protocol.mimic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing tests for {@link WfdbHeader} against the three MIMIC-III header flavours. */
class WfdbHeaderTest {

    @Test
    void parsesMasterMultiSegmentHeaderWithGaps() {
        WfdbHeader h = WfdbHeader.parse(List.of(
                "3000003/23 5 125 18892500 19:44:07.664",
                "3000003_layout 0",
                "3000003_0001 16878",
                "~ 4077",
                "3000003_0002 384"
        ));

        assertTrue(h.isMultiSegment());
        assertEquals("3000003", h.recordName());
        assertEquals(125.0, h.samplingFrequency());
        assertEquals("19:44:07.664", h.baseTime());

        List<WfdbHeader.Segment> segs = h.segments();
        assertEquals(4, segs.size());
        assertEquals("3000003_layout", segs.get(0).name());
        assertEquals(0, segs.get(0).numSamples());
        assertEquals("3000003_0001", segs.get(1).name());
        assertEquals(16878, segs.get(1).numSamples());
        assertTrue(segs.get(2).gap());
        assertEquals(4077, segs.get(2).numSamples());
        assertFalse(segs.get(3).gap());
    }

    @Test
    void parsesSegmentHeaderWithFormat80AndGainBaselineUnits() {
        WfdbHeader h = WfdbHeader.parse(List.of(
                "3000003_0001 2 125 16878",
                "3000003_0001.dat 80 200(0)/mV 8 128 0 0 0 II",
                "3000003_0001.dat 80 100(-50)/mmHg 8 128 0 0 0 ABP"
        ));

        assertFalse(h.isMultiSegment());
        List<WfdbSignal> s = h.signals();
        assertEquals(2, s.size());

        WfdbSignal ii = s.get(0);
        assertEquals("3000003_0001.dat", ii.fileName());
        assertEquals(80, ii.format());
        assertEquals(200.0, ii.gain());
        assertEquals(0, ii.baseline());
        assertEquals("mV", ii.units());
        assertEquals("II", ii.description());

        WfdbSignal abp = s.get(1);
        assertEquals(-50, abp.baseline());
        assertEquals("mmHg", abp.units());
        assertEquals("ABP", abp.description());
        // physical = (adc - baseline) / gain
        assertEquals(0.5, abp.toPhysical(0), 1e-9); // (0 - (-50)) / 100
    }

    @Test
    void baselineDefaultsToAdcZeroWhenNotGiven() {
        // gain token has no (baseline); ADC-zero field (index 4) supplies the baseline.
        WfdbHeader h = WfdbHeader.parse(List.of(
                "seg 1 250 100",
                "seg.dat 16 200/mV 16 2048 0 0 0 V"
        ));
        WfdbSignal v = h.signals().get(0);
        assertEquals(2048, v.baseline());
        assertEquals(0.0, v.toPhysical(2048), 1e-9);
    }

    @Test
    void uncalibratedSignalPassesRawValueThrough() {
        WfdbHeader h = WfdbHeader.parse(List.of(
                "seg 1 125 10",
                "seg.dat 80 0 8 0 0 0 0 RESP"
        ));
        WfdbSignal resp = h.signals().get(0);
        assertTrue(resp.uncalibrated());
        assertEquals(42.0, resp.toPhysical(42), 1e-9);
    }
}