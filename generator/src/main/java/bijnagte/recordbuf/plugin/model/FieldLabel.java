package bijnagte.recordbuf.plugin.model;

/** Label of a protobuf field. */
public enum FieldLabel {
    OPTIONAL,   // proto3 singular (with default) or proto2 optional
    REQUIRED,   // proto2 only
    REPEATED
}
