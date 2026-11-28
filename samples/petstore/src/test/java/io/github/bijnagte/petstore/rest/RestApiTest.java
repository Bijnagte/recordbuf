package io.github.bijnagte.petstore.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bijnagte.petstore.model.Cat;
import io.github.bijnagte.petstore.model.Order;
import io.github.bijnagte.petstore.model.OrderEvent;
import io.github.bijnagte.petstore.model.Pet;
import io.github.bijnagte.petstore.model.PlaceOrderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.grpc.server.port=0")
class RestApiTest {

  @LocalServerPort int port;

  @Autowired JsonMapper json;

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }

  private Pet createCat() {
    Pet cat =
        Pet.builder()
            .name("Felix")
            .kind(Cat.builder().breed("Siamese").indoor(true).build())
            .build();
    return client()
        .post()
        .uri("/pets")
        .contentType(MediaType.APPLICATION_JSON)
        .body(cat)
        .retrieve()
        .body(Pet.class);
  }

  @Test
  void createdPetComesBackAsJsonWithDiscriminatorAndIsoTimestamp() {
    Pet created = createCat();

    assertNotNull(created);
    String body = client().get().uri("/pets/{id}", created.id()).retrieve().body(String.class);
    assertTrue(body.contains("\"cat\""), body);
    assertTrue(body.contains("\"addedAt\":\"20"), body);
    assertEquals(
        created, client().get().uri("/pets/{id}", created.id()).retrieve().body(Pet.class));
  }

  @Test
  void listUpdateAndDelete() {
    Pet created = createCat();

    List<Pet> pets =
        client().get().uri("/pets").retrieve().body(new ParameterizedTypeReference<>() {});
    assertTrue(pets.stream().anyMatch(p -> p.id().equals(created.id())));

    Pet updated =
        client()
            .put()
            .uri("/pets/{id}", created.id())
            .contentType(MediaType.APPLICATION_JSON)
            .body(created.toBuilder().name("Garfield").build())
            .retrieve()
            .body(Pet.class);
    assertEquals("Garfield", updated.name());

    client().delete().uri("/pets/{id}", created.id()).retrieve().toBodilessEntity();
    HttpClientErrorException gone =
        org.junit.jupiter.api.Assertions.assertThrows(
            HttpClientErrorException.class,
            () -> client().get().uri("/pets/{id}", created.id()).retrieve().body(Pet.class));
    assertEquals(HttpStatus.NOT_FOUND, gone.getStatusCode());
  }

  @Test
  void malformedBodyIsABadRequest() {
    HttpClientErrorException bad =
        org.junit.jupiter.api.Assertions.assertThrows(
            HttpClientErrorException.class,
            () ->
                client()
                    .post()
                    .uri("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{not json")
                    .retrieve()
                    .toBodilessEntity());
    assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
  }

  /** Covers AE2 over REST. */
  @Test
  void placedOrderShowsUpAsADecodedEvent() {
    Pet pet = createCat();
    int before = events().size();

    Order order =
        client()
            .post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PlaceOrderRequest.builder().petIds(List.of(pet.id().toString())).build())
            .retrieve()
            .body(Order.class);

    List<OrderEvent> events = events();
    assertEquals(before + 1, events.size());
    assertEquals(order, events.get(events.size() - 1).order());
  }

  @Test
  void unknownJsonPropertiesAreAcceptedAndKept() throws Exception {
    Pet pet = json.readValue("{\"name\":\"Odd\",\"mystery\":42}", Pet.class);

    assertEquals("Odd", pet.name());
    assertEquals(42, pet.jsonExtras().get("mystery").asInt());
  }

  private List<OrderEvent> events() {
    return client().get().uri("/events").retrieve().body(new ParameterizedTypeReference<>() {});
  }
}
