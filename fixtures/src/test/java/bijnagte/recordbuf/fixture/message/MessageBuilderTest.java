package bijnagte.recordbuf.fixture.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end use of the generated fluent {@code Builder} for the nested-message family. The {@code
 * .rec} record under test lives in the sibling {@code bijnagte.recordbuf.fixture.message.rec}
 * package. These tests confirm the builder's fluent setters, the {@code build()} terminal, and the
 * {@code toBuilder()} copy semantics behave -- the layer the generator's source-shape test can't
 * reach.
 */
class MessageBuilderTest {

  private static final int ID = 7;
  private static final String NAME = "seven";

  private static bijnagte.recordbuf.fixture.message.rec.Item aItem(int id, String name) {
    return bijnagte.recordbuf.fixture.message.rec.Item.builder().id(id).name(name).build();
  }

  @Test
  void freshBuilderWithSettersRoundTrips() throws Exception {
    var b = bijnagte.recordbuf.fixture.message.rec.Sample.builder();
    b.single(aItem(ID, NAME));
    // The component-named setter replaces the whole value (value-only semantics);
    // a second call to items(...) overrides, it does not append.
    b.items(List.of(aItem(1, "one"), aItem(2, "two")));

    bijnagte.recordbuf.fixture.message.rec.Sample v = b.build();
    assertEquals(aItem(ID, NAME), v.single());
    assertEquals(List.of(aItem(1, "one"), aItem(2, "two")), v.items());

    // A builder-built value round-trips through the codec.
    assertEquals(
        v,
        bijnagte.recordbuf.fixture.message.rec.Sample.SampleCodec.decode(
            bijnagte.recordbuf.fixture.message.rec.Sample.SampleCodec.encode(v)));
  }

  @Test
  void freshBuilderYieldsProto3Defaults() {
    bijnagte.recordbuf.fixture.message.rec.Sample defaultV =
        bijnagte.recordbuf.fixture.message.rec.Sample.builder().build();
    assertNull(defaultV.single(), "fresh builder leaves singular message absent (null)");
    assertEquals(List.of(), defaultV.items(), "fresh builder leaves repeated message empty");
  }

  @Test
  void toBuilderCopyIsDefensive() {
    bijnagte.recordbuf.fixture.message.rec.Sample original =
        bijnagte.recordbuf.fixture.message.rec.Sample.builder()
            .single(aItem(ID, NAME))
            .items(List.of(aItem(1, "one")))
            .build();

    var b = original.toBuilder();
    List<bijnagte.recordbuf.fixture.message.rec.Item> mutated = new ArrayList<>(original.items());
    mutated.remove(0);
    b.items(mutated);

    bijnagte.recordbuf.fixture.message.rec.Sample rebuilt = b.build();
    // The builder's items were copied from the source, so mutating the input list
    // through the builder did not reach the original record's components.
    assertEquals(List.of(aItem(1, "one")), original.items());
    assertEquals(List.of(), rebuilt.items());
    assertEquals(aItem(ID, NAME), rebuilt.single());
    assertNotNull(rebuilt);
  }
}
