package io.github.bijnagte.petstore.rest;

import io.github.bijnagte.petstore.domain.OrderService;
import io.github.bijnagte.petstore.model.Order;
import io.github.bijnagte.petstore.model.PlaceOrderRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for orders. */
@RestController
@RequestMapping("/orders")
public class OrderController {

  private final OrderService orders;

  OrderController(OrderService orders) {
    this.orders = orders;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Order place(@RequestBody PlaceOrderRequest request) {
    return orders.place(request);
  }

  @GetMapping("/{id}")
  Order get(@PathVariable String id) {
    return orders.get(id);
  }
}
