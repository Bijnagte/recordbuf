package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.OrderEvent;
import io.github.bijnagte.petstore.model.OrderEventAvroConverter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.avro.util.ClassSecurityValidator;
import org.springframework.stereotype.Component;
import petstore.v1.OrderEventAvro;

/**
 * An {@link EventLog} that stores each event as Avro bytes, the way it would be written to a file
 * or a topic. The record converts to its generated Avro twin and back with the generated converter,
 * so the sample has no mapping code of its own.
 */
@Component
public class AvroEventLog implements EventLog {

  static {
    // Avro refuses to instantiate classes it was not told to trust. The generated Avro records
    // live in the proto's package, so trust that package alongside Avro's defaults.
    ClassSecurityValidator.setGlobal(
        ClassSecurityValidator.composite(
            ClassSecurityValidator.getGlobal(),
            type -> type.getPackageName().equals("petstore.v1")));
  }

  private final CopyOnWriteArrayList<ByteBuffer> encoded = new CopyOnWriteArrayList<>();

  @Override
  public void append(OrderEvent event) {
    try {
      encoded.add(OrderEventAvroConverter.fromRecord(event).toByteBuffer());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public List<OrderEvent> events() {
    return encoded.stream().map(AvroEventLog::decode).toList();
  }

  private static OrderEvent decode(ByteBuffer bytes) {
    try {
      return OrderEventAvroConverter.toRecord(OrderEventAvro.fromByteBuffer(bytes.duplicate()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
