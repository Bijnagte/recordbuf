package bijnagte.recordbuf.fixture.golden;

import bijnagte.recordbuf.fixture.golden.GoldenCase.NotApplicable;
import bijnagte.recordbuf.fixture.golden.GoldenCase.Outcome;
import bijnagte.recordbuf.fixture.golden.format.AvroBinaryFormat;
import bijnagte.recordbuf.fixture.golden.format.AvroJsonFormat;
import bijnagte.recordbuf.fixture.golden.format.JacksonFormat;
import bijnagte.recordbuf.fixture.golden.format.Proto3JsonFormat;
import bijnagte.recordbuf.fixture.golden.format.ProtobufBinaryFormat;
import bijnagte.recordbuf.fixture.golden.format.ReferenceTypes;
import bijnagte.recordbuf.runtime.ProtobufMessage;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds missing goldens, and only missing ones. For every registered case and every format the case
 * applies to, the golden {@code <root>/<family>/<case>.<extension>} is written if it is absent. A
 * golden that exists is left byte-identical and reported as skipped; nothing is ever overwritten,
 * so a frozen golden stays frozen.
 *
 * <p>Each format is seeded from the independent source named for it, not from the suite's own
 * adapter where the two differ:
 *
 * <ul>
 *   <li>protobuf binary: the record's wire bytes parsed by the protobuf-java reference message,
 *       then protobuf-java's own {@code toByteArray()};
 *   <li>proto3 JSON: protobuf-java's {@code JsonFormat} printer (map keys sorted) on that same
 *       reference message, so the golden does not come from recordbuf's {@code Interop} converter;
 *   <li>Avro binary and Avro JSON: Avro's encoders, via the Avro adapters, which are those encoders
 *       over the specific record with its maps in key order;
 *   <li>Jackson JSON: recordbuf's own mapper output through {@link JacksonFormat}. There is no
 *       independent source for it, so the report flags every Jackson golden for human review.
 * </ul>
 *
 * <p>Run it with {@code ./gradlew :recordbuf-fixtures:seedGoldens}. It is not part of {@code
 * check}. The command logs the report through SLF4J at INFO; the slf4j-simple binding writes that
 * to standard error.
 */
public final class GoldenSeeder {

  private static final Logger LOG = LoggerFactory.getLogger(GoldenSeeder.class);

  private GoldenSeeder() {}

  /** A golden the seeder wrote. */
  public record Created(String family, String name, String extension, boolean needsReview) {

    /** Returns the golden's path relative to the golden root. */
    public String relativePath() {
      return family + "/" + name + "." + extension;
    }
  }

  /** A case/format pair the format does not apply to, so nothing was written. */
  public record Inapplicable(String family, String name, String extension, String reason) {}

  /**
   * What a seeding run did.
   *
   * @param created the goldens written
   * @param skippedExisting the relative paths of goldens that already existed and were left alone
   * @param notApplicable the pairs that were skipped because the format does not apply
   */
  public record Report(
      List<Created> created, List<String> skippedExisting, List<Inapplicable> notApplicable) {

    /** Renders the report as plain text, one entry per line. */
    public String render() {
      StringBuilder text = new StringBuilder();
      text.append("created (").append(created.size()).append("):\n");
      for (Created golden : created) {
        text.append("  ")
            .append(golden.relativePath())
            .append(
                golden.needsReview()
                    ? "  REVIEW (Jackson: recordbuf's own output, no independent source)"
                    : "")
            .append('\n');
      }
      text.append("skipped, already exist (").append(skippedExisting.size()).append("):\n");
      for (String path : skippedExisting) {
        text.append("  ").append(path).append('\n');
      }
      text.append("not applicable (").append(notApplicable.size()).append("):\n");
      for (Inapplicable pair : notApplicable) {
        text.append("  ")
            .append(pair.family())
            .append('/')
            .append(pair.name())
            .append(" [")
            .append(pair.extension())
            .append("]: ")
            .append(pair.reason())
            .append('\n');
      }
      return text.toString();
    }
  }

  /**
   * Walks {@code sources} and writes every missing golden under {@code goldenRoot}.
   *
   * @throws IllegalStateException if a case's format has no seeding source
   * @throws IOException if a golden cannot be written, or a format fails to serialize a record
   */
  public static Report seed(Path goldenRoot, List<GoldenCaseSource> sources) throws Exception {
    GoldenStore store = GoldenStore.inDirectory(goldenRoot);
    List<Created> created = new ArrayList<>();
    List<String> skippedExisting = new ArrayList<>();
    List<Inapplicable> notApplicable = new ArrayList<>();
    for (GoldenCaseSource source : sources) {
      for (GoldenCase goldenCase : source.cases()) {
        for (GoldenFormat format : source.formats()) {
          String family = goldenCase.family();
          String name = goldenCase.name();
          String extension = format.extension();
          Outcome outcome = goldenCase.outcomeFor(format);
          if (outcome instanceof NotApplicable inapplicable) {
            notApplicable.add(new Inapplicable(family, name, extension, inapplicable.reason()));
          } else if (store.exists(family, name, extension)) {
            skippedExisting.add(family + "/" + name + "." + extension);
          } else {
            Path golden = goldenRoot.resolve(family).resolve(name + "." + extension);
            writeNew(golden, seedContent(format, goldenCase.record()));
            created.add(new Created(family, name, extension, format instanceof JacksonFormat));
          }
        }
      }
    }
    return new Report(
        List.copyOf(created), List.copyOf(skippedExisting), List.copyOf(notApplicable));
  }

  /**
   * Writes {@code content} to {@code golden}, creating its directory.
   *
   * @throws IllegalStateException if the golden already exists; it is left untouched
   */
  static void writeNew(Path golden, byte[] content) throws IOException {
    Files.createDirectories(golden.getParent());
    try {
      Files.write(golden, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (FileAlreadyExistsException e) {
      throw new IllegalStateException("refusing to overwrite the existing golden " + golden, e);
    }
  }

  /** Command line entry point: the single argument is the golden root directory. */
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: GoldenSeeder <golden root directory>");
    }
    LOG.info("{}", seed(Path.of(args[0]), GoldenCaseRegistry.sources()).render());
  }

  private static byte[] seedContent(GoldenFormat format, Object record) throws Exception {
    if (format instanceof ProtobufBinaryFormat) {
      return GoldenAssertions.encodeHex(referenceMessage(record).toByteArray())
          .getBytes(StandardCharsets.UTF_8);
    }
    if (format instanceof Proto3JsonFormat proto3Json) {
      String printed = proto3Json.printReference((Message) referenceMessage(record));
      return (printed + "\n").getBytes(StandardCharsets.UTF_8);
    }
    if (format instanceof AvroBinaryFormat avroBinary) {
      return GoldenAssertions.encodeHex(avroBinary.serialize(record))
          .getBytes(StandardCharsets.UTF_8);
    }
    if (format instanceof AvroJsonFormat avroJson) {
      return prettyJson(avroJson.serialize(record));
    }
    if (format instanceof JacksonFormat jackson) {
      return prettyJson(jackson.serialize(record));
    }
    throw new IllegalStateException(
        "no seeding source for format ." + format.extension() + " (" + format.getClass() + ")");
  }

  private static byte[] prettyJson(String json) {
    return (GoldenAssertions.normalizeJson(json) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** The protobuf-java message equivalent to the record: its wire bytes parsed by the reference. */
  private static MessageLite referenceMessage(Object record) throws Exception {
    if (!(record instanceof ProtobufMessage message)) {
      throw new IllegalArgumentException(
          record.getClass().getName() + " is not a recordbuf ProtobufMessage record");
    }
    Class<? extends MessageLite> reference =
        ReferenceTypes.referenceFor(record.getClass())
            .orElseThrow(() ->
                new IllegalStateException(
                    ReferenceTypes.missingReason(record.getClass())
                        .orElse(record.getClass().getName())));
    MessageLite defaultInstance =
        (MessageLite) reference.getMethod("getDefaultInstance").invoke(null);
    return defaultInstance.getParserForType().parseFrom(message.toByteArray());
  }
}
