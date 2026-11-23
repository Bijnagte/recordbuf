package bijnagte.recordbuf.fixture.golden.format;

import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.util.JsonFormat;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * The proto3 JSON mapping of a record, as {@code .proto3.json} goldens, produced by protobuf-java's
 * own {@link JsonFormat} rather than by recordbuf's Jackson support.
 *
 * <p>The record crosses to protobuf-java through its generated nested {@code Interop} converter
 * (emitted when the message carries {@code (recordbuf.protobuf_java)}): {@link #serialize} is
 * {@code Interop.to}, then {@link JsonFormat#printer()}; {@link #deserialize} is {@link
 * JsonFormat#parser()} into a builder of the reference class (see {@link ReferenceTypes}), then
 * {@code Interop.from}. The format applies only to records that have an {@code Interop}.
 *
 * <p>The printer is the default proto3 one (pretty-printed, 64-bit integers as JSON strings, bytes
 * as base64, enums by name) plus {@code sortingMapKeys()}: a record's map is a {@code HashMap} (or
 * a randomly ordered {@code Map.of}), so without sorting the member order of a map field would
 * change from run to run and break the order-sensitive golden comparison.
 */
public final class Proto3JsonFormat implements GoldenFormat.Json {

  private static final String INTEROP_SIMPLE_NAME = "Interop";

  private final JsonFormat.TypeRegistry typeRegistry;

  /** A format whose printer and parser resolve no {@code Any} type, as the golden corpus needs. */
  public Proto3JsonFormat() {
    this(JsonFormat.TypeRegistry.getEmptyTypeRegistry());
  }

  /**
   * A format whose printer and parser resolve the {@code Any} types in {@code typeRegistry}, as the
   * conformance mirror needs for {@code Any} scenarios.
   */
  public Proto3JsonFormat(JsonFormat.TypeRegistry typeRegistry) {
    this.typeRegistry = typeRegistry;
  }

  @Override
  public String extension() {
    return "proto3.json";
  }

  @Override
  public Optional<String> notApplicableReason(Object record) {
    Class<?> recordType = record.getClass();
    if (interopOf(recordType).isEmpty()) {
      return Optional.of(
          recordType.getName()
              + " has no generated Interop converter (the message does not carry"
              + " (recordbuf.protobuf_java)), so it cannot cross to protobuf-java for proto3 JSON");
    }
    return ReferenceTypes.missingReason(recordType);
  }

  @Override
  public String serialize(Object record) throws Exception {
    Class<?> recordType = record.getClass();
    Method to = requireInterop(recordType).getMethod("to", recordType);
    Message reference = (Message) invoke(to, record);
    return JsonFormat.printer().usingTypeRegistry(typeRegistry).sortingMapKeys().print(reference);
  }

  @Override
  public Object deserialize(String goldenText, Class<?> recordType) throws Exception {
    Class<? extends MessageLite> referenceType =
        ReferenceTypes.referenceFor(recordType)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    ReferenceTypes.missingReason(recordType).orElse(recordType.getName())));
    Method from = requireInterop(recordType).getMethod("from", referenceType);
    Message.Builder builder = (Message.Builder) referenceType.getMethod("newBuilder").invoke(null);
    JsonFormat.parser().usingTypeRegistry(typeRegistry).merge(goldenText, builder);
    return invoke(from, builder.build());
  }

  private static Class<?> requireInterop(Class<?> recordType) {
    return interopOf(recordType)
        .orElseThrow(() ->
            new IllegalArgumentException(
                recordType.getName() + " has no generated Interop converter"));
  }

  /** The one place that locates the generated {@code Interop} nested in a record, if any. */
  private static Optional<Class<?>> interopOf(Class<?> recordType) {
    return Arrays.stream(recordType.getDeclaredClasses())
        .filter(nested -> nested.getSimpleName().equals(INTEROP_SIMPLE_NAME))
        .findFirst();
  }

  private static Object invoke(Method staticMethod, Object... arguments) throws Exception {
    try {
      return staticMethod.invoke(null, arguments);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
  }
}
