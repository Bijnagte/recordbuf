package bijnagte.recordbuf.fixture.golden.format;

import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;

/**
 * The canonical Avro JSON encoding of a record, as {@code .avro.json} goldens.
 *
 * <p>{@link #serialize} converts the record to its Avro specific record through the generated
 * {@code <Record>AvroConverter} (see {@link AvroTypes}), orders its maps, and writes it with Avro's
 * official {@link JsonEncoder} (non-pretty): members in schema order, a union value wrapped in a
 * one-member object named by its branch. {@link #deserialize} reads golden text with Avro's {@code
 * JsonDecoder} and converts back through the same converter. This is the encoding the {@code
 * canonicalAvroJson} helper of {@code JacksonDynamicWktTest} produces, so the existing {@code
 * json/jackson/avro} goldens are reused unchanged.
 *
 * <p>The goldens are pretty-printed for review while the encoder is compact; Avro's {@code
 * JsonDecoder} is a streaming Jackson parser and skips whitespace, so the golden text is decoded as
 * it is, and the matrix's JSON comparison normalizes the whitespace of both sides.
 *
 * <p>The encoder writes an Avro map in the iteration order of the specific record's {@code Map},
 * and the record's own maps have no defined order. So {@link #serialize} first puts every map of
 * the specific record into key order ({@link AvroTypes#withOrderedMaps}), which makes the text
 * identical from run to run for the same record value.
 *
 * <p>Avro refuses to read or write a specific class it does not trust, so both directions first
 * make sure the specific record and the classes its schema reaches are trusted ({@link
 * AvroTypes.Binding#ensureTrusted}); cases carry no trust setup of their own.
 *
 * <p>The format applies only to records that have a generated converter.
 */
public final class AvroJsonFormat implements GoldenFormat.Json {

  @Override
  public String extension() {
    return "avro.json";
  }

  @Override
  public Optional<String> notApplicableReason(Object record) {
    return AvroTypes.missingReason(record.getClass());
  }

  @Override
  public String serialize(Object record) throws Exception {
    AvroTypes.Binding binding = AvroTypes.requireBinding(record.getClass());
    binding.ensureTrusted();
    Object specific = binding.fromRecordWithOrderedMaps(record);
    Schema schema = schemaOf(binding);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JsonEncoder encoder = EncoderFactory.get().jsonEncoder(schema, out, false);
    new SpecificDatumWriter<Object>(schema).write(specific, encoder);
    encoder.flush();
    return out.toString(StandardCharsets.UTF_8);
  }

  @Override
  public Object deserialize(String goldenText, Class<?> recordType) throws Exception {
    AvroTypes.Binding binding = AvroTypes.requireBinding(recordType);
    binding.ensureTrusted();
    Schema schema = schemaOf(binding);
    Object specific =
        new SpecificDatumReader<Object>(schema)
            .read(null, DecoderFactory.get().jsonDecoder(schema, goldenText));
    return binding.toRecord(specific);
  }

  private static Schema schemaOf(AvroTypes.Binding binding) throws ReflectiveOperationException {
    return (Schema) binding.specificType().getMethod("getClassSchema").invoke(null);
  }
}
