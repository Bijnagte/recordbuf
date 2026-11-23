package bijnagte.recordbuf.fixture.golden.format;

import com.google.protobuf.MessageLite;
import java.util.Optional;

/**
 * Maps a recordbuf-generated record type to its protobuf-java reference message class.
 *
 * <p>The main source set generates records with {@code package_suffix=.rec}, so a record {@code
 * a.b.rec.Outer.Inner} has its reference {@code a.b.Outer.Inner} in the natural package: the same
 * binary name with the trailing {@code rec} package segment removed. This holds for top-level and
 * nested messages alike, because protobuf-java nests a nested message class inside its parent
 * exactly as recordbuf does. It requires {@code java_multiple_files = true} on the proto (every
 * main-source-set proto sets it); a message that protobuf-java would nest in an outer class has no
 * mapping here.
 *
 * <p>A record has no reference class when it is outside a {@code .rec} package (the iface protos),
 * when the natural-package class does not exist (the test-source-set {@code jackson_*} protos,
 * which are generated without the protobuf-java builtin), or when it is not a message (a map-entry
 * record, an enum).
 */
public final class ReferenceTypes {

  private static final String RECORD_PACKAGE_SEGMENT = "rec";

  private ReferenceTypes() {}

  /**
   * Returns the protobuf-java reference message class of {@code recordType}, or empty if there is
   * none; {@link #missingReason} says why.
   */
  public static Optional<Class<? extends MessageLite>> referenceFor(Class<?> recordType) {
    return resolve(recordType).reference();
  }

  /** Returns why {@code recordType} has no reference class, or empty if it has one. */
  public static Optional<String> missingReason(Class<?> recordType) {
    return resolve(recordType).missingReason();
  }

  private record Resolution(
      Optional<Class<? extends MessageLite>> reference, Optional<String> missingReason) {

    static Resolution found(Class<? extends MessageLite> reference) {
      return new Resolution(Optional.of(reference), Optional.empty());
    }

    static Resolution missing(String reason) {
      return new Resolution(Optional.empty(), Optional.of(reason));
    }
  }

  private static Resolution resolve(Class<?> recordType) {
    String packageName = recordType.getPackageName();
    if (!packageName.equals(RECORD_PACKAGE_SEGMENT)
        && !packageName.endsWith("." + RECORD_PACKAGE_SEGMENT)) {
      return Resolution.missing(
          recordType.getName() + " is not in a .rec package, so it has no protobuf-java reference");
    }
    String naturalPackage =
        packageName.equals(RECORD_PACKAGE_SEGMENT)
            ? ""
            : packageName.substring(0, packageName.length() - RECORD_PACKAGE_SEGMENT.length() - 1);
    String simpleBinaryName = recordType.getName().substring(packageName.length() + 1);
    String referenceName =
        naturalPackage.isEmpty() ? simpleBinaryName : naturalPackage + "." + simpleBinaryName;
    Class<?> candidate;
    try {
      candidate = Class.forName(referenceName, false, recordType.getClassLoader());
    } catch (ClassNotFoundException e) {
      return Resolution.missing(
          "no protobuf-java reference class "
              + referenceName
              + " exists for "
              + recordType.getName()
              + " (generated without the protobuf-java builtin, or not a message)");
    }
    if (!MessageLite.class.isAssignableFrom(candidate)) {
      return Resolution.missing(
          referenceName + " exists but is not a protobuf-java message (" + candidate + ")");
    }
    return Resolution.found(candidate.asSubclass(MessageLite.class));
  }
}
