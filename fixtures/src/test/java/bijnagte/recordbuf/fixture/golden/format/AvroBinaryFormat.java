package bijnagte.recordbuf.fixture.golden.format;

import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * The Avro binary encoding of a record, as {@code .avro.hex} goldens.
 *
 * <p>{@link #serialize} converts the record to its Avro specific record through the generated
 * {@code <Record>AvroConverter} (see {@link AvroTypes}) and encodes it with the specific record's
 * own {@code toByteBuffer}; {@link #deserialize} decodes with its {@code fromByteBuffer} and
 * converts back through the same converter.
 *
 * <p>The encoding is Avro's <em>single-object</em> encoding: the two marker bytes {@code C3 01},
 * the 8-byte CRC-64-AVRO fingerprint of the writer schema, then the raw binary datum. It is the
 * encoding the existing {@code avro/dyn-representative.avro} golden was written in, so that golden
 * is reused unchanged. The fingerprint prefix makes a golden depend on the schema as well as the
 * data, which is wanted here: a schema change must show up as a golden change.
 *
 * <p>Avro writes a map in the iteration order of the specific record's {@code Map}, and the
 * record's own maps have no defined order. So {@link #serialize} first puts every map of the
 * specific record into key order ({@link AvroTypes#withOrderedMaps}), which makes the bytes
 * identical from run to run for the same record value.
 *
 * <p>Avro refuses to read or write a specific class it does not trust, so both directions first
 * make sure the specific record and the classes its schema reaches are trusted ({@link
 * AvroTypes.Binding#ensureTrusted}); cases carry no trust setup of their own.
 *
 * <p>The format applies only to records that have a generated converter.
 */
public final class AvroBinaryFormat implements GoldenFormat.Binary {

  @Override
  public String extension() {
    return "avro.hex";
  }

  @Override
  public Optional<String> notApplicableReason(Object record) {
    return AvroTypes.missingReason(record.getClass());
  }

  @Override
  public byte[] serialize(Object record) throws Exception {
    AvroTypes.Binding binding = AvroTypes.requireBinding(record.getClass());
    binding.ensureTrusted();
    Object specific = binding.fromRecordWithOrderedMaps(record);
    ByteBuffer encoded;
    try {
      encoded = (ByteBuffer) binding.specificType().getMethod("toByteBuffer").invoke(specific);
    } catch (InvocationTargetException e) {
      throw unwrap(e);
    }
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }

  @Override
  public Object deserialize(byte[] goldenBytes, Class<?> recordType) throws Exception {
    AvroTypes.Binding binding = AvroTypes.requireBinding(recordType);
    binding.ensureTrusted();
    Object specific;
    try {
      specific =
          binding
              .specificType()
              .getMethod("fromByteBuffer", ByteBuffer.class)
              .invoke(null, ByteBuffer.wrap(goldenBytes));
    } catch (InvocationTargetException e) {
      throw unwrap(e);
    }
    return binding.toRecord(specific);
  }

  private static Exception unwrap(InvocationTargetException e) {
    return e.getCause() instanceof Exception cause ? cause : e;
  }
}
