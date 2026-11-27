package io.github.bijnagte.petstore.rest;

import io.github.bijnagte.petstore.domain.OrderService;
import io.github.bijnagte.petstore.model.OrderEvent;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows the order events. They are stored as Avro, so every event returned here was decoded from
 * Avro bytes first.
 */
@RestController
@RequestMapping("/events")
public class EventController {

  private final OrderService orders;

  EventController(OrderService orders) {
    this.orders = orders;
  }

  @GetMapping
  List<OrderEvent> events() {
    return orders.events();
  }
}
