package bijnagte.recordbuf.fixture.combined;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import bijnagte.recordbuf.fixture.combined.rec.Compat;
import com.google.protobuf.ByteString;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * The byte identity of the generated {@code Interop} converter, which the {@code combined} golden
 * family does not pin: its proto3 JSON golden fixes the message {@code Interop.to} builds by value,
 * not the bytes that message serializes to. The converter never touches wire bytes, so a converted
 * reference must serialize to exactly the bytes of a hand-built reference and of the record's own
 * codec ({@code toByteArray}), which is what lets a user hand a converted reference to gRPC. Every
 * other converter check this class made (record to reference and back, nested and repeated
 * messages, the sibling {@code Sub}, the all-default record) is a case of {@link CompatCases}.
 *
 * <p>The record is in the sibling {@code .rec} package and imported; the protobuf-java reference
 * lives in this package, so its {@code Compat}/{@code Sub} are written qualified by package.
 */
class CompatInteropTest {

  /**
   * The protobuf-java reference for a fully-populated record, built by hand so it is an independent
   * twin of the converter's output.
   */
  private static bijnagte.recordbuf.fixture.combined.Compat handBuiltReference(Compat record) {
    var reference =
        bijnagte.recordbuf.fixture.combined.Compat.newBuilder()
            .setI(record.i())
            .setFx(record.fx())
            .setS(record.s())
            .setB(ByteString.copyFrom(record.b().toByteArray()))
            .addAllPacked(record.packed());
    if (record.nested() != null) {
      reference.setNested(subReference(record.nested()));
    }
    for (var sub : record.subs()) {
      reference.addSubs(subReference(sub));
    }
    record.m().forEach(reference::putM);
    if (record.presence() != null) {
      reference.setPresence(record.presence());
    }
    return reference.build();
  }

  private static bijnagte.recordbuf.fixture.combined.Sub subReference(
      bijnagte.recordbuf.fixture.combined.rec.Sub sub) {
    return bijnagte.recordbuf.fixture.combined.Sub.newBuilder()
        .setId(sub.id())
        .setLabel(sub.label())
        .build();
  }

  /** {@code Interop.to} is a field copy: its output equals what a user would write by hand. */
  @Test
  void interopToProducesAByteIdenticalReference() throws IOException {
    Compat record = CompatCases.populated();

    assertArrayEquals(
        handBuiltReference(record).toByteArray(),
        Compat.Interop.to(record).toByteArray(),
        "Interop.to(record) must serialize to byte-identical protobuf-java bytes");
  }

  /**
   * The record's own codec bytes equal the converted reference's wire bytes, and an all-default
   * record converts to a reference that serializes to zero bytes.
   */
  @Test
  void recordCodecAndInteropAgreeOnBytes() throws IOException {
    Compat populated = CompatCases.populated();
    Compat allDefault = Compat.builder().build();

    assertArrayEquals(populated.toByteArray(), Compat.Interop.to(populated).toByteArray());
    assertArrayEquals(allDefault.toByteArray(), Compat.Interop.to(allDefault).toByteArray());
    assertArrayEquals(
        new byte[0],
        Compat.Interop.to(allDefault).toByteArray(),
        "an all-default record must serialize to zero protobuf-java bytes");
  }
}
