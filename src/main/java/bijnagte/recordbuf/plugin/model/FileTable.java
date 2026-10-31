package bijnagte.recordbuf.plugin.model;

import java.util.HashMap;
import java.util.Map;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;

/**
 * Builds an internal {@link FileMap} from a list of
 * {@link FileDescriptorProto}s (the entire request, including dependencies).
 *
 * <p>The map makes it trivial to look up "what Java type do I generate for
 * proto type {@code a.b.C}?" given the java_package of every file.
 */
public final class FileTable {
    private final Map<String, String> protoFqnToJavaFqn = new HashMap<>();
    private final Map<String, FileDescriptorProto> byFileName = new HashMap<>();

    public void addAll(java.util.List<FileDescriptorProto> files) {
        // Pass 1: java package resolution (in case it depends on relative proto
        // package, which it normally doesn't, but be safe).
        for (FileDescriptorProto f : files) {
            byFileName.put(f.getName(), f);
            String javaPkg = javaPackageFor(f);
            // Pass 2: walk each file and register every top-level and nested
            // type.
            registerFile(f, javaPkg);
        }
    }

    /** Java FQN for a proto FQN, or null if unknown. */
    public String javaFqn(String protoFqn) {
        return protoFqnToJavaFqn.get(protoFqn);
    }

    /** Java package for a proto FQN — used when generating import lines. */
    public String javaPackage(String protoFqn) {
        String fqn = javaFqn(protoFqn);
        if (fqn == null) return null;
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    public FileDescriptorProto byName(String fileName) {
        return byFileName.get(fileName);
    }

    // --- internals ---

    private String javaPackageFor(FileDescriptorProto f) {
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
            registerMessageTree(m, scope, javaPkg, "0");
        }
        for (EnumDescriptorProto e : f.getEnumTypeList()) {
            String protoFqn = join(scope, e.getName());
            String javaShort = JavaNames.cap(e.getName());
            protoFqnToJavaFqn.put(protoFqn, join(javaPkg, javaShort));
        }
    }

    private void registerMessageTree(DescriptorProto m, String protoScope,
                                     String javaScope, /*ignored*/ String depth) {
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
            registerMessageTree(nested, nestedProtoScope, nestedJavaScope, null);
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

    /** Visible for testing. */
    public Map<String, String> snapshot() {
        return Map.copyOf(protoFqnToJavaFqn);
    }
}
