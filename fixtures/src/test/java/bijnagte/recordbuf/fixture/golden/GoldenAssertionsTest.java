package bijnagte.recordbuf.fixture.golden;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenAssertionsTest {

  @Test
  void assertJsonMatches_membersInDifferentOrder_fails() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                GoldenAssertions.assertJsonMatches(
                    "{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}", "case/x"));
    assertTrue(error.getMessage().contains("case/x"), error.getMessage());
  }

  @Test
  void assertJsonMatches_differentWhitespaceAndIndentation_passes() {
    GoldenAssertions.assertJsonMatches(
        "{\"a\":[1,2],\"b\":{\"c\":\"d\"}}",
        "{\n    \"a\" : [ 1,\n 2 ],\n  \"b\": { \"c\": \"d\" }\n}\n",
        "case/x");
  }

  @Test
  void assertJsonMatches_decimalPointVersusInteger_fails() {
    assertThrows(
        AssertionError.class,
        () -> GoldenAssertions.assertJsonMatches("{\"n\":1.0}", "{\"n\":1}", "case/x"));
  }

  @Test
  void decodeHex_mixedOrUpperCase_isRejected() {
    IllegalArgumentException mixed =
        assertThrows(
            IllegalArgumentException.class,
            () -> GoldenAssertions.decodeHex("00fF10\n", "g.pb.hex"));
    assertTrue(mixed.getMessage().contains("g.pb.hex"), mixed.getMessage());
    assertTrue(mixed.getMessage().contains("uppercase"), mixed.getMessage());
    assertThrows(
        IllegalArgumentException.class, () -> GoldenAssertions.decodeHex("00FF10\n", "g.pb.hex"));
  }

  @Test
  void decodeHex_missingFinalNewline_isRejected() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> GoldenAssertions.decodeHex("00ff10", "g.pb.hex"));
    assertTrue(error.getMessage().contains("final newline"), error.getMessage());
  }

  @Test
  void decodeHex_oddLengthOrNonHexOrEmptyLine_isRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> GoldenAssertions.decodeHex("00f\n", "g.pb.hex"));
    assertThrows(
        IllegalArgumentException.class, () -> GoldenAssertions.decodeHex("00zz\n", "g.pb.hex"));
    assertThrows(
        IllegalArgumentException.class, () -> GoldenAssertions.decodeHex("00\n\n01\n", "g.pb.hex"));
    assertThrows(
        IllegalArgumentException.class, () -> GoldenAssertions.decodeHex("00\r\n", "g.pb.hex"));
  }

  @Test
  void encodeHex_wrapsAt32BytesAndRoundTrips() {
    byte[] bytes = new byte[70];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 7 + 200);
    }
    String hex = GoldenAssertions.encodeHex(bytes);
    List<String> lines = hex.lines().toList();
    assertEquals(3, lines.size());
    assertEquals(64, lines.get(0).length());
    assertEquals(64, lines.get(1).length());
    assertEquals(12, lines.get(2).length());
    assertTrue(hex.endsWith("\n") && !hex.endsWith("\n\n"));
    assertEquals(hex.toLowerCase(Locale.ROOT), hex);
    assertArrayEquals(bytes, GoldenAssertions.decodeHex(hex, "round-trip"));
  }

  @Test
  void encodeHex_emptyPayload_roundTrips() {
    assertEquals("\n", GoldenAssertions.encodeHex(new byte[0]));
    assertEquals(0, GoldenAssertions.decodeHex("\n", "empty").length);
  }

  @Test
  void assertBinaryMatches_identicalBytes_passes() {
    GoldenAssertions.assertBinaryMatches(new byte[] {1, 2, 3}, new byte[] {1, 2, 3}, "case/x");
  }

  @Test
  void assertBinaryMatches_oneOffsetDiffers_failsNamingOffset() {
    byte[] expected = new byte[40];
    byte[] actual = expected.clone();
    actual[37] = 0x7f;
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> GoldenAssertions.assertBinaryMatches(expected, actual, "case/x"));
    assertTrue(error.getMessage().contains("offset 37"), error.getMessage());
    assertTrue(error.getMessage().contains("7f"), error.getMessage());
  }

  @Test
  void assertBinaryMatches_lengthDiffers_failsNamingFirstMissingOffset() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                GoldenAssertions.assertBinaryMatches(
                    new byte[] {1, 2, 3}, new byte[] {1, 2}, "case/x"));
    assertTrue(error.getMessage().contains("offset 2"), error.getMessage());
  }

  @Test
  void classpathStore_loadsCommittedGoldens() {
    GoldenStore store = GoldenStore.classpath();
    assertEquals(
        "{\"b\": 1,\n \"a\": [1.5, \"x\"]}\n",
        store.loadText("_selftest", "sample", "jackson.json"));
    assertArrayEquals(
        new byte[] {0x00, (byte) 0xff, 0x10}, store.loadBinary("_selftest", "sample", "pb.hex"));
    assertTrue(store.exists("_selftest", "sample", "pb.hex"));
  }

  @Test
  void classpathStore_missingGolden_failsNamingFullPath() {
    GoldenStore store = GoldenStore.classpath();
    AssertionError error =
        assertThrows(
            AssertionError.class, () -> store.loadText("_selftest", "absent", "jackson.json"));
    assertTrue(
        error.getMessage().contains("/golden/_selftest/absent.jackson.json"), error.getMessage());
    assertTrue(!store.exists("_selftest", "absent", "jackson.json"));
  }

  @Test
  void directoryStore_readsOverriddenRootAndNamesMissingPath(@TempDir Path directory)
      throws IOException {
    Files.createDirectories(directory.resolve("fam"));
    Files.writeString(directory.resolve("fam/c.pb.hex"), "0a0b\n");
    GoldenStore store = GoldenStore.inDirectory(directory);
    assertArrayEquals(new byte[] {0x0a, 0x0b}, store.loadBinary("fam", "c", "pb.hex"));
    AssertionError error =
        assertThrows(AssertionError.class, () -> store.loadText("fam", "nope", "pb.hex"));
    assertTrue(error.getMessage().contains(directory + "/fam/nope.pb.hex"), error.getMessage());
  }

  @Test
  void loadBinary_malformedGolden_namesGoldenPath(@TempDir Path directory) throws IOException {
    Files.createDirectories(directory.resolve("fam"));
    Files.writeString(directory.resolve("fam/bad.pb.hex"), "0A0B\n");
    GoldenStore store = GoldenStore.inDirectory(directory);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> store.loadBinary("fam", "bad", "pb.hex"));
    assertTrue(error.getMessage().contains("fam/bad.pb.hex"), error.getMessage());
  }
}
