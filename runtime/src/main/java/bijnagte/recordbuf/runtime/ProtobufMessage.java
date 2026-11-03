package bijnagte.recordbuf.runtime;

import java.io.IOException;

/**
 * The always-on Layer A marshalling hook every generated record implements.
 *
 * <p>Since a recordbuf record's wire bytes are byte-identical to the
 * equivalent protobuf-java message's bytes, a record can <em>be</em> the message
 * type at a gRPC / protobuf-java boundary with zero structural conversion: to
 * wire a record out, call {@link #toByteArray()}; to wire bytes in, a caller
 * with the concrete record type calls that type's generated static
 * {@code fromBytes(byte[])} (a static cannot ride a polymorphic interface, so
 * the read half is reached by a {@code Function<byte[], T>} held by
 * {@link ProtobufMarshaller}, never by reflection).
 *
 * <p>Exactly one instance method keeps the hook JIT-clean: a virtual call to a
 * {@code final} implementation that the record's own generated codec satisfies.
 */
public interface ProtobufMessage {
    /**
     * Wire-encode this record through its generated codec. The result is
     * byte-identical to the equivalent protobuf-java message's
     * {@code toByteArray()}, so it is safe to hand to any protobuf-java
     * consumer, including a gRPC marshaller.
     *
     * @throws IOException if the underlying encode fails
     */
    byte[] toByteArray() throws IOException;
}
