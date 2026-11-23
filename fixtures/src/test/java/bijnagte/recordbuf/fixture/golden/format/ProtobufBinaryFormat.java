package bijnagte.recordbuf.fixture.golden.format;

import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import bijnagte.recordbuf.runtime.ProtobufMessage;
import com.google.protobuf.MessageLite;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

/**
 * The protobuf binary wire format of a record, as {@code .pb.hex} goldens.
 *
 * <p>{@link #serialize} is the record's own codec. {@link #deserialize} runs two checks on the
 * golden bytes, in this order:
 *
 * <ol>
 *   <li>the live protobuf-java oracle: the golden is parsed by the reference message class (see
 *       {@link ReferenceTypes}) and re-serialized, and the result must equal the golden byte for
 *       byte. This proves the golden is exactly what protobuf-java itself writes, so a golden can
 *       never encode a recordbuf quirk;
 *   <li>the record's generated {@code fromBytes} decodes the golden.
 * </ol>
 *
 * The format applies only to records that have a reference class.
 */
public final class ProtobufBinaryFormat implements GoldenFormat.Binary {

  @Override
  public String extension() {
    return "pb.hex";
  }

  @Override
  public Optional<String> notApplicableReason(Object record) {
    return ReferenceTypes.missingReason(record.getClass());
  }

  @Override
  public byte[] serialize(Object record) throws Exception {
    if (!(record instanceof ProtobufMessage message)) {
      throw new IllegalArgumentException(
          record.getClass().getName() + " is not a recordbuf ProtobufMessage record");
    }
    return message.toByteArray();
  }

  @Override
  public Object deserialize(byte[] goldenBytes, Class<?> recordType) throws Exception {
    checkProtobufJavaOracle(goldenBytes, recordType);
    try {
      return recordType.getMethod("fromBytes", byte[].class).invoke(null, (Object) goldenBytes);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
  }

  private static void checkProtobufJavaOracle(byte[] goldenBytes, Class<?> recordType)
      throws Exception {
    Class<? extends MessageLite> reference =
        ReferenceTypes.referenceFor(recordType)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    ReferenceTypes.missingReason(recordType).orElse(recordType.getName())));
    MessageLite defaultInstance =
        (MessageLite) reference.getMethod("getDefaultInstance").invoke(null);
    byte[] reserialized = defaultInstance.getParserForType().parseFrom(goldenBytes).toByteArray();
    GoldenAssertions.assertBinaryMatches(
        goldenBytes,
        reserialized,
        "the protobuf-java oracle check of "
            + reference.getName()
            + " (golden bytes vs protobuf-java parse + re-serialize)");
  }
}
