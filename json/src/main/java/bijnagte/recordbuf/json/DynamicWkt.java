package bijnagte.recordbuf.json;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.Value.KindCase;
import java.io.IOException;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Converts between the structural well-known types and their Jackson node shapes (the U3 module of
 * the 2026-11-20 Jackson plan). Generated dynamic-WKT fields are node-typed — {@code Struct} →
 * {@code ObjectNode}, {@code Value} / {@code Any} → {@code JsonNode}, {@code ListValue} → {@code
 * ArrayNode}, {@code NullValue} → {@code NullNode} — and the generated codec round-trips through
 * these helpers: wire bytes become the native message, which becomes a node on the way out of the
 * record, and the node becomes the native message again on the way in.
 *
 * <p>{@link Any} maps to its {@code type_url} / {@code value} JSON shape (the JSON mapping
 * prescribed by the well-known-types documentation): the resulting object node carries {@code
 * typeUrl} and a {@code value} node reconstructed from the embedded payload when it can be decoded,
 * else {@code null}.
 */
public final class DynamicWkt {

  /** A lenient mapper for structural conversion — never binds to records. */
  public static final ObjectMapper MAPPER = new ObjectMapper();

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private DynamicWkt() {}

  /** The node shape of a {@link Struct} (its fields, in the record's field order). */
  public static ObjectNode struct(Struct struct) {
    ObjectNode node = F.objectNode();
    struct.getFieldsMap().forEach((name, value) -> node.set(name, value(value)));
    return node;
  }

  /** The node shape of a {@link Value}. */
  public static JsonNode value(Value value) {
    KindCase kind = value.getKindCase();
    return switch (kind) {
      case NULL_VALUE -> F.nullNode();
      case NUMBER_VALUE -> F.numberNode(value.getNumberValue());
      case STRING_VALUE -> new StringNode(value.getStringValue());
      case BOOL_VALUE -> value.getBoolValue() ? F.booleanNode(true) : F.booleanNode(false);
      case STRUCT_VALUE -> struct(value.getStructValue());
      case LIST_VALUE -> list(value.getListValue());
      case KIND_NOT_SET -> F.nullNode();
    };
  }

  /** The node shape of a {@link ListValue}. */
  public static ArrayNode list(ListValue list) {
    ArrayNode node = F.arrayNode();
    list.getValuesList().forEach(v -> node.add(value(v)));
    return node;
  }

  /** The node shape of a {@link NullValue} (always the JSON null node). */
  public static NullNode nullValue(NullValue nullValue) {
    return F.nullNode();
  }

  /**
   * The node shape of an {@link Any}, in the well-known-types' canonical JSON mapping: an object
   * with {@code typeUrl} and {@code value}, where {@code value} is the decoded JSON representation
   * for the structural types and the payload's base64 for everything else — exactly protobuf's own
   * Any JSON mapping, so a round-trip is lossless.
   */
  public static ObjectNode any(Any any) {
    ObjectNode node = F.objectNode();
    node.put("typeUrl", any.getTypeUrl());
    try {
      switch (any.getTypeUrl()) {
        case "type.googleapis.com/google.protobuf.Struct" ->
            node.set("value", struct(Struct.parseFrom(any.getValue())));
        case "type.googleapis.com/google.protobuf.Value" ->
            node.set("value", value(Value.parseFrom(any.getValue())));
        case "type.googleapis.com/google.protobuf.ListValue" ->
            node.set("value", list(ListValue.parseFrom(any.getValue())));
        default -> node.put("value", base64(any.getValue()));
      }
    } catch (InvalidProtocolBufferException e) {
      node.put("value", base64(any.getValue()));
    }
    return node;
  }

  private static String base64(ByteString bytes) {
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  /** Rebuilds a {@link Struct} from a JSON object node. */
  public static Struct structFrom(ObjectNode node) {
    Struct.Builder b = Struct.newBuilder();
    node.properties().forEach(entry -> b.putFields(entry.getKey(), valueFrom(entry.getValue())));
    return b.build();
  }

  /** Rebuilds a {@link Value} from any JSON node. */
  public static Value valueFrom(JsonNode node) {
    Value.Builder b = Value.newBuilder();
    if (node == null || node.isNull()) {
      b.setNullValue(NullValue.NULL_VALUE);
    } else if (node.isBoolean()) {
      b.setBoolValue(node.booleanValue());
    } else if (node.isNumber()) {
      b.setNumberValue(node.decimalValue().doubleValue());
    } else if (node.stringValueOpt().isPresent()) {
      b.setStringValue(node.asString());
    } else if (node.isObject()) {
      b.setStructValue(structFrom((ObjectNode) node));
    } else if (node.isArray()) {
      b.setListValue(listFrom((ArrayNode) node));
    } else {
      b.setNullValue(NullValue.NULL_VALUE);
    }
    return b.build();
  }

  /** Rebuilds a {@link ListValue} from a JSON array node. */
  public static ListValue listFrom(ArrayNode node) {
    ListValue.Builder b = ListValue.newBuilder();
    node.forEach(v -> b.addValues(valueFrom(v)));
    return b.build();
  }

  /**
   * Rebuilds an {@link Any} from its canonical JSON mapping (the inverse of {@link #any(Any)}):
   * structural typeUrls re-encode their message from the decoded node; any other typeUrl takes the
   * payload back from base64. A null/absent {@code value} node clears the payload.
   */
  public static Any anyFrom(String typeUrl, JsonNode valueNode) {
    Any.Builder b = Any.newBuilder().setTypeUrl(typeUrl);
    if (valueNode == null || valueNode.isNull()) {
      return b.build();
    }
    switch (typeUrl) {
      case "type.googleapis.com/google.protobuf.Struct" ->
          b.setValue(structFrom((ObjectNode) valueNode).toByteString());
      case "type.googleapis.com/google.protobuf.Value" ->
          b.setValue(valueFrom(valueNode).toByteString());
      case "type.googleapis.com/google.protobuf.ListValue" ->
          b.setValue(listFrom((ArrayNode) valueNode).toByteString());
      default -> b.setValue(ByteString.copyFrom(Base64.getDecoder().decode(valueNode.asString())));
    }
    return b.build();
  }

  /**
   * Wire-encode a Jackson node as the byte content of the named well-known type (the {@code
   * writeByteArray} payload of a message-field wire arm): {@code Struct}/{@code Value} and {@code
   * ListValue} nodes become their WKT message's serialized bytes.
   *
   * @param wktFqn the WKT's canonical FQN ({@code google.protobuf.Struct} / {@code Value} / {@code
   *     ListValue})
   * @param node the field's node value (non-null; absent fields are skipped by the caller)
   * @return the WKT's serialized bytes
   * @throws IOException if serialization fails
   * @throws IllegalArgumentException for any other FQN (the generator only emits these three)
   */
  public static byte[] encode(String wktFqn, JsonNode node) throws IOException {
    return switch (shortName(wktFqn)) {
      case "Struct" -> structFrom(needObjectNode(node, wktFqn)).toByteArray();
      case "Value" -> valueFrom(node).toByteArray();
      case "ListValue" -> listFrom(needArrayNode(node, wktFqn)).toByteArray();
      default ->
          throw new IllegalArgumentException(
              "DynamicWkt.encode: unsupported well-known type " + wktFqn);
    };
  }

  /**
   * Wire-decode the byte content of the named well-known type back into a Jackson node (the mirror
   * of {@link #encode}): a {@code Value} field reads back as a {@code Value} node, a {@code Struct}
   * field as its object node, a {@code ListValue} field as its array node.
   *
   * @param wktFqn the WKT's canonical FQN ({@code google.protobuf.Struct} / {@code Value} / {@code
   *     ListValue})
   * @param content the wire bytes (a {@code readByteArray} result)
   * @return the decoded node
   * @throws com.google.protobuf.InvalidProtocolBufferException if the bytes are not a valid WKT
   */
  public static JsonNode decode(String wktFqn, byte[] content)
      throws InvalidProtocolBufferException {
    return switch (shortName(wktFqn)) {
      case "Struct" -> struct(Struct.parseFrom(content));
      case "Value" -> value(Value.parseFrom(content));
      case "ListValue" -> list(ListValue.parseFrom(content));
      default ->
          throw new IllegalArgumentException(
              "DynamicWkt.decode: unsupported well-known type " + wktFqn);
    };
  }

  private static ObjectNode needObjectNode(JsonNode node, String wktFqn) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("expected an object node for " + wktFqn + ", got " + node);
    }
    return (ObjectNode) node;
  }

  private static ArrayNode needArrayNode(JsonNode node, String wktFqn) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("expected an array node for " + wktFqn + ", got " + node);
    }
    return (ArrayNode) node;
  }

  private static String shortName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }
}
