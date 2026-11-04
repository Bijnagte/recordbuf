package bijnagte.recordbuf.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.Function;

/**
 * The gRPC-agnostic core marshaller for a record type {@code T} that implements {@link
 * ProtobufMessage}.
 *
 * <p>Its two-method shape — {@code read(InputStream) throws IOException} and {@code write(T,
 * OutputStream) throws IOException} — is signature-identical to {@code
 * io.grpc.MethodDescriptor.Marshaller}, so an optional io.grpc adapter binds a {@link
 * ProtobufMarshaller} to a gRPC method with a thin delegate and no other machinery. The marshaller
 * itself has no gRPC or protobuf dependency, so users who are not on gRPC pay for nothing.
 *
 * <p>The write half needs no per-type handle — every {@link ProtobufMessage} carries its own {@link
 * ProtobufMessage#toByteArray()}. The read half is reached by a {@code Function<byte[], T>} decoder
 * (a record type's generated static {@code fromBytes}); a static cannot ride a polymorphic
 * interface, so the constructor takes the decoder explicitly rather than inferring it.
 *
 * @param <T> the record type being marshalled
 */
public final class ProtobufMarshaller<T extends ProtobufMessage> {

  private final Function<byte[], T> decode;

  private ProtobufMarshaller(Function<byte[], T> decode) {
    this.decode = Objects.requireNonNull(decode, "decode");
  }

  /**
   * Build a marshaller for record type {@code T} from its generated {@code fromBytes} decoder.
   *
   * @param decode the record type's static byte-array decoder
   * @return a marshaller of that type
   */
  public static <T extends ProtobufMessage> ProtobufMarshaller<T> of(Function<byte[], T> decode) {
    return new ProtobufMarshaller<>(decode);
  }

  /**
   * Write a record's wire bytes to {@code out}.
   *
   * @param value the record to wire out (never {@code null})
   * @param out the destination stream (never {@code null})
   * @throws IOException if the encode or stream write fails
   */
  public void write(T value, OutputStream out) throws IOException {
    out.write(Objects.requireNonNull(value, "value").toByteArray());
  }

  /**
   * Read and decode a record from {@code in}.
   *
   * @param in the source stream (never {@code null})
   * @return the decoded record
   * @throws IOException if the decode or stream read fails
   */
  public T read(InputStream in) throws IOException {
    return decode.apply(Objects.requireNonNull(in, "in").readAllBytes());
  }
}
