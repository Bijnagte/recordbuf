package bijnagte.recordbuf.plugin.model;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds an internal {@link FileMap} from a list of {@link FileDescriptorProto}s (the entire
 * request, including dependencies).
 *
 * <p>The map makes it trivial to look up "what Java type do I generate for proto type {@code
 * a.b.C}?" given the java_package of every file.
 */
public final class FileTable {
  private final Map<String, String> protoFqnToJavaFqn = new HashMap<>();
  private final Map<String, FileDescriptorProto> byFileName = new HashMap<>();
  private final String packageSuffix;

  public FileTable() {
    this("");
  }

  /**
   * Construct with a normalized run-wide {@code package_suffix} (leading/ trailing dots stripped)
   * applied uniformly to every file's java package. An empty suffix is a no-op that leaves every
   * package byte-for-byte unchanged.
   */
  public FileTable(String packageSuffix) {
    this.packageSuffix = (packageSuffix == null) ? "" : packageSuffix;
  }

  public void addAll(List<FileDescriptorProto> files) {
    // Pass 1: java package resolution (in case it depends on relative proto
    // package, which it normally doesn't, but be safe).
    for (FileDescriptorProto f : files) {
      byFileName.put(f.getName(), f);
      String javaPkg = javaPackage(f);
      // Pass 2: walk each file and register every top-level and nested
      // type.
      registerFile(f, javaPkg);
    }
  }

  /** java FQN for a proto FQN, or null if unknown. */
  public @Nullable String javaFqn(String protoFqn) {
    return protoFqnToJavaFqn.get(protoFqn);
  }

  /** The file for a filename, or null if not present. */
  public @Nullable FileDescriptorProto byName(String fileName) {
    return byFileName.get(fileName);
  }

  /**
   * The Java package for a proto file: the declared {@code java_package} (or proto-package /
   * filename fallback), then the run-wide {@code package_suffix} composed on as the final segment —
   * e.g. suffix {@code .rec} on declared java_package {@code bijnagte.recordbuf.fixture} yields
   * {@code bijnagte.recordbuf.fixture.rec}. An absent/blank suffix is a no-op.
   */
  public String javaPackage(FileDescriptorProto f) {
    return applyPackageSuffix(rawJavaPackage(f), packageSuffix);
  }

  // --- internals ---

  /**
   * Compose a java package with the run-wide suffix. A blank suffix is a no-op (byte-for-byte
   * unchanged). Otherwise the suffix becomes the final path segment: {@code nativePkg + "." +
   * suffix}. If the base is empty, the bare suffix is the package.
   */
  public static String applyPackageSuffix(@Nullable String base, @Nullable String suffix) {
    // A blank on either side is a no-op; a null/empty side is normalised to
    // the empty string (the "no package" sentinel, matching rawJavaPackage).
    if (base == null || base.isEmpty()) base = "";
    if (suffix == null || suffix.isEmpty()) suffix = "";
    if (suffix.isEmpty()) return base;
    if (base.isEmpty()) return suffix;
    return base + "." + suffix;
  }

  /** The declared Java package for {@code f} with NO suffix applied. */
  private static String rawJavaPackage(FileDescriptorProto f) {
    if (f.getOptions().hasJavaPackage() && !f.getOptions().getJavaPackage().isBlank()) {
      return f.getOptions().getJavaPackage();
    }
    String p = f.getPackage();
    if (!p.isBlank()) return p;
    // Fall back to file stem.
    String stem = f.getName();
    int slash = stem.lastIndexOf('/');
    if (slash >= 0) stem = stem.substring(slash + 1);
    int dot = stem.indexOf('.');
    if (dot >= 0) stem = stem.substring(0, dot);
    return stem;
  }

  private void registerFile(FileDescriptorProto f, String javaPkg) {
    String protoPkg = f.getPackage();
    String scope = protoPkg.isBlank() ? "" : protoPkg;
    for (DescriptorProto m : f.getMessageTypeList()) {
      registerMessageTree(m, scope, javaPkg);
    }
    for (EnumDescriptorProto e : f.getEnumTypeList()) {
      String protoFqn = join(scope, e.getName());
      String javaShort = JavaNames.cap(e.getName());
      protoFqnToJavaFqn.put(protoFqn, join(javaPkg, javaShort));
    }
  }

  private void registerMessageTree(DescriptorProto m, String protoScope, String javaScope) {
    String protoFqn = join(protoScope, m.getName());
    String javaShort = JavaNames.cap(m.getName());
    String javaFqn = join(javaScope, javaShort);
    protoFqnToJavaFqn.put(protoFqn, javaFqn);

    // Nested types share the parent's scope for proto resolution, and the
    // parent's java scope as well (we generate them as nested Java types
    // in the parent class).
    String nestedProtoScope = protoFqn;
    String nestedJavaScope = javaFqn;
    for (DescriptorProto nested : m.getNestedTypeList()) {
      registerMessageTree(nested, nestedProtoScope, nestedJavaScope);
    }
    for (EnumDescriptorProto nested : m.getEnumTypeList()) {
      String pe = join(nestedProtoScope, nested.getName());
      String je = join(nestedJavaScope, JavaNames.cap(nested.getName()));
      protoFqnToJavaFqn.put(pe, je);
    }
  }

  private static String join(String prefix, String suffix) {
    if (prefix == null || prefix.isBlank()) return suffix;
    if (suffix == null || suffix.isBlank()) return prefix;
    return prefix + "." + suffix;
  }
}
