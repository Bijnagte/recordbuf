package io.github.bijnagte.petstore.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bijnagte.petstore.domain.OrderService;
import io.github.bijnagte.petstore.model.Bird;
import io.github.bijnagte.petstore.model.DeletePetRequest;
import io.github.bijnagte.petstore.model.GetOrderRequest;
import io.github.bijnagte.petstore.model.GetPetRequest;
import io.github.bijnagte.petstore.model.ListPetsRequest;
import io.github.bijnagte.petstore.model.Order;
import io.github.bijnagte.petstore.model.OrderStatus;
import io.github.bijnagte.petstore.model.OrderStatusUpdate;
import io.github.bijnagte.petstore.model.Pet;
import io.github.bijnagte.petstore.model.PlaceOrderRequest;
import io.github.bijnagte.petstore.model.WatchOrderRequest;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.grpc.server.port=0", "spring.grpc.server.inprocess.name=petstore-test"})
class GrpcApiTest {

  @LocalServerPort int port;

  @Autowired OrderService orders;

  private ManagedChannel channel;

  @BeforeEach
  void connect() {
    channel = InProcessChannelBuilder.forName("petstore-test").build();
  }

  @AfterEach
  void disconnect() {
    channel.shutdownNow();
  }

  private Pet newPet(String name) {
    return GrpcClients.call(
        channel,
        PetStoreMethods.CREATE_PET,
        Pet.builder()
            .name(name)
            .kind(Bird.builder().species("Parrot").canTalk(true).build())
            .build());
  }

  @Test
  void unaryCrudOverGrpc() {
    Pet created = newPet("Polly");

    assertEquals(
        created,
        GrpcClients.call(
            channel, PetStoreMethods.GET_PET, GetPetRequest.builder().id(created.id()).build()));
    assertTrue(
        GrpcClients.call(channel, PetStoreMethods.LIST_PETS, ListPetsRequest.builder().build())
            .pets()
            .contains(created));

    Pet updated =
        GrpcClients.call(
            channel, PetStoreMethods.UPDATE_PET, created.toBuilder().name("Pretty Polly").build());
    assertEquals("Pretty Polly", updated.name());

    GrpcClients.call(
        channel, PetStoreMethods.DELETE_PET, DeletePetRequest.builder().id(created.id()).build());
    StatusRuntimeException gone =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                GrpcClients.call(
                    channel,
                    PetStoreMethods.GET_PET,
                    GetPetRequest.builder().id(created.id()).build()));
    assertEquals(Status.Code.NOT_FOUND, gone.getStatus().getCode());
  }

  /** Covers AE1: a pet created over REST is the same record over gRPC. */
  @Test
  void petCreatedOverRestIsReturnedByGrpc() {
    Pet viaRest =
        RestClient.create("http://localhost:" + port)
            .post()
            .uri("/pets")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Pet.builder().name("Rest").build())
            .retrieve()
            .body(Pet.class);

    Pet viaGrpc =
        GrpcClients.call(
            channel, PetStoreMethods.GET_PET, GetPetRequest.builder().id(viaRest.id()).build());

    assertEquals(viaRest, viaGrpc);
  }

  @Test
  void unknownOrderIsNotFound() {
    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                GrpcClients.call(
                    channel,
                    PetStoreMethods.GET_ORDER,
                    GetOrderRequest.builder().id(UUID.randomUUID()).build()));
    assertEquals(Status.Code.NOT_FOUND, e.getStatus().getCode());
  }

  @Test
  void watchOrderStreamsStatusChangesThenCompletes() throws InterruptedException {
    Pet pet = newPet("Streamy");
    Order order =
        GrpcClients.call(
            channel,
            PetStoreMethods.PLACE_ORDER,
            PlaceOrderRequest.builder().petIds(List.of(pet.id().toString())).build());

    Iterator<OrderStatusUpdate> updates =
        GrpcClients.stream(
            channel,
            PetStoreMethods.WATCH_ORDER,
            WatchOrderRequest.builder().orderId(order.id().toString()).build());
    // The watcher registers once the server handles the call; wait for that before advancing.
    awaitWatchers(order.id(), 1);
    orders.advance(order.id(), OrderStatus.ORDER_STATUS_PACKED);
    orders.advance(order.id(), OrderStatus.ORDER_STATUS_SHIPPED);
    orders.advance(order.id(), OrderStatus.ORDER_STATUS_DELIVERED);

    List<OrderStatus> seen = new ArrayList<>();
    updates.forEachRemaining(update -> seen.add(update.status()));
    assertEquals(
        List.of(
            OrderStatus.ORDER_STATUS_PACKED,
            OrderStatus.ORDER_STATUS_SHIPPED,
            OrderStatus.ORDER_STATUS_DELIVERED),
        seen);
  }

  @Test
  void cancellingTheStreamReleasesTheWatcher() throws InterruptedException {
    Pet pet = newPet("Quitter");
    Order order =
        GrpcClients.call(
            channel,
            PetStoreMethods.PLACE_ORDER,
            PlaceOrderRequest.builder().petIds(List.of(pet.id().toString())).build());

    ClientCall<WatchOrderRequest, OrderStatusUpdate> call =
        channel.newCall(PetStoreMethods.WATCH_ORDER, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<>() {}, new Metadata());
    call.sendMessage(WatchOrderRequest.builder().orderId(order.id().toString()).build());
    call.halfClose();
    call.request(1);
    awaitWatchers(order.id(), 1);

    call.cancel("done", null);

    awaitWatchers(order.id(), 0);
  }

  private void awaitWatchers(UUID orderId, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (orders.watcherCount(orderId) != expected && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(expected, orders.watcherCount(orderId));
  }

  /** A newer client may send fields this schema does not know; they pass through untouched. */
  @Test
  void unknownFieldsFromANewerClientSurviveTheRoundTrip() throws IOException {
    Pet pet = newPet("Evolving");
    byte[] newer = pet.toByteArray();
    byte[] withExtra = java.util.Arrays.copyOf(newer, newer.length + 3);
    // field 99, varint, value 7
    withExtra[newer.length] = (byte) 0x98;
    withExtra[newer.length + 1] = 0x06;
    withExtra[newer.length + 2] = 0x07;
    Pet fromNewerClient = Pet.fromBytes(withExtra);
    assertFalse(fromNewerClient.unknownFields().isEmpty());

    GrpcClients.call(channel, PetStoreMethods.UPDATE_PET, fromNewerClient);

    byte[] stored =
        GrpcClients.call(
                channel, PetStoreMethods.GET_PET, GetPetRequest.builder().id(pet.id()).build())
            .toByteArray();
    assertEquals(0x07, stored[stored.length - 1]);
    assertEquals((byte) 0x98, stored[stored.length - 3]);
  }
}
