package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the shape of the generated {@code XAvro} converter (KTD6 / R20-P1): a static {@code
 * fromRecord} that fills the {@code XSpecific} and a static {@code toRecord} that rebuilds the
 * record, with a well-known-type field routed through {@code WktAvroBridge} rather than copied. The
 * converter components compile + round-trip at the fixtures level (U6); this test pins the shape at
 * the generator level.
 */
class AvroConverterGeneratorTest {

  private static Message person() {
    var age =
        new Field(
            "age",
            "age",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "int32",
            null,
            null,
            false,
            null,
            null,
            false);
    var whenCreated =
        new Field(
            "when_created",
            "whenCreated",
            3,
            FieldLabel.OPTIONAL,
            FieldKind.MESSAGE,
            "google.protobuf.Timestamp",
            null,
            null,
            true,
            null,
            null,
            false);
    return new Message(
        "Person",
        "Person",
        List.of(age, whenCreated),
        java.util.List.<bijnagte.recordbuf.plugin.model.Message>of(),
        java.util.List.<bijnagte.recordbuf.plugin.model.Enum>of(),
        java.util.List.<bijnagte.recordbuf.plugin.model.Oneof>of(),
        false,
        null,
        List.of(),
        null);
  }

  @Test
  void converterHasBothDirectionsAndRoutesWktThroughBridge() {
    String src =
        AvroConverterGenerator.converter(
            "com.example", person(), AvroSpecificGenerator.specificName(person()));
    // The two static directions.
    assertTrue(src.contains("fromRecord("), "fromRecord present: " + src);
    assertTrue(src.contains("toRecord("), "toRecord present: " + src);
    // The specific class name is used for both.
    assertTrue(src.contains("PersonSpecific"), "specific class referenced: " + src);
    // A scalar copies directly; the WKT field routes through the bridge.
    assertTrue(src.contains("s.age = r.age()"), "scalar copies directly: " + src);
    assertTrue(
        src.contains("bijnagte.recordbuf.avro.wkt.WktAvroBridge"),
        "WKT field routed via the bridge: " + src);
    // The generated classes live in the record's package.
    assertTrue(src.contains("package com.example;"), src);
  }
}
