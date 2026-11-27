package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.Order;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** An {@link OrderRepository} backed by a concurrent map. */
@Repository
public class InMemoryOrderRepository implements OrderRepository {

  private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();

  @Override
  public Order save(Order order) {
    orders.put(order.id(), order);
    return order;
  }

  @Override
  public Optional<Order> findById(String id) {
    return Optional.ofNullable(orders.get(id));
  }
}
