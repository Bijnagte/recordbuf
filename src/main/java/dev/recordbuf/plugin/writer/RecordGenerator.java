package dev.recordbuf.plugin.writer;

import java.util.ArrayList;
import java.util.List;

import dev.recordbuf.plugin.model.Field;
import dev.recordbuf.plugin.model.Message;
import dev.recordbuf.plugin.types.TypeResolver;
import dev.recordbuf.plugin.types.TypeResolver.WireScalar;

/**
 * Emits record declarations. For each {@link Message}, we produce a Java
 * {@code record} whose components are the proto fields, followed by a
 * {@code static final class Codec} that knows how to serialize and
 * deserialize via {@code CodedInputStream}/{@code CodedOutputStream}
 * (protobuf-java runtime).
 */
public final class RecordGenerator {

    private final TypeResolver types;

    public RecordGenerator(TypeResolver types) {
        this.types = types;
    }

    /** Emit a top-level record with all nested types and its codec inline. */
    public void write(JavaWriter w, Message m) {
        w.line("public record " + m.name() + "(");
        for (int i = 0; i < m.fields().size(); i++) {
            Field f = m.fields().get(i);
            String type = types.resolve(f);
            String sep = (i == m.fields().size() - 1) ? "" : ",";
            w.line("    " + type + " " + f.javaName() + sep);
        }
        w.line(")");
        w.block("{", "}", body -\u003e {});
    }

    private void writeNested(JavaWriter w, Message m) {
        for (Message nested : m.nestedMessages()) {
            w.line();
            write(w, nested);
        }
        for (var e : m.nestedEnums()) {
            w.line();
            new EnumGenerator().write(w, e);
        }
    }
}
