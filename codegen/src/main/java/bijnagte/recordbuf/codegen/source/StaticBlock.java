package bijnagte.recordbuf.codegen.source;

import java.util.List;

/** A {@code static { ... }} initializer. */
public record StaticBlock(List<Element> body) implements Member {}
