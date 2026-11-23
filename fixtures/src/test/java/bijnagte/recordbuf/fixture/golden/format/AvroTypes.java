package bijnagte.recordbuf.fixture.golden.format;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import org.apache.avro.Schema;
import org.apache.avro.util.ClassSecurityValidator;

/**
 * Maps a recordbuf-generated record type to its generated Avro surface: the {@code
 * <Record>AvroConverter} and the Avro specific record it converts to.
 *
 * <p>The converter is generated into the record's own package and named {@code
 * <SimpleName>AvroConverter} (for {@code a.b.rec.Dyn}: {@code a.b.rec.DynAvroConverter}). The
 * specific record is not derived from a name: it is the return type of the converter's {@code
 * fromRecord}, so it holds wherever the generator puts the specific classes (a {@code rec}-less
 * package for the iface protos, the namespace of the proto package otherwise). A record has no Avro
 * surface when that converter class does not exist (a message of a proto generated without Avro, an
 * enum, a map-entry record).
 *
 * <p>Avro's {@link ClassSecurityValidator} refuses to read or write a specific class it does not
 * trust, and the existing tests each install their own global validator for the classes they use.
 * {@link Binding#ensureTrusted} replaces that per-class setup: it adds every class a specific
 * record's schema reaches to the global validator, but only the ones the current validator does not
 * already trust, so it neither undoes nor is undone by a validator another test installed.
 */
final class AvroTypes {

  private static final String CONVERTER_SUFFIX = "AvroConverter";

  private AvroTypes() {}

  /** The generated Avro surface of one record type. */
  static final class Binding {

    private final Class<?> specificType;
    private final Method toRecord;
    private final Method fromRecord;

    private Binding(Class<?> specificType, Method toRecord, Method fromRecord) {
      this.specificType = specificType;
      this.toRecord = toRecord;
      this.fromRecord = fromRecord;
    }

    /** Returns the Avro specific record class the record converts to. */
    Class<?> specificType() {
      return specificType;
    }

    /** Converts a recordbuf record to its Avro specific record, via the generated converter. */
    Object fromRecord(Object record) throws Exception {
      return invoke(fromRecord, record);
    }

    /** Converts an Avro specific record to its recordbuf record, via the generated converter. */
    Object toRecord(Object specific) throws Exception {
      return invoke(toRecord, specific);
    }

    /**
     * Makes sure Avro's global {@link ClassSecurityValidator} trusts the specific record class and
     * every named class its schema reaches (nested records, enums, fixed types).
     */
    void ensureTrusted() throws ReflectiveOperationException {
      Schema schema = (Schema) specificType.getMethod("getClassSchema").invoke(null);
      List<Class<?>> reached = new ArrayList<>();
      collectNamedClasses(schema, specificType.getClassLoader(), reached, new IdentityHashMap<>());
      ClassSecurityValidator.Builder untrusted = ClassSecurityValidator.builder();
      boolean anyUntrusted = false;
      for (Class<?> type : reached) {
        try {
          ClassSecurityValidator.validate(type);
        } catch (SecurityException notTrusted) {
          untrusted.add(type);
          anyUntrusted = true;
        }
      }
      if (anyUntrusted) {
        ClassSecurityValidator.setGlobal(
            ClassSecurityValidator.composite(
                ClassSecurityValidator.getGlobal(), untrusted.build()));
      }
    }
  }

  /** Returns the Avro surface of {@code recordType}, or empty if it has no generated converter. */
  static Optional<Binding> bindingFor(Class<?> recordType) {
    Class<?> converter;
    try {
      converter =
          Class.forName(
              recordType.getPackageName() + "." + recordType.getSimpleName() + CONVERTER_SUFFIX,
              false,
              recordType.getClassLoader());
    } catch (ClassNotFoundException e) {
      return Optional.empty();
    }
    try {
      Method fromRecord = converter.getMethod("fromRecord", recordType);
      Class<?> specificType = fromRecord.getReturnType();
      Method toRecord = converter.getMethod("toRecord", specificType);
      return Optional.of(new Binding(specificType, toRecord, fromRecord));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          converter.getName() + " lacks the fromRecord/toRecord pair for " + recordType.getName(),
          e);
    }
  }

  /** Returns why {@code recordType} has no Avro surface, or empty if it has one. */
  static Optional<String> missingReason(Class<?> recordType) {
    if (bindingFor(recordType).isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        "no generated "
            + recordType.getPackageName()
            + "."
            + recordType.getSimpleName()
            + CONVERTER_SUFFIX
            + " exists for "
            + recordType.getName()
            + " (generated without Avro, or not a message)");
  }

  /** Returns the binding of {@code recordType}, or fails naming the missing converter. */
  static Binding requireBinding(Class<?> recordType) {
    return bindingFor(recordType)
        .orElseThrow(() ->
            new IllegalArgumentException(missingReason(recordType).orElse(recordType.getName())));
  }

  private static void collectNamedClasses(
      Schema schema,
      ClassLoader loader,
      List<Class<?>> out,
      IdentityHashMap<Schema, Boolean> seen) {
    switch (schema.getType()) {
      case RECORD -> {
        if (seen.put(schema, Boolean.TRUE) != null) {
          return;
        }
        addNamedClass(schema, loader, out);
        for (Schema.Field field : schema.getFields()) {
          collectNamedClasses(field.schema(), loader, out, seen);
        }
      }
      case ENUM, FIXED -> addNamedClass(schema, loader, out);
      case ARRAY -> collectNamedClasses(schema.getElementType(), loader, out, seen);
      case MAP -> collectNamedClasses(schema.getValueType(), loader, out, seen);
      case UNION -> {
        for (Schema branch : schema.getTypes()) {
          collectNamedClasses(branch, loader, out, seen);
        }
      }
      default -> {}
    }
  }

  private static void addNamedClass(Schema schema, ClassLoader loader, List<Class<?>> out) {
    try {
      out.add(Class.forName(schema.getFullName(), false, loader));
    } catch (ClassNotFoundException e) {
      // a schema type with no compiled class is read generically; there is nothing to trust
    }
  }

  private static Object invoke(Method method, Object argument) throws Exception {
    try {
      return method.invoke(null, argument);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
  }
}
