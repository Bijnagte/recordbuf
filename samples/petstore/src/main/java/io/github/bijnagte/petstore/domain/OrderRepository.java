package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.Order;
import java.util.Optional;

/** Storage for orders; the in-memory implementation marks where a database would plug in. */
public interface OrderRepository {

  /**
   * Stores an order, replacing any order with the same id.
   *
   * @param order the order to store
   * @return the stored order
   */
  Order save(Order order);

  /**
   * Looks an order up.
   *
   * @param id the order id
   * @return the order, or empty when there is none
   */
  Optional<Order> findById(String id);
}
