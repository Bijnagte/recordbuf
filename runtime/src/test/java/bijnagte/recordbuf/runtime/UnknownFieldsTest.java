package bijnagte.recordbuf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link UnknownFields} carrier + the {@code of(Iterable)} factory the converters use. */
class UnknownFieldsTest {

  @Test
  void ofEmptyInputReturnsSharedEmpty() {
    assertSame(UnknownFields.empty(), UnknownFields.of(List.of()));
  }

  @Test
  void ofPreservesOrderAndContents() {
    UnknownField first = new UnknownField(7, WireType.I32, new byte[] {1});
    UnknownField second = new UnknownField(9, WireType.I64, new byte[] {2, 3});
    UnknownFields fields = UnknownFields.of(List.of(first, second));
    assertNotSame(UnknownFields.empty(), fields);
    assertEquals(List.of(first, second), fields.fields());
  }

  @Test
  void ofCopiesItsInput() {
    ArrayList<UnknownField> input = new ArrayList<>();
    input.add(new UnknownField(1, WireType.I32, new byte[] {5}));
    UnknownFields fields = UnknownFields.of(input);
    input.add(new UnknownField(2, WireType.I32, new byte[] {6}));
    assertEquals(1, fields.fields().size());
  }

  @Test
  void emptyCarrierIsEmpty() {
    assertTrue(UnknownFields.empty().isEmpty());
  }
}
