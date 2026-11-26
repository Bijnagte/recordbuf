package bijnagte.recordbuf.plugin.model;

import bijnagte.recordbuf.codegen.source.TypeRef;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a real protobuf {@code oneof} inside a {@link Message}.
 *
 * <p>The oneof's members live here — and here only — and are routed <em>out</em> of the parent
 * {@link Message}'s flat {@link Message#fields() fields} list by the loader: one proto3 {@code
 * oneof {...}} group becomes exactly one nested sealed interface on the generated record (one
 * {@code @Nullable} component, K4), and a proto3-<em>optional</em> scalar/enum's <i>synthetic</i>
 * {@code oneofIndex} is NOT a real oneof, so its member stays a top-level field and no entry is
 * created for it.
 *
 * <p>The oneof's Java surface: a nested {@code sealed interface} named {@link #ifaceName()
 * ifaceName()} (one record component of that type per oneof) that permits, per member, either the
 * member's own message record (an identity subtype) or the one {@link #componentName()} wrapper
 * record that carries a scalar/enum member's value plus its discriminant (the member's field tag).
 *
 * @param name the oneof's proto name
 * @param iface the nested sealed interface's fully-typed reference — the owner record's reference
 *     with the capitalized oneof name as the final segment (e.g. {@code Compat.Choice}); the loader
 *     owns it, so writers consume it instead of re-deriving a name string
 * @param descriptor the raw protobuf {@link OneofDescriptorProto} for this group loader so the
 *     codec can recover a member field's raw protobuf type (e.g. whether a member is an {@code
 *     enum}) for encode/decode casts without leaking protobuf classes into the writers; never
 *     {@code null}
 * @param parentDescriptor the raw {@link DescriptorProto} of the enclosing (owner) message, carried
 *     so the codec can resolve a member's raw field descriptor by tag via {@code
 *     parentDescriptor.getField(number)} (each oneof member's tag is also registered on the owner
 *     message, which is how a scalar/enum member's wire type is recovered without walking the oneof
 *     itself); never {@code null}
 * @param members the oneof's declared members in declaration order, routed out of the parent's flat
 *     fields list; never {@code null} (empty is the never-actually-emitted case: proto3 rejects
 *     empty oneof groups)
 * @param jsonOneof the per-oneof {@code recordbuf.json_oneof} discriminator property name override,
 *     or {@code null} when the option is absent (the default is {@code <oneofName>}); never
 *     meaningful on a file without the {@code recordbuf.jackson} option (the loader fails loud on a
 *     knob without the toggle)
 */
@RecordBuilder
public record Oneof(
    String name,
    TypeRef.Named iface,
    OneofDescriptorProto descriptor,
    DescriptorProto parentDescriptor,
    List<Field> members,
    @Nullable String jsonOneof) {
  /**
   * Normalize a {@code null} members list to an empty one and keep the members list immutably owned
   * by this entry, mirroring {@link Message}'s invariant-on-its-owned-list style.
   */
  public Oneof {
    members = (members == null) ? List.of() : List.copyOf(members);
  }

  /**
   * The nested sealed interface's Java type name for this oneof — the first-letter capitalization
   * of the oneof's camelCased proto name. E.g. oneof {@code choice} -> {@code Choice}, oneof {@code
   * my_member} -> {@code MyMember}. One record component of this type per oneof (K4) and the
   * permits/implements surface (K6) key off it.
   */
  public String ifaceName() {
    return iface.shortName();
  }

  /**
   * The one record component's Java name and the value wrapper's nested record name for this oneof
   * — the camelCased proto name of the group. E.g. oneof {@code choice} -> component {@code
   * choice}; oneof {@code my_member} -> {@code myMember}. The wrapper record is nested inside
   * {@link #ifaceName()} as the shared {@code <iface>.Scalar(Object value, int tag)} type.
   */
  public String componentName() {
    return JavaNames.camel(name);
  }

  /**
   * The simple name of a oneof member's value record, nested in {@link #ifaceName()}: the
   * UpperCamelCase form of the member's camel-cased proto name plus {@code Value}. E.g. member
   * {@code oneof_uint32} -> {@code OneofUint32Value}, member {@code name} -> {@code NameValue}. The
   * record, codec and converter writers all name the record through this helper, so they agree.
   */
  public static String valueRecordName(Field member) {
    return JavaNames.cap(JavaNames.camel(member.name())) + "Value";
  }
}
