package bijnagte.recordbuf.fixture.oneofwkt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.SampleCodec;
import bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.V;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Byte-and-semantic interop of the generated codec against the protobuf-java reference for oneof
 * members that are well-known types (structured, wrapper, dynamic) or bytes -- each set member on
 * the wire even at its zero value.
 */
class OneofWktTest {

  private static bijnagte.recordbuf.fixture.oneofwkt.rec.Sample record(V v) {
    return bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.builder().v(v).build();
  }

  /** Each record paired with the reference message carrying the same member. */
  private static List<Object[]> cases() {
    var f = JsonNodeFactory.instance;
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
          record(new V.ValueValue(f.stringNode("s"))),
          Sample.newBuilder().setValue(Value.newBuilder().setStringValue("s")).build()
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
}
