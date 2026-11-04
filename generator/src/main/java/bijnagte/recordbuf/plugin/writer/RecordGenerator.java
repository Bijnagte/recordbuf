package bijnagte.recordbuf.plugin.writer;

import java.util.List;

/**
 * Shared helper for the {@code implements X, Y} suffix on a generated record's and enum's type
 * header. The two generators each own their type bodies; only this header suffix is factored out so
 * records and enums stay consistent.
 */
public final class RecordGenerator {

  private RecordGenerator() {}

  /**
   * Build a Java {@code implements} clause from the declared option values, each resolved through
   * the file's import collector: an interface in the generated file's own package is emitted short
   * and import-free; one in another package is imported and emitted short, or (on a simple-name
   * collision with another type in the same file) left fully-qualified.
   */
  public static String implementsClause(TypeImports ci, List<String> interfaces) {
    if (interfaces.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(" implements ");
    for (int i = 0; i < interfaces.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(ci.use(interfaces.get(i)));
    }
    return sb.toString();
  }
}
