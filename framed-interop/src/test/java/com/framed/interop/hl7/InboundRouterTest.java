package com.framed.interop.hl7;

import com.framed.interop.hl7.hl7v2.Hl7Message;
import com.framed.interop.mapping.ObservationMapping;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routing-level simulation using actual HL7 v2 message text (ORU^R01, ADT^A08, malformed input).
 */
class InboundRouterTest {

  private record Captured(String className, String deviceID, String channelID, Object value) {}

  private static final String MAPPING_JSON = """
      {
        "Measurement.Oxylog-3000-Plus-00.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"},
        "Percentage_int.PC60FW.SpO2": {"code":"59408-5","system":"LOINC","display":"SpO2","unit":"%","valueType":"NM"}
      }""";

  private static final String ORU = String.join("\r",
      "MSH|^~\\&|MONITOR|ICU|FRAMED|HOSP|20260623120000||ORU^R01|MSG0001|P|2.5",
      "PID|1||12345^^^MRN||Doe^Jane||19800101|F",
      "PV1|1|I|ICU^01^A",
      "OBR|1||||",
      "OBX|1|NM|19889-5^End tidal CO2^LOINC||38|mm[Hg]|||||F",
      "OBX|2|NM|59408-5^SpO2^LOINC||95|%|||||F") + "\r";

  private static final String ADT = String.join("\r",
      "MSH|^~\\&|ADT|HOSP|FRAMED|ICU|20260623120000||ADT^A08|MSG0002|P|2.5",
      "EVN|A08|20260623120000",
      "PID|1||67890^^^MRN||Smith^John||19700202|M",
      "PV1|1|I|ICU^02^B") + "\r";

  private List<Captured> captured;
  private InboundRouter router(boolean ingestAdt) {
    captured = new ArrayList<>();
    ObservationMapping mapping = ObservationMapping.fromJson(new JSONObject(MAPPING_JSON));
    ObservationSink sink = (cls, dev, ch, val, ts) -> captured.add(new Captured(cls, dev, ch, val));
    return new InboundRouter(mapping, sink, "HL7-In", ingestAdt);
  }

  @Test
  void routesOruObservationsToEavAndAcksAa() {
    String ack = router(false).handle(ORU);

    assertEquals(2, captured.size());
    Captured etco2 = captured.get(0);
    assertEquals("Measurement", etco2.className());
    assertEquals("Oxylog-3000-Plus-00", etco2.deviceID());
    assertEquals("etCO2", etco2.channelID());
    assertEquals(38.0, etco2.value());

    Captured spo2 = captured.get(1);
    assertEquals("PC60FW", spo2.deviceID());
    assertEquals("SpO2", spo2.channelID());
    assertEquals(95.0, spo2.value());

    Hl7Message ackMsg = Hl7Message.parse(ack);
    assertEquals("AA", ackMsg.field("MSA", 1));
    assertEquals("MSG0001", ackMsg.field("MSA", 2), "echoes the control id");
  }

  @Test
  void ingestsAdtDemographicsWhenEnabled() {
    String ack = router(true).handle(ADT);

    assertTrue(captured.stream().anyMatch(c ->
        c.className().equals("Patient") && c.channelID().equals("MRN") && c.value().equals("67890")));
    assertTrue(captured.stream().anyMatch(c ->
        c.channelID().equals("Name") && c.value().equals("Smith John")));
    assertEquals("AA", Hl7Message.parse(ack).field("MSA", 1));
  }

  @Test
  void ignoresAdtWhenDisabled() {
    String ack = router(false).handle(ADT);
    assertTrue(captured.isEmpty());
    assertEquals("AA", Hl7Message.parse(ack).field("MSA", 1));
  }

  @Test
  void malformedMessageYieldsApplicationError() {
    String ack = router(false).handle("this is not HL7");
    assertEquals("AE", Hl7Message.parse(ack).field("MSA", 1));
  }

  @Test
  void obx14TimestampReachesTheSink() {
    List<Instant> stamps = new ArrayList<>();
    ObservationMapping mapping = ObservationMapping.fromJson(new JSONObject(MAPPING_JSON));
    InboundRouter router =
        new InboundRouter(mapping, (cls, dev, ch, val, ts) -> stamps.add(ts), "HL7-In", false);

    String oru = String.join("\r",
        "MSH|^~\\&|MONITOR|ICU|FRAMED|HOSP|20260623120000||ORU^R01|MSG0003|P|2.5",
        "PID|1||12345^^^MRN||Doe^Jane||19800101|F",
        "OBR|1||||",
        "OBX|1|NM|19889-5^End tidal CO2^LOINC||38|mm[Hg]|||||F|||20260623115959") + "\r";
    router.handle(oru);

    assertEquals(List.of(Instant.parse("2026-06-23T11:59:59Z")), stamps,
        "the observation keeps its OBX-14 time instead of being re-stamped on arrival");
  }

  @Test
  void parsesHl7DtmPrecisionsAndOffsets() {
    assertEquals(Instant.parse("2026-06-23T12:00:00Z"), InboundRouter.parseTimestamp("20260623120000"),
        "full precision, no offset: interpreted as UTC");
    assertEquals(Instant.parse("2026-06-23T10:00:00Z"), InboundRouter.parseTimestamp("20260623120000+0200"),
        "explicit zone offset honoured");
    assertEquals(Instant.parse("2026-06-23T14:00:00Z"), InboundRouter.parseTimestamp("20260623120000-0200"),
        "negative zone offset honoured");
    assertEquals(Instant.parse("2026-06-23T12:00:00Z"), InboundRouter.parseTimestamp("20260623120000.1234"),
        "fractional seconds tolerated");
    assertEquals(Instant.parse("2026-06-23T12:30:00Z"), InboundRouter.parseTimestamp("202606231230"),
        "minute precision padded");
    assertEquals(Instant.parse("2026-06-23T00:00:00Z"), InboundRouter.parseTimestamp("20260623"),
        "day precision padded to midnight");
    assertEquals(Instant.parse("2026-06-01T00:00:00Z"), InboundRouter.parseTimestamp("202606"),
        "month precision padded to the 1st");
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), InboundRouter.parseTimestamp("2026"),
        "year precision padded to Jan 1st");
  }

  @Test
  void absentOrMalformedDtmFallsBackToArrivalTime() {
    for (String raw : new String[]{null, "", "  ", "20261323120000", "202606231", "not-a-dtm"}) {
      Instant before = Instant.now();
      Instant got = InboundRouter.parseTimestamp(raw);
      Instant after = Instant.now();
      assertFalse(got.isBefore(before) || got.isAfter(after),
          "\"%s\" must fall back to the arrival time".formatted(raw));
    }
  }
}
