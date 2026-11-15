/**
 * The code-emission spine of the {@code recordbuf} generator: a generator-side-ONLY library that
 * renders generated Java source ({@code .rec} records, {@code .rec} enums, nested {@code Codec},
 * interface implementations). It has NO runtime dependency and is never referenced by the generated
 * {@code .rec} surface (R22: the generated types stay dependency-free); it is a clean-room,
 * original implementation — NOT palantir/javapoet nor a copy of it.
 *
 * <p>Default-on-non-null (JSpecify): every type / member here is non-null by default; individual
 * {@code @Nullable} escapes are annotated explicitly. This is the project's cross-project default
 * and the same contract the generator's own packages carry; NullAway (an Error Prone check, wired
 * in this module's build) enforces it at error severity via {@code OnlyNullMarked=true}.
 */
@NullMarked
package bijnagte.recordbuf.codegen;

import org.jspecify.annotations.NullMarked;
