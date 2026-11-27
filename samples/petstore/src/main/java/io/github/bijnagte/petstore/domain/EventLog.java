package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.OrderEvent;
import java.util.List;

/** An append-only log of order events. */
public interface EventLog {

  /**
   * Appends an event.
   *
   * @param event the event to record
   */
  void append(OrderEvent event);

  /**
   * Reads every event back, oldest first.
   *
   * @return the recorded events
   */
  List<OrderEvent> events();
}
