package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.Order;
import io.github.bijnagte.petstore.model.OrderEvent;
import io.github.bijnagte.petstore.model.OrderStatus;
import io.github.bijnagte.petstore.model.OrderStatusUpdate;
import io.github.bijnagte.petstore.model.PlaceOrderRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Order operations shared by the REST and gRPC endpoints. */
@Service
public class OrderService {

  private final OrderRepository orders;
  private final PetRepository pets;
  private final EventLog eventLog;
  private final Clock clock;
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<OrderStatusUpdate>>>
      listeners = new ConcurrentHashMap<>();

  /**
   * Creates the service.
   *
   * @param orders where orders are stored
   * @param pets used to check that ordered pets exist
   * @param eventLog receives an event for every placed order
   * @param clock the source of "now"
   */
  public OrderService(OrderRepository orders, PetRepository pets, EventLog eventLog, Clock clock) {
    this.orders = orders;
    this.pets = pets;
    this.eventLog = eventLog;
    this.clock = clock;
  }

  /**
   * Places an order and records an event for it.
   *
   * @param request the pets to order
   * @return the stored order
   * @throws NotFoundException when a pet does not exist; nothing is stored or logged then
   */
  public Order place(PlaceOrderRequest request) {
    for (String petId : request.petIds()) {
      if (pets.findById(petId).isEmpty()) {
        throw new NotFoundException("pet", petId);
      }
    }
    Instant now = clock.instant();
    Order order =
        orders.save(
            Order.builder()
                .id(UUID.randomUUID().toString())
                .petIds(request.petIds())
                .attributes(request.attributes())
                .status(OrderStatus.ORDER_STATUS_PLACED)
                .placedAt(now)
                .build());
    eventLog.append(
        OrderEvent.builder()
            .orderId(order.id())
            .status(order.status())
            .occurredAt(now)
            .order(order)
            .build());
    return order;
  }

  /**
   * Finds an order.
   *
   * @param id the order id
   * @return the order
   * @throws NotFoundException when there is no such order
   */
  public Order get(String id) {
    return orders.findById(id).orElseThrow(() -> new NotFoundException("order", id));
  }

  /**
   * Moves an order to a new status and tells everyone watching it.
   *
   * @param id the order id
   * @param status the new status
   * @return the updated order
   * @throws NotFoundException when there is no such order
   */
  public Order advance(String id, OrderStatus status) {
    Order updated = orders.save(get(id).toBuilder().status(status).build());
    OrderStatusUpdate update =
        OrderStatusUpdate.builder().orderId(id).status(status).at(clock.instant()).build();
    List.copyOf(listeners.getOrDefault(id, new CopyOnWriteArrayList<>()))
        .forEach(listener -> listener.accept(update));
    return updated;
  }

  /**
   * Watches an order's status changes.
   *
   * @param id the order id
   * @param listener called for every later change
   * @return a handle that stops the watching
   * @throws NotFoundException when there is no such order
   */
  public Runnable watch(String id, Consumer<OrderStatusUpdate> listener) {
    get(id);
    CopyOnWriteArrayList<Consumer<OrderStatusUpdate>> forOrder =
        listeners.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
    forOrder.add(listener);
    return () -> forOrder.remove(listener);
  }

  /**
   * Counts the watchers of an order.
   *
   * @param id the order id
   * @return how many listeners are still registered
   */
  public int watcherCount(String id) {
    return listeners.getOrDefault(id, new CopyOnWriteArrayList<>()).size();
  }

  /**
   * Reads the event log.
   *
   * @return every recorded order event, oldest first
   */
  public List<OrderEvent> events() {
    return eventLog.events();
  }
}
