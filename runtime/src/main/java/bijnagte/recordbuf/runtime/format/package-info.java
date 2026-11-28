/**
 * The format registry a {@code recordbuf.java_type} format field's generated surface code resolves
 * through: {@link Format} is the {@code String ⇄ T} contract, {@link FormatRegistry} maps a Java
 * type to its format (seeded from {@link java.util.ServiceLoader} discovery), and {@link
 * UuidFormat} / {@link UriFormat} are the built-in formats.
 *
 * <p>Default-on-non-null (JSpecify).
 */
@NullMarked
package bijnagte.recordbuf.runtime.format;

import org.jspecify.annotations.NullMarked;
