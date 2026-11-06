package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.plugin.model.AvroToggle;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The per-invocation, run-wide context a {@link FormatBridge} resolves its enablement against: the
 * plugin-parameter string protoc passes (a comma-separated {@code key=value} list, e.g. {@code
 * package_suffix=.rec,avro=off}), parsed into per-key values that a bridge reads by its own key
 * name.
 *
 * <p>For the {@code recordbuf.avro} bridge the relevant key is {@code avro}, a tri-state read as:
 * absent → the built-in default of <em>on</em>; {@code avro=on} (or {@code avro=true}/{@code
 * avro=1}) → on; {@code avro=off} (or {@code avro=false}/{@code avro=0}) → off. A per-file {@code
 * recordbuf.avro} option (in {@link ProtoFile#avroToggle()}) overrides this flag, which in turn
 * sits above the built-in default — the R1 resolution order {@code file > flag > default(on)}.
 */
public final class BridgeContext {

  // The plugin-parameter key the Avro bridge reads its global flag from.
  static final String AVRO_KEY = "avro";

  private final Map<String, String> params;

  private BridgeContext(Map<String, String> params) {
    this.params = params;
  }

  /** An empty context (no plugin parameters): every bridge resolves to its built-in default. */
  public static BridgeContext empty() {
    return new BridgeContext(Map.of());
  }

  /**
   * Parse a protoc plugin-parameter string into a per-key map. {@code key=value} pairs are
   * comma-separated; a bare {@code key} (no {@code =}) maps to an empty string. Whitespace is
   * trimmed from each key and value. An absent/blank string yields an empty map.
   */
  public static BridgeContext parse(@Nullable String parameter) {
    Map<String, String> params = new LinkedHashMap<>();
    if (parameter == null || parameter.isBlank()) return new BridgeContext(params);
    for (String pair : parameter.split(",")) {
      int eq = pair.indexOf('=');
      String key = (eq < 0 ? pair : pair.substring(0, eq)).trim();
      String value = (eq < 0 ? "" : pair.substring(eq + 1)).trim();
      if (key.isEmpty()) continue;
      params.put(key, value);
    }
    return new BridgeContext(params);
  }

  /** The raw value of a plugin-parameter key, or {@code null} when the key is absent. */
  @Nullable
  public String raw(@Nullable String key) {
    return key == null ? null : params.get(key);
  }

  /**
   * The Avro bridge's per-file enablement, applying R1's resolution order {@code file > flag >
   * default(on)}: a per-file {@code recordbuf.avro} option (an explicit {@code ON}/ {@code OFF} in
   * {@link ProtoFile#avroToggle()}) wins; otherwise the plugin's global {@code avro} flag decides
   * if present, else the built-in default of on.
   */
  public boolean avroEnabled(ProtoFile file) {
    AvroToggle toggle = file.avroToggle();
    if (toggle.isExplicitControl()) {
      return toggle.enabled();
    }
    return bridgeFlagOn(AVRO_KEY);
  }

  /**
   * The tri-state resolution of a single plugin-parameter flag to a boolean: present-and-off →
   * {@code false}, present-and-on → {@code true}, absent → the built-in default of {@code true}. An
   * "off" is any of {@code off}/{@code false}/{@code 0} (case-insensitive); anything else with a
   * non-blank, recognized value is "on". A blank value is treated as "on" (bare flag).
   */
  private boolean bridgeFlagOn(String key) {
    String v = params.get(key);
    if (v == null) return true; // absent → built-in default on
    switch (v.toLowerCase(Locale.ROOT)) {
      case "off":
      case "false":
      case "0":
        return false;
      case "on":
      case "true":
      case "1":
        return true;
      case "":
      default:
        // A bare flag key (blank value) or any unrecognized-but-present value: on.
        return true;
    }
  }
}
