package bijnagte.recordbuf.runtime;

/**
 * The per-wire default-value table a decoded field's local starts from when the field is absent on
 * the wire, plus the encode-side "is this the default?" comparison. Pulled out of the generated
 * codec so the value literals live in one runtime place; the per-message decode {@code switch} that
 * consumes them stays generated.
 */
public final class CodecDefaults {

  private CodecDefaults() {}

  /** int32/uint32/sint32/sfixed32 and fixed32 default. */
  public static int int32() {
    return 0;
  }

  /** int64/uint64/sint64/sfixed64 and fixed64 default. */
  public static long int64() {
    return 0L;
  }

  /** float default. */
  public static float float_() {
    return 0.0f;
  }

  /** double default. */
  public static double double_() {
    return 0.0;
  }

  /** bool default. */
  public static boolean bool() {
    return false;
  }

  /** string default. */
  public static String string() {
    return "";
  }

  /** bytes default. */
  public static byte[] bytes() {
    return new byte[0];
  }
}
