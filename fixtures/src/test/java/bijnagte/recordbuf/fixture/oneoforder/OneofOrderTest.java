package bijnagte.recordbuf.fixture.oneoforder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.oneoforder.rec.Sample.O;
import bijnagte.recordbuf.fixture.oneoforder.rec.Sample.P;
import bijnagte.recordbuf.fixture.oneoforder.rec.Sample.SampleCodec;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.ByteString;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A oneof's members are written at their own field numbers among the regular fields, exactly as
 * protobuf-java orders them: ascending field number across oneofs and regular fields together.
 */
class OneofOrderTest {

  private static bijnagte.recordbuf.fixture.oneoforder.rec.Sample record(
      O o, P p, int first, int mid, List<String> tail) {
    return bijnagte.recordbuf.fixture.oneoforder.rec.Sample.builder()
        .first(first)
        .o(o)
        .mid(mid)
        .tail(tail)
        .p(p)
        .build();
  }

  /** Each record paired with the reference message carrying the same values. */
  private static List<Object[]> cases() {
    return List.of(
        // o.s (2) sits between first (1) and p.blob (3), mid (4), tail (9)
        new Object[] {
          record(new O.SValue("x"), new P.BlobValue(Bytes.of(new byte[] {7})), 1, 5, List.of("t")),
          Sample.newBuilder()
              .setFirst(1)
              .setS("x")
              .setBlob(ByteString.copyFrom(new byte[] {7}))
              .setMid(5)
              .addTail("t")
              .build()
        },
        // o.n (6) and p.big (8) come after mid (4) but before tail (9)
        new Object[] {
          record(new O.NValue(3), new P.BigValue(9L), 1, 5, List.of("t", "u")),
          Sample.newBuilder()
              .setFirst(1)
              .setN(3)
              .setMid(5)
              .setBig(9L)
              .addTail("t")
              .addTail("u")
              .build()
        },
        // only one oneof set, defaults elsewhere
        new Object[] {
          record(null, new P.BigValue(0L), 0, 0, List.of()), Sample.newBuilder().setBig(0L).build()
        },
        new Object[] {
          record(new O.SValue(""), null, 0, 0, List.of("z")),
          Sample.newBuilder().setS("").addTail("z").build()
        });
  }

  @Test
  void byteEqualToTheReference() throws Exception {
    for (Object[] c : cases()) {
      var ours = (bijnagte.recordbuf.fixture.oneoforder.rec.Sample) c[0];
      var theirs = (Sample) c[1];
      assertArrayEquals(theirs.toByteArray(), SampleCodec.encode(ours), ours.toString());
    }
  }

  @Test
  void decodesTheReferenceBytes() throws Exception {
    for (Object[] c : cases()) {
      var ours = (bijnagte.recordbuf.fixture.oneoforder.rec.Sample) c[0];
      var theirs = (Sample) c[1];
      assertEquals(ours, SampleCodec.decode(theirs.toByteArray()), theirs.toString());
    }
  }
}
