package bijnagte.recordbuf.avro;

import bijnagte.recordbuf.runtime.UnknownField;
import bijnagte.recordbuf.runtime.UnknownFields;
import bijnagte.recordbuf.runtime.WireType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.avro.generic.IndexedRecord;
import org.jspecify.annotations.Nullable;

/**
 * The conversion between a record's {@link UnknownFields} carrier and the {@code unknownFields}
 * array of its Avro twin: a list of {@code RecordbufUnknownField} entry records ({@code number},
 * {@code wireType}, {@code value}, in that field order). Generated converters call it, passing the
 * entry record's constructor, so the generated code names no entry accessor.
 *
 * <p>The value is always the {@code bytes} branch of the entry's union and holds the exact post-tag
 * value bytes of the capture, so a field re-emits the same bytes on encode after an Avro round
 * trip.
 */
public final class UnknownFieldsAvro {

  private static final int NUMBER = 0;
  private static final int WIRE_TYPE = 1;
  private static final int VALUE = 2;

  private UnknownFieldsAvro() {}

  /**
   * The Avro entry records of a carrier, one per captured field, in wire order.
   *
   * @param unknown the record's carrier
   * @param entry the constructor of the entry record
   * @return the entries; empty for an empty carrier
   */
  public static <E extends IndexedRecord> List<E> toAvro(UnknownFields unknown, Supplier<E> entry) {
    List<E> entries = new ArrayList<>();
    for (UnknownField field : unknown.fields()) {
      E record = entry.get();
      record.put(NUMBER, field.fieldNumber());
      record.put(WIRE_TYPE, field.wireType().name());
      record.put(VALUE, ByteBuffer.wrap(field.value()));
      entries.add(record);
    }
    return entries;
  }

  /**
   * The carrier an Avro record's entry records describe.
   *
   * @param entries the {@code unknownFields} array, or {@code null} for a specific record built by
   *     hand that left it unset
   * @return the carrier, with its own copy of every value
   * @throws IllegalArgumentException if an entry's value is not its bytes branch
   */
  public static UnknownFields fromAvro(@Nullable List<? extends IndexedRecord> entries) {
    if (entries == null) {
      return UnknownFields.empty();
    }
    List<UnknownField> fields = new ArrayList<>();
    for (IndexedRecord entry : entries) {
      int number = (int) entry.get(NUMBER);
      WireType wireType = WireType.valueOf(entry.get(WIRE_TYPE).toString());
      if (!(entry.get(VALUE) instanceof ByteBuffer buffer)) {
        throw new IllegalArgumentException(
            "unknown field " + number + " holds " + entry.get(VALUE) + ", expected the bytes");
      }
      ByteBuffer view = buffer.duplicate();
      byte[] bytes = new byte[view.remaining()];
      view.get(bytes);
      fields.add(new UnknownField(number, wireType, bytes));
    }
    return UnknownFields.of(fields);
  }
}
