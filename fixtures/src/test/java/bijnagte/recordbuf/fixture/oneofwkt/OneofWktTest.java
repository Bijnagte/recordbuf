package bijnagte.recordbuf.fixture.oneofwkt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.SampleCodec;
import bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.V;
import bijnagte.recordbuf.fixture.oneofwkt.rec.SampleAvroConverter;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.time.Instant;
import java.util.List;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Oneof members that are well-known types (structured, wrapper, dynamic) or bytes:
 * byte-and-semantic interop of the codec against the protobuf-java reference (each set member on
 * the wire even at its zero value), and the Avro bridge, where every member rides the shared {@code
 * {tag, value}} oneof wrapper in its Avro form and survives record -> Avro binary -> record.
 */
class OneofWktTest {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  @BeforeAll
  static void trustTheAvroClasses() {
    // The global validator is replaced per test class (see AvroInteropTest); name ours.
    var b = ClassSecurityValidator.builder();
    for (String name :
        List.of(
            "SampleAvro", "RecordbufOneofValue", "RecordbufUnknownField", "RecordbufDynamicWkt")) {
      b.add("bijnagte.recordbuf.fixture.oneofwkt." + name);
    }
    b.add("bijnagte.recordbuf.avro.wkt.Duration");
    b.add("bijnagte.recordbuf.avro.wkt.Empty");
    ClassSecurityValidator.setGlobal(b.build());
  }

  private static bijnagte.recordbuf.fixture.oneofwkt.rec.Sample record(V v) {
    return bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.builder().v(v).build();
  }

  /** Each record paired with the reference message carrying the same member. */
  private static List<Object[]> cases() {
    return List.of(
        new Object[] {
          record(new V.AtValue(Instant.ofEpochSecond(1_763_614_896L, 789))),
          Sample.newBuilder()
              .setAt(Timestamp.newBuilder().setSeconds(1_763_614_896L).setNanos(789))
              .build()
        },
        new Object[] {
          record(new V.AtValue(Instant.EPOCH)),
          Sample.newBuilder().setAt(Timestamp.getDefaultInstance()).build()
        },
        new Object[] {
          record(new V.CountValue(0)), Sample.newBuilder().setCount(Int32Value.of(0)).build()
        },
        new Object[] {
          record(new V.CountValue(-7)), Sample.newBuilder().setCount(Int32Value.of(-7)).build()
        },
        new Object[] {
          record(new V.BlobValue(Bytes.empty())),
          Sample.newBuilder().setBlob(ByteString.EMPTY).build()
        },
        new Object[] {
          record(new V.BlobValue(Bytes.of(new byte[] {1, (byte) 0xff}))),
          Sample.newBuilder().setBlob(ByteString.copyFrom(new byte[] {1, (byte) 0xff})).build()
        },
        new Object[] {
          record(new V.ValueValue(F.stringNode("s"))),
          Sample.newBuilder().setValue(Value.newBuilder().setStringValue("s")).build()
        },
        new Object[] {
          // proto Duration carries same-signed nanos; -1.5s is {-1, -500000000}.
          record(new V.SpanValue(java.time.Duration.ofMillis(-1500))),
          Sample.newBuilder()
              .setSpan(Duration.newBuilder().setSeconds(-1).setNanos(-500_000_000))
              .build()
        },
        new Object[] {
          record(new V.NothingValue(bijnagte.recordbuf.runtime.Empty.INSTANCE)),
          Sample.newBuilder().setNothing(Empty.getDefaultInstance()).build()
        },
        new Object[] {
          record(new V.MaskValue(bijnagte.recordbuf.runtime.FieldMask.of("a.b", "c"))),
          Sample.newBuilder().setMask(FieldMask.newBuilder().addPaths("a.b").addPaths("c")).build()
        },
        new Object[] {
          record(new V.MaskValue(bijnagte.recordbuf.runtime.FieldMask.empty())),
          Sample.newBuilder().setMask(FieldMask.getDefaultInstance()).build()
        },
        new Object[] {
          record(new V.TextValue("")), Sample.newBuilder().setText(StringValue.of("")).build()
        });
  }

  @Test
  void byteEqualToTheReference() throws Exception {
    for (Object[] c : cases()) {
      var ours = (bijnagte.recordbuf.fixture.oneofwkt.rec.Sample) c[0];
      var theirs = (Sample) c[1];
      assertArrayEquals(theirs.toByteArray(), SampleCodec.encode(ours), ours.toString());
    }
  }

  @Test
  void decodesTheReferenceBytes() throws Exception {
    for (Object[] c : cases()) {
      var ours = (bijnagte.recordbuf.fixture.oneofwkt.rec.Sample) c[0];
      var theirs = (Sample) c[1];
      assertEquals(ours, SampleCodec.decode(theirs.toByteArray()), theirs.toString());
    }
  }

  /** Every member (and an unset oneof) survives record -> Avro binary -> record. */
  @Test
  void everyMemberRoundTripsThroughAvroBinary() throws Exception {
    List<bijnagte.recordbuf.fixture.oneofwkt.rec.Sample> records =
        List.of(
            // A Timestamp travels at microsecond resolution (see the floor test below).
            record(new V.AtValue(Instant.ofEpochSecond(1_763_614_896L, 789_000))),
            record(new V.AtValue(Instant.ofEpochSecond(-1, 999_999_000))),
            record(new V.CountValue(-7)),
            record(new V.BlobValue(Bytes.empty())),
            record(new V.BlobValue(Bytes.of(new byte[] {1, (byte) 0xff}))),
            record(new V.ValueValue(F.objectNode().put("k", "v"))),
            record(new V.SpanValue(java.time.Duration.ofMillis(-1500))),
            record(new V.SpanValue(java.time.Duration.ofSeconds(Long.MAX_VALUE, 999_999_999))),
            record(new V.NothingValue(bijnagte.recordbuf.runtime.Empty.INSTANCE)),
            record(new V.MaskValue(bijnagte.recordbuf.runtime.FieldMask.of("a.b", "c"))),
            record(new V.MaskValue(bijnagte.recordbuf.runtime.FieldMask.empty())),
            record(new V.TextValue("")),
            bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.builder().build());
    for (var original : records) {
      var binary = SampleAvroConverter.fromRecord(original).toByteBuffer();

      assertEquals(
          original,
          SampleAvroConverter.toRecord(SampleAvro.fromByteBuffer(binary)),
          original.toString());
    }
  }

  @Test
  void aTimestampMemberFloorsToTheMicrosecondInAvro() throws Exception {
    var binary =
        SampleAvroConverter.fromRecord(
                record(new V.AtValue(Instant.ofEpochSecond(-1, 999_999_999))))
            .toByteBuffer();

    assertEquals(
        record(new V.AtValue(Instant.ofEpochSecond(-1, 999_999_000))),
        SampleAvroConverter.toRecord(SampleAvro.fromByteBuffer(binary)));
  }
}
