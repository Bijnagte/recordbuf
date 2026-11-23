package bijnagte.recordbuf.fixture.jackson;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.avro.rec.Dyn;
import bijnagte.recordbuf.json.DynamicWkt;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The golden cases of family {@code jackson-dyn}, migrated from {@code JacksonDynamicWktTest}: a
 * {@code Dyn} record whose node-typed dynamic WKT fields (and an {@code Any} payload, structural or
 * opaque) render as nodes. {@code Dyn} lives in a Jackson x Avro file, so besides its Jackson
 * golden it has both Avro goldens, where the dynamic WKT envelope carries the content type.
 */
public final class JacksonDynamicWktCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "jackson-dyn";

  private static final JsonMapper NODES = new JsonMapper();

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "dyn-struct-any", dyn(structAny()))),
        build(jacksonLossless(FAMILY, "dyn-opaque-any", dyn(opaqueAny()))));
  }

  /** An Any packing a Struct: rendered with its value as the Struct's node. */
  public static JsonNode structAny() {
    Struct struct =
        Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build()).build();
    return DynamicWkt.any(Any.pack(struct));
  }

  /** An Any packing an unknown type: rendered with its value as the payload's base64. */
  public static JsonNode opaqueAny() {
    return DynamicWkt.any(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/com.example.Opaque")
            .setValue(ByteString.copyFrom(new byte[] {8, (byte) 150, 1}))
            .build());
  }

  /**
   * A {@code Dyn} with every dynamic WKT field set, carrying {@code payload} as its {@code Any}.
   */
  public static Dyn dyn(JsonNode payload) {
    return Dyn.builder()
        .name("d")
        .value(NODES.readTree("{\"k\":[1.5,\"x\",null]}"))
        .doc((ObjectNode) NODES.readTree("{\"a\":{\"b\":true}}"))
        .items((ArrayNode) NODES.readTree("[2.25]"))
        .payload(payload)
        .values(List.of(NODES.readTree("\"s\""), NODES.readTree("[]")))
        .build();
  }
}
