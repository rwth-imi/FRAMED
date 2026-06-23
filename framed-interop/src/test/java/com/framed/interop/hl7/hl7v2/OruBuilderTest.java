package com.framed.interop.hl7.hl7v2;

import com.framed.interop.mapping.CodedConcept;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OruBuilderTest {

  private static final SendingIds IDS = new SendingIds("FRAMED", "ICU", "HIS", "HOSP");
  private static final PatientContext PATIENT =
      new PatientContext("12345", "Doe", "Jane", "19800101", "F", "ICU^01^A");

  @Test
  void buildsWellFormedOruWithObxFields() {
    CodedConcept etco2 = new CodedConcept("19889-5", "LOINC", "End tidal CO2", "mm[Hg]", "NM");
    String oru = OruBuilder.build(IDS, PATIENT, etco2, "38", Instant.parse("2026-06-23T12:00:00Z"), "CID1");

    Hl7Message msg = Hl7Message.parse(oru);
    assertEquals("ORU^R01", msg.messageType());
    assertEquals("CID1", msg.controlId());
    assertEquals("20260623120000", msg.field("MSH", 7));
    assertEquals("FRAMED", msg.field("MSH", 3));

    // PID-3 carries the MRN
    assertEquals("12345", Hl7Message.firstComponent(msg.field("PID", 3)));

    String[] obx = msg.segment("OBX");
    assertEquals("NM", Hl7Message.field(obx, "OBX", 2));
    assertEquals("19889-5", Hl7Message.firstComponent(Hl7Message.field(obx, "OBX", 3)));
    assertEquals("38", Hl7Message.field(obx, "OBX", 5));
    assertEquals("mm[Hg]", Hl7Message.field(obx, "OBX", 6));
    assertEquals("F", Hl7Message.field(obx, "OBX", 11));
  }

  @Test
  void buildsMultipleObxSegments() {
    var hr = new CodedConcept("8867-4", "LOINC", "Heart rate", "/min", "NM");
    var spo2 = new CodedConcept("59408-5", "LOINC", "SpO2", "%", "NM");
    String oru = OruBuilder.build(IDS, PATIENT,
        List.of(new OruBuilder.Observation(hr, "72"), new OruBuilder.Observation(spo2, "98")),
        Instant.parse("2026-06-23T12:00:00Z"), "CID2");

    Hl7Message msg = Hl7Message.parse(oru);
    var obxs = msg.segments("OBX");
    assertEquals(2, obxs.size());
    assertEquals("8867-4", Hl7Message.firstComponent(Hl7Message.field(obxs.get(0), "OBX", 3)));
    assertEquals("59408-5", Hl7Message.firstComponent(Hl7Message.field(obxs.get(1), "OBX", 3)));
    assertEquals("98", Hl7Message.field(obxs.get(1), "OBX", 5));
  }
}
