package io.github.bijnagte.petstore.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bijnagte.petstore.model.Order;
import io.github.bijnagte.petstore.model.OrderEvent;
import io.github.bijnagte.petstore.model.OrderStatus;
import io.github.bijnagte.petstore.model.OrderStatusUpdate;
import io.github.bijnagte.petstore.model.Pet;
import io.github.bijnagte.petstore.model.PetStatus;
import io.github.bijnagte.petstore.model.PlaceOrderRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final InMemoryPetRepository petRepository = new InMemoryPetRepository();
  private final AvroEventLog eventLog = new AvroEventLog();
  private final PetService petService = new PetService(petRepository, clock);
  private final OrderService orderService =
      new OrderService(new InMemoryOrderRepository(), petRepository, eventLog, clock);

  @Test
  void petCrudLifecycle() {
    Pet created = petService.create(Pet.builder().name("Rex").build());

    assertEquals(NOW, created.addedAt());
    assertEquals(PetStatus.PET_STATUS_AVAILABLE, created.status());
    assertEquals(created, petService.get(created.id()));
    assertEquals(List.of(created), petService.list());

    Pet updated = petService.update(created.id(), Pet.builder().name("Max").build());
    assertEquals("Max", updated.name());
    assertEquals(created.id(), updated.id());
    assertEquals(NOW, updated.addedAt());

    petService.delete(created.id());
    assertEquals(List.of(), petService.list());
  }

  @Test
  void missingPetsAreReportedAsNotFound() {
    assertThrows(NotFoundException.class, () -> petService.get("nope"));
    assertThrows(NotFoundException.class, () -> petService.update("nope", Pet.builder().build()));
    assertThrows(NotFoundException.class, () -> petService.delete("nope"));
  }

  /** Covers AE2 at the service level: the stored Avro bytes decode to the placed order. */
  @Test
  void placingAnOrderAppendsOneDecodableAvroEvent() {
    Pet pet = petService.create(Pet.builder().name("Rex").build());

    Order order = orderService.place(PlaceOrderRequest.builder().petIds(List.of(pet.id())).build());

    List<OrderEvent> events = orderService.events();
    assertEquals(1, events.size());
    assertEquals(order.id(), events.get(0).orderId());
    assertEquals(order, events.get(0).order());
  }

  @Test
  void orderForAnUnknownPetStoresAndLogsNothing() {
    assertThrows(
        NotFoundException.class,
        () -> orderService.place(PlaceOrderRequest.builder().petIds(List.of("nope")).build()));

    assertTrue(orderService.events().isEmpty());
  }

  @Test
  void watchersSeeStatusChangesUntilTheyStop() {
    Pet pet = petService.create(Pet.builder().name("Rex").build());
    Order order = orderService.place(PlaceOrderRequest.builder().petIds(List.of(pet.id())).build());
    List<OrderStatusUpdate> seen = new ArrayList<>();

    Runnable stop = orderService.watch(order.id(), seen::add);
    orderService.advance(order.id(), OrderStatus.ORDER_STATUS_PACKED);
    stop.run();
    orderService.advance(order.id(), OrderStatus.ORDER_STATUS_SHIPPED);

    assertEquals(1, seen.size());
    assertEquals(OrderStatus.ORDER_STATUS_PACKED, seen.get(0).status());
  }

  @Test
  void concurrentOrdersAreAllLogged() throws InterruptedException {
    Pet pet = petService.create(Pet.builder().name("Rex").build());
    PlaceOrderRequest request = PlaceOrderRequest.builder().petIds(List.of(pet.id())).build();
    int orders = 50;

    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < orders; i++) {
        pool.submit(() -> orderService.place(request));
      }
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    assertEquals(orders, orderService.events().size());
  }

  @Test
  void seedPetsAreAvailableAndTheLogStartsEmpty() {
    SeedData.pets().forEach(petService::create);

    assertEquals(3, petService.list().size());
    assertTrue(orderService.events().isEmpty());
  }
}
