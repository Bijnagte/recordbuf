package bijnagte.recordbuf.codegen;

import bijnagte.recordbuf.codegen.source.Formatter;

/**
 * The identity {@link Formatter}: returns its input source unchanged.
 *
 * <p>Provided as a concrete, named type -- distinct from {@link Formatter#IDENTITY}, which is the
 * same behavior exposed as a default constant -- so a caller that wants an explicit no-op formatter
 * by name (rather than a one-off lambda) has a stable handle on it. It carries no state.
 */
public final class IdentityFormatter implements Formatter {

  @Override
  public String format(String source) {
    return source;
  }
}
