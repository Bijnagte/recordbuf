package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeResolver;
import java.util.ArrayList;

/**
 * Emits a oneof's nested sealed interface family inside its enclosing record.
 *
 * <p>Each real {@code oneof{...}} group becomes one nested {@code sealed interface} ({@link
 * Oneof#ifaceName()}) inside the message record. Non-message members get a dedicated nested {@code
 * record <Name>Value(<Type> value)} — no tag field, the codec hardcodes the tag in its write
 * switch. Message members become <em>identity</em> subtypes: their own record gains the interface
 * in its {@code implements} clause through the K6 registry in {@code CodeGenerator} (their {@code
 * value()} method is inherited from the interface's default, returning {@code this}).
 *
 * <p>The interface extends a runtime {@code Unwrapable<T>} so consumers can extract the underlying
 * value via {@code u.value()} without knowing which wrapper was used. A member's presence is read
 * off the interface reference on the record: {@code null} means the oneof was absent off the wire.
 */
public final class OneofGenerator {

  private static final String RT_UNWRAPABLE = "bijnagte.recordbuf.runtime.Unwrapable";

  private final TypeResolver types;
  private final TypeImports ci;

  public OneofGenerator(TypeResolver types, TypeImports ci) {
    this.types = types;
    this.ci = ci;
  }

  /**
   * Emit {@code public sealed <ifaceName> extends Unwrapable<Object> permits <per-member-records>,
   * <message-members> { ... }} at the record body's current indent, with one nested record per
   * non-message member and the shared default {@code value()} method.
   */
  public void write(JavaWriter w, Message enclosing, Oneof oneof) {
    String iface = oneof.ifaceName();
    String unwrapable = ci.use(RT_UNWRAPABLE) + "<Object>";

    w.javadoc(
        w.indent(),
        "The '"
            + oneof.name()
            + "' oneof interface. Non-message members get a "
            + "dedicated nested record; message members are identity subtypes added to this"
            + " interface's "
            + "{@code permits} clause by the K6 registry in "
            + "{@code CodeGenerator}. The record holds one @Nullable "
            + iface
            + " component per oneof; null means the oneof was absent off the wire.");
    // Build the permits clause: one per-member record for every non-message
    // member, plus every message member's element type (short + imported for
    // cross-file via the TypeResolver).
    var permits = new ArrayList<String>();
    for (var member : oneof.members()) {
      if (member.isMessage()) {
        permits.add(ci.use(types.resolveElement(member)));
      } else {
        permits.add(iface + "." + JavaNames.cap(member.name()) + "Value");
      }
    }
    w.line(
        "public sealed interface "
            + iface
            + " extends "
            + unwrapable
            + " permits "
            + String.join(", ", permits)
            + " {");
    int ind = w.indent() + 1;
    w.indent(ind);
    // Default value() implementation: message-member subtypes inherit this
    // (returning themselves); per-member scalar records override it via their
    // own record component (a covariant return). It overrides the Unwrapable
    // interface's value(), so @Override is correct (keeps the EP check set clean).
    w.line("@Override");
    w.line("default Object value() { return this; }");
    // One nested record per non-message member. Each carries its underlying
    // value and satisfies Unwrapable.value() via its record component.
    for (var f : oneof.members()) {
      if (f.isMessage()) continue;
      String recName = JavaNames.cap(f.name()) + "Value";
      String javaType = ci.use(types.resolveElement(f));
      w.line();
      w.line("public record " + recName + "(" + javaType + " value) implements " + iface + " {}");
    }
    w.indent(w.indent() - 1);
    w.line("}");
  }
}
