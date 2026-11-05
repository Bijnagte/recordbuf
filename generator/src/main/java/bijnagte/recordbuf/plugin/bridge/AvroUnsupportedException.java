package bijnagte.recordbuf.plugin.bridge;

/**
 * Raised by the Avro bridge when a type the {@code .proto} declares has no representable Avro
 * mapping (v1: {@code uint64} scalars, {@code map<K,V>} where the key is non-string or the value is
 * a message/enum, and the dynamic well-known types {@code Struct}/{@code Value}/{@code
 * ListValue}/{@code Any}). This is one of the FEAT's <em>fail-loud</em> points (R11/R17): rather
 * than emit malformed or wrong-typed Avro, the generation aborts with a clear message.
 */
public final class AvroUnsupportedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * @param protoName the offending proto type or field
   * @param reason why it cannot be bridged
   */
  public AvroUnsupportedException(String protoName, String reason) {
    super("avro bridge: " + protoName + " -> " + reason);
  }
}
