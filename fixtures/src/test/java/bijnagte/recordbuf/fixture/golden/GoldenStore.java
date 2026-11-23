package bijnagte.recordbuf.fixture.golden;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Resolves golden files by the layout {@code <root>/<family>/<case>.<ext>} (for example {@code
 * /golden/scalars/int32.pb.hex}). The default store reads the test classpath under {@code /golden};
 * {@link #inDirectory} points the same layout at a filesystem directory, for tests of the seeding
 * task. A missing golden fails with its full path, never with a silent default.
 */
public final class GoldenStore {

  /** Classpath root of the committed goldens. */
  public static final String CLASSPATH_ROOT = "/golden";

  @FunctionalInterface
  private interface Opener {
    /** Opens the golden at {@code relativePath}, or returns {@code null} if it does not exist. */
    InputStream open(String relativePath) throws IOException;
  }

  private final String root;
  private final Opener opener;

  private GoldenStore(String root, Opener opener) {
    this.root = root;
    this.opener = opener;
  }

  /** Returns the store over the committed goldens on the test classpath. */
  public static GoldenStore classpath() {
    return new GoldenStore(
        CLASSPATH_ROOT,
        relativePath -> GoldenStore.class.getResourceAsStream(CLASSPATH_ROOT + "/" + relativePath));
  }

  /** Returns a store over a filesystem directory laid out like the classpath root. */
  public static GoldenStore inDirectory(Path directory) {
    return new GoldenStore(
        directory.toString(),
        relativePath -> {
          try {
            return Files.newInputStream(directory.resolve(relativePath));
          } catch (NoSuchFileException e) {
            return null;
          }
        });
  }

  /**
   * Returns the full path of a golden, as shown in failure messages: {@code
   * <root>/<family>/<caseName>.<extension>}.
   *
   * @param family the family directory, e.g. {@code "scalars"}
   * @param caseName the case name, e.g. {@code "int32"}
   * @param extension the format extension without the leading dot, e.g. {@code "pb.hex"}
   */
  public String path(String family, String caseName, String extension) {
    return root + "/" + relativePath(family, caseName, extension);
  }

  /** Returns whether the golden exists. */
  public boolean exists(String family, String caseName, String extension) {
    try (InputStream in = opener.open(relativePath(family, caseName, extension))) {
      return in != null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Loads a golden as UTF-8 text, exactly as committed.
   *
   * @throws AssertionError if the golden is missing, naming its full path
   */
  public String loadText(String family, String caseName, String extension) {
    return new String(readBytes(family, caseName, extension), StandardCharsets.UTF_8);
  }

  /**
   * Loads a hex-encoded binary golden and decodes it.
   *
   * @throws AssertionError if the golden is missing, naming its full path
   * @throws IllegalArgumentException if the hex is malformed (see {@link
   *     GoldenAssertions#decodeHex})
   */
  public byte[] loadBinary(String family, String caseName, String extension) {
    return GoldenAssertions.decodeHex(
        loadText(family, caseName, extension), path(family, caseName, extension));
  }

  private byte[] readBytes(String family, String caseName, String extension) {
    String relativePath = relativePath(family, caseName, extension);
    try (InputStream in = opener.open(relativePath)) {
      if (in == null) {
        throw new AssertionError("missing golden " + path(family, caseName, extension));
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read golden " + path(family, caseName, extension), e);
    }
  }

  private static String relativePath(String family, String caseName, String extension) {
    return family + "/" + caseName + "." + extension;
  }
}
