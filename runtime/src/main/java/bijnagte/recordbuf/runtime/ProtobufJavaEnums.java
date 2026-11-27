package bijnagte.recordbuf.runtime;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The enum numbers no constant has, on their way from the record back to protobuf-java. A record's
 * enum cannot hold such a number, so the record keeps it as a varint in its unknown-field carrier,
 * where it re-emits on encode (the Interop {@code from} puts it there, from generated per-field
 * checks); this class moves it from the carrier into the protobuf-java enum field, which keeps it
 * as {@code UNRECOGNIZED}.
 */
public final class ProtobufJavaEnums {

  private static final FieldDescriptor[] NONE = new FieldDescriptor[0];

  private static final ConcurrentHashMap<Descriptor, FieldDescriptor[]> ENUM_FIELDS =
      new ConcurrentHashMap<>();

  private ProtobufJavaEnums() {}

  /**
   * Moves the enum numbers a carrier holds for enum fields of the message back into those
   * protobuf-java fields.
   *
   * @param builder the protobuf-java builder being filled
   * @param carrier the record's carrier
   * @return the carrier without the entries that were moved
   */
  public static UnknownFields restore(Message.Builder builder, UnknownFields carrier) {
    if (carrier.isEmpty()) {
      return carrier;
    }
    FieldDescriptor[] fields = enumFields(builder.getDescriptorForType());
    if (fields.length == 0) {
      return carrier;
    }
    List<UnknownField> rest = new ArrayList<>();
    for (UnknownField entry : carrier.fields()) {
      FieldDescriptor field = find(fields, entry.fieldNumber());
      if (field == null || entry.wireType() != WireType.I32) {
        rest.add(entry);
        continue;
      }
      EnumValueDescriptor value =
          field.getEnumType().findValueByNumberCreatingIfUnknown((int) entry.asLong());
      if (field.isRepeated()) {
        builder.addRepeatedField(field, value);
      } else {
        builder.setField(field, value);
      }
    }
    return rest.size() == carrier.fields().size() ? carrier : UnknownFields.of(rest);
  }

  private static @Nullable FieldDescriptor find(FieldDescriptor[] fields, int number) {
    for (FieldDescriptor field : fields) {
      if (field.getNumber() == number) {
        return field;
      }
    }
    return null;
  }

  private static FieldDescriptor[] enumFields(Descriptor descriptor) {
    return ENUM_FIELDS.computeIfAbsent(
        descriptor,
        d -> {
          List<FieldDescriptor> found = new ArrayList<>();
          for (FieldDescriptor field : d.getFields()) {
            if (field.getJavaType() == FieldDescriptor.JavaType.ENUM
                && !field.getEnumType().isClosed()) {
              found.add(field);
            }
          }
          return found.isEmpty() ? NONE : found.toArray(new FieldDescriptor[0]);
        });
  }
}
