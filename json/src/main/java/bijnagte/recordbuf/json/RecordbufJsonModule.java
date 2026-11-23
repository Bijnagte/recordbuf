package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import tools.jackson.databind.module.SimpleModule;

/**
 * The recordbuf Jackson module: registers recordbuf's JSON mappings for the runtime value types —
 * {@link Bytes} as a base64 string ({@link JsonBytes}), {@link FieldMask} as an array of its
 * lowerCamel paths ({@link JsonFieldMask}), and {@link Empty} as {@code {}} ({@link JsonEmpty}).
 *
 * <p>It is listed in {@code META-INF/services/tools.jackson.databind.JacksonModule}, so Jackson 3's
 * opt-in service discovery ({@code JsonMapper.builder().findAndAddModules()}) picks it up; it can
 * also be registered directly with {@code addModule(new RecordbufJsonModule())}. A bare {@code new
 * JsonMapper()} does not run discovery (verified against 3.1.8), which is why generated records pin
 * each pair per field: they keep the contract under any mapper, and the module covers hand-written
 * types holding these values.
 */
public final class RecordbufJsonModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  /** Creates the module (the public no-arg constructor service discovery requires). */
  public RecordbufJsonModule() {
    super("RecordbufJsonModule");
    addSerializer(Bytes.class, new JsonBytes.B64Serializer());
    addDeserializer(Bytes.class, new JsonBytes.B64Deserializer());
    addSerializer(FieldMask.class, new JsonFieldMask.Serializer());
    addDeserializer(FieldMask.class, new JsonFieldMask.Deserializer());
    addSerializer(Empty.class, new JsonEmpty.Serializer());
    addDeserializer(Empty.class, new JsonEmpty.Deserializer());
  }
}
