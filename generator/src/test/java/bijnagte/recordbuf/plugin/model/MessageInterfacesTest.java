package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.TypeRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * U1: the record and enum models carry an ordered, never-{@code null} list of fully-qualified
 * interfaces to emit in an {@code implements} clause. Populated lists are exposed verbatim; when
 * none are supplied the list defaults to empty (never {@code null}).
 */
class MessageInterfacesTest {

  @Test
  void messageExposesInterfacesInDeclarationOrder() {
    Message m =
        MessageBuilder.builder()
            .name("Sample")
            .type(TypeRef.named("bijnagte.recordbuf", "Sample"))
            .fields(List.of())
            .nestedMessages(List.of())
            .nestedEnums(List.of())
            .deprecated(false)
            .comment(null)
            .interfaces(
                List.of(TypeRef.of("java.lang.Comparable"), TypeRef.of("com.example.Marker")))
            .build();

    assertEquals(
        List.of(TypeRef.of("java.lang.Comparable"), TypeRef.of("com.example.Marker")),
        m.interfaces());
  }

  @Test
  void messageDefaultsToEmptyInterfaceList() {
    // No interfaces() call: the compact constructor normalizes a null list to empty.
    Message m =
        MessageBuilder.builder()
            .name("Plain")
            .type(TypeRef.named("bijnagte.recordbuf", "Plain"))
            .fields(List.of())
            .nestedMessages(List.of())
            .nestedEnums(List.of())
            .deprecated(false)
            .comment(null)
            .build();

    assertTrue(m.interfaces().isEmpty());
  }

  @Test
  void enumExposesInterfacesInDeclarationOrder() {
    Enum e =
        EnumBuilder.builder()
            .name("Color")
            .constants(List.of())
            .deprecated(false)
            .comment(null)
            .interfaces(List.of(TypeRef.of("com.example.Named")))
            .build();

    assertEquals(List.of(TypeRef.of("com.example.Named")), e.interfaces());
  }

  @Test
  void enumDefaultsToEmptyInterfaceList() {
    Enum e =
        EnumBuilder.builder()
            .name("Plain")
            .constants(List.of())
            .deprecated(false)
            .comment(null)
            .build();

    assertTrue(e.interfaces().isEmpty());
    // And the canonical accessors keep returning a real, non-null collection.
    assertInstanceOf(List.class, e.interfaces());
  }
}
