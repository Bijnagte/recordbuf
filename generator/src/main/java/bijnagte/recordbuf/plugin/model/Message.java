package bijnagte.recordbuf.plugin.model;

import bijnagte.recordbuf.codegen.source.TypeRef;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a protobuf message.
 *
 * @param name short name (last path component)
 * @param type the record's own Java type as a {@link TypeRef.Named} — the declaring java package
 *     plus this message's short name, with any enclosing messages carried in its nesting chain (a
 *     nested message {@code Outer.Inner} is built as {@code ...Outer.sub("Inner")}). The writer
 *     layer reads it in place of reconstructing {@code named(pkg, cap(name))} per site; its {@link
 *     TypeRef.Named#fqn() fqn} is the fully-qualified name within this file.
 * @param fields direct fields, exclusive of oneof members: real {@code oneof{...}} members are
 *     routed into {@link #oneofs} (one nested sealed interface per oneof), while a proto3 {@code
 *     optional} scalar/enum's synthetic oneof stays a top-level field here. Map fields are expanded
 *     per {@link Field#isMap()}.
 * @param nestedMessages messages declared inside this one
 * @param nestedEnums enums declared inside this one
 * @param oneofs the real {@code oneof{...}} groups declared inside this one, in declaration order,
 *     each carrying its own members (never {@code null}; empty when the message has none). A
 *     proto3-optional scalar/enum is NOT an entry here (its synthetic oneof is skipped by the
 *     loader).
 * @param deprecated {@code option deprecated = true}
 * @param comment leading doc comment, if captured
 * @param interfaces the fully-typed Java interfaces the record should emit in an {@code implements}
 *     clause, as {@link TypeRef.Named} references, in declaration order and de-duplicated (never
 *     {@code null}); empty when none are declared. Parsed from the {@code recordbuf.java_interface}
 *     option's FQN strings.
 * @param protobufJava the java_package that hosts the equivalent protobuf-java reference {@code X}
 *     for a Layer B structural converter ({@code Interop.from}/{@code Interop.to}), or {@code null}
 *     when no converter is opt-ed in. {@code null} for every message except those that declare
 *     {@code recordbuf.protobuf_java}.
 */
@RecordBuilder
public record Message(
    String name,
    TypeRef.Named type,
    List<Field> fields,
    List<Message> nestedMessages,
    List<Enum> nestedEnums,
    List<Oneof> oneofs,
    boolean deprecated,
    @Nullable String comment,
    List<TypeRef> interfaces,
    @Nullable String protobufJava) {
  public boolean hasNested() {
    return !nestedMessages.isEmpty() || !nestedEnums.isEmpty();
  }

  /**
   * Enforce the never-{@code null}, empty-default invariant on {@link #interfaces} even when a
   * caller (or the record-builder's default initialization) supplies {@code null}, and fold a blank
   * {@link #protobufJava} to {@code null} so "opted in" is exactly "non-null package".
   */
  public Message {
    if (interfaces == null) interfaces = List.of();
    if (oneofs == null) oneofs = List.of();
    if (protobufJava != null && protobufJava.isEmpty()) protobufJava = null;
  }
}
