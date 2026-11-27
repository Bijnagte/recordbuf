package bijnagte.recordbuf.fixture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import bijnagte.recordbuf.runtime.UnknownFields;
import bijnagte.recordbuf.runtime.Wire;
import bijnagte.recordbuf.runtime.wkt.Wkt;
import bijnagte.recordbuf.runtime.wkt.Wkt.Wrap;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural parity checks for the direct-stream WKT writes: each {@code write*} emits exactly the
 * tag, length and content the allocating encoder produces, and each {@code *Size} equals the
 * content length. Not goldens.
 */
class WktDirectWriteTest {

  private interface Writer {
    void write(CodedOutputStream out) throws IOException;
  }

  private static byte[] run(Writer w) throws IOException {
    var bos = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bos);
    w.write(out);
    out.flush();
    return bos.toByteArray();
  }

  private static byte[] framed(int num, byte[] content) throws IOException {
    return run(out -> out.writeByteArray(num, content));
  }

  @Test
  void timestampAndDuration() throws IOException {
    for (Instant v :
        List.of(
            Instant.EPOCH,
            Instant.ofEpochSecond(1_700_000_000L, 123),
            Instant.ofEpochSecond(-5, 7))) {
      assertEquals(Wkt.timestamp(v).length, Wkt.timestampSize(v));
      assertArrayEquals(framed(3, Wkt.timestamp(v)), run(o -> Wkt.writeTimestamp(o, 3, v)));
    }
    for (Duration v :
        List.of(
            Duration.ZERO,
            Duration.ofSeconds(3, 5),
            Duration.ofMillis(-1500),
            Duration.ofNanos(-1),
            Duration.ofSeconds(-2, 9))) {
      assertEquals(Wkt.duration(v).length, Wkt.durationSize(v));
      assertArrayEquals(framed(4, Wkt.duration(v)), run(o -> Wkt.writeDuration(o, 4, v)));
    }
  }

  @Test
  void fieldMaskAndEmpty() throws IOException {
    for (FieldMask v : List.of(FieldMask.empty(), FieldMask.of("a.b", "c"))) {
      assertEquals(Wkt.fieldMask(v).length, Wkt.fieldMaskSize(v));
      assertArrayEquals(framed(5, Wkt.fieldMask(v)), run(o -> Wkt.writeFieldMask(o, 5, v)));
    }
    assertEquals(0, Wkt.emptySize(Empty.INSTANCE));
    assertArrayEquals(framed(6, new byte[0]), run(o -> Wkt.writeEmpty(o, 6, Empty.INSTANCE)));
  }

  @Test
  void wrappers() throws IOException {
    record Case(Wrap wire, Object zero, Object value) {}
    var cases =
        List.of(
            new Case(Wrap.INT32, 0, -7),
            new Case(Wrap.UINT32, 0, 300),
            new Case(Wrap.SINT_32, 0, -9),
            new Case(Wrap.FIXED32, 0, 11),
            new Case(Wrap.INT64, 0L, -8L),
            new Case(Wrap.UINT64, 0L, 1L << 40),
            new Case(Wrap.SINT_64, 0L, -12L),
            new Case(Wrap.FIXED64, 0L, 13L),
            new Case(Wrap.FLOAT, 0f, 1.5f),
            new Case(Wrap.DOUBLE, 0d, -2.5d),
            new Case(Wrap.BOOL, false, true),
            new Case(Wrap.STRING, "", "héllo"),
            new Case(Wrap.BYTES, Bytes.empty(), Bytes.of(new byte[] {1, 2, 3})));
    for (Case c : cases) {
      for (Object v : new Object[] {c.zero(), c.value()}) {
        assertEquals(Wkt.wrap(c.wire(), v).length, Wkt.wrapSize(c.wire(), v), c.wire() + " size");
        assertArrayEquals(
            framed(2, Wkt.wrap(c.wire(), v)),
            run(o -> Wkt.writeWrap(o, 2, c.wire(), v)),
            c.wire() + " write");
      }
    }
  }

  @Test
  void lenDelimSizeMatchesWriteByteArray() throws IOException {
    for (int n : new int[] {0, 1, 127, 128, 16_383, 16_384}) {
      byte[] content = new byte[n];
      assertEquals(framed(9, content).length, Wire.lenDelimSize(9, n));
    }
  }

  @Test
  void unknownFieldsFromProtoRoundTrips() throws IOException {
    assertSame(UnknownFields.empty(), UnknownFields.fromProto(null));
    assertSame(
        UnknownFields.empty(), UnknownFields.fromProto(UnknownFieldSet.getDefaultInstance()));
    var set =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(-1).addVarint(5).build())
            .addField(101, UnknownFieldSet.Field.newBuilder().addFixed32(0xCAFE).build())
            .addField(102, UnknownFieldSet.Field.newBuilder().addFixed64(1L << 50).build())
            .addField(
                103,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(ByteString.EMPTY)
                    .addLengthDelimited(ByteString.copyFrom(new byte[200]))
                    .build())
            .build();
    UnknownFields uf = UnknownFields.fromProto(set);
    assertEquals(set.getSerializedSize(), uf.getSerializedSize());
    var bos = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bos);
    uf.writeTo(out);
    out.flush();
    assertEquals(set, UnknownFieldSet.parseFrom(bos.toByteArray()));
  }
}
