package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.Bytes;
import tools.jackson.databind.module.SimpleModule;

/**
 * The recordbuf Jackson module: registers the proto3 {@code bytes} mapping ({@link Bytes} as a
 * base64 string, via {@link JsonBytes}). It is listed in {@code
 * META-INF/services/tools.jackson.databind.JacksonModule}, so Jackson 3's service discovery picks
 * it up and a bare {@code new JsonMapper()} needs no manual registration. Generated records also
 * pin the pair per field, so they keep the contract even under a mapper that skipped discovery.
 */
public final class RecordbufJsonModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  /** Creates the module (the public no-arg constructor service discovery requires). */
  public RecordbufJsonModule() {
    super("RecordbufJsonModule");
    addSerializer(Bytes.class, new JsonBytes.B64Serializer());
    addDeserializer(Bytes.class, new JsonBytes.B64Deserializer());
  }
}
