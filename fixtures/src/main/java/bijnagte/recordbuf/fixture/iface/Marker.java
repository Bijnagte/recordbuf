package bijnagte.recordbuf.fixture.iface;

/**
 * A user-supplied marker interface. The generated {@link Sample} record is expected to implement
 * this — the whole point of {@code option (recordbuf.java_interface)}. Marker interfaces make the
 * implementation trivially satisfiable, so they also serve as the cheapest compile-time proof that
 * the option does its job.
 */
public interface Marker {}
