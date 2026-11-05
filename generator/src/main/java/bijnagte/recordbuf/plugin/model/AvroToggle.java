package bijnagte.recordbuf.plugin.model;

/**
 * The per-file {@code recordbuf.avro} option's tri-state as resolved <em>from the descriptor</em>
 * (the file-level option only, before the global {@code --avro} flag and the built-in default are
 * consulted).
 *
 * <p>{@link #NEUTRAL} means "no per-file option was declared for this file" (either the option was
 * absent entirely, or it was set to {@code RECORDBUF_AVRO_NONE}); the bridge is enabled by the
 * global {@code --avro} flag or, absent that, the built-in default of on. {@link #ON} forces
 * generation for this file; {@link #OFF} forces it off. A per-file {@link #ON}/{@link #OFF} wins
 * over the global flag and the default (R1), so the resolution that a caller derives from this is
 * {@code this.file() != NEUTRAL ? this.file().enabled() : flagOrDefault}.
 *
 * <p>Reads from the {@code FileDescriptorProto}'s unknown-field varint at the options tag (a
 * resolved extension lands there) or an uninterpreted option by name (the only path for a
 * hand-constructed descriptor).
 */
public enum AvroToggle {

  /** No per-file option declared: fall through to the global flag, then the built-in default. */
  NEUTRAL,
  /** The file forces the bridge on. */
  ON,
  /** The file forces the bridge off. */
  OFF;

  /**
   * Whether an <em>explicit</em> per-file control (not NEUTRAL) enables the bridge. {@link
   * #NEUTRAL} is not an explicit control and so contributes {@code false} to the "explicit per-file
   * wins" test; callers use it to decide whether a per-file option overrides the global flag.
   */
  public boolean isExplicitControl() {
    return this != NEUTRAL;
  }

  /** Whether THIS explicit control enables generation. */
  public boolean enabled() {
    return this == ON;
  }
}
