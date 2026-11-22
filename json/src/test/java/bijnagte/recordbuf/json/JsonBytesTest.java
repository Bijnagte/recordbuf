package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bijnagte.recordbuf.runtime.Bytes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code Bytes} ↔ base64 pair: discovered through {@link RecordbufJsonModule}'s service entry,
 * and pinned per field the way generated records pin it.
 */
class JsonBytesTest {

  /** A hand-written bean holding {@code Bytes}: nothing on it names the (de)serializers. */
  record Holder(@Nullable Bytes data) {}

  /** A generated-record-style component: the pair pinned per field, as the generator emits it. */
  record Pinned(
      @JsonSerialize(using = JsonBytes.B64Serializer.class)
          @JsonDeserialize(using = JsonBytes.B64Deserializer.class)
          @Nullable Bytes data) {}

  /** Service discovery only: the module is found through META-INF/services, never named here. */
  private static final JsonMapper BARE = JsonMapper.builder().findAndAddModules().build();

  @Test
  void serviceDiscoveryFindsTheModuleAndWritesBase64() {
    Holder h = new Holder(Bytes.of(new byte[] {1, 2, 3, (byte) 0xff}));

    String json = BARE.writeValueAsString(h);

    assertEquals("{\"data\":\"AQID/w==\"}", json);
    assertEquals(h, BARE.readValue(json, Holder.class));
  }

  @Test
  void perFieldPairWorksUnderAPlainMapperWithoutDiscovery() {
    JsonMapper plain = new JsonMapper();
    Pinned p = new Pinned(Bytes.of(new byte[] {1, 2, 3, (byte) 0xff}));

    String json = plain.writeValueAsString(p);

    assertEquals("{\"data\":\"AQID/w==\"}", json);
    assertEquals(p, plain.readValue(json, Pinned.class));
  }

  @Test
  void emptyAndNonUtf8PayloadsRoundTrip() {
    for (byte[] payload : new byte[][] {{}, {(byte) 0xc3, (byte) 0x28, 0, (byte) 0x80}}) {
      Holder h = new Holder(Bytes.of(payload));
      Holder back = BARE.readValue(BARE.writeValueAsString(h), Holder.class);
      assertArrayEquals(payload, back.data() == null ? null : back.data().toByteArray());
    }
  }

  @Test
  void jsonNullStaysNull() {
    assertNull(BARE.readValue("{\"data\":null}", Holder.class).data());
  }

  @Test
  void readsUrlSafeAndUnpaddedBase64() {
    // 0xfb 0xff encodes to "+/8=" (standard) or "-_8" (URL-safe, unpadded).
    Holder h = BARE.readValue("{\"data\":\"-_8\"}", Holder.class);

    assertArrayEquals(
        new byte[] {(byte) 0xfb, (byte) 0xff}, h.data() == null ? null : h.data().toByteArray());
  }

  @Test
  void rejectsNonBase64AndNonStringInput() {
    assertThrows(JacksonException.class, () -> BARE.readValue("{\"data\":\"*\"}", Holder.class));
    assertThrows(JacksonException.class, () -> BARE.readValue("{\"data\":12}", Holder.class));
  }
}
