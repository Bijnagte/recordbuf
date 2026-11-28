package io.github.bijnagte.petstore.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.bijnagte.petstore.model.Bird;
import io.github.bijnagte.petstore.model.Cat;
import io.github.bijnagte.petstore.model.Dog;
import io.github.bijnagte.petstore.model.Pet;
import io.github.bijnagte.petstore.model.PetStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchemaShapeTest {

  @Test
  void everyOneofArmBuildsAndRoundTrips() throws IOException {
    for (Pet pet :
        List.of(
            Pet.builder()
                .id(UUID.randomUUID())
                .name("Rex")
                .kind(Dog.builder().breed("lab").build())
                .build(),
            Pet.builder()
                .id(UUID.randomUUID())
                .name("Tom")
                .kind(Cat.builder().indoor(true).build())
                .build(),
            Pet.builder()
                .id(UUID.randomUUID())
                .name("Tweety")
                .kind(Bird.builder().canTalk(true).build())
                .build())) {
      assertEquals(pet, Pet.fromBytes(pet.toByteArray()));
    }
  }

  @Test
  void petIsPriced() {
    Priced priced = Pet.builder().priceCents(1250).build();

    assertEquals(1250, priced.priceCents());
  }

  @Test
  void unsetOneofStaysUnsetAcrossTheWire() throws IOException {
    Pet pet = Pet.builder().id(UUID.randomUUID()).status(PetStatus.PET_STATUS_AVAILABLE).build();
    Pet back = Pet.fromBytes(pet.toByteArray());

    assertNull(back.kind());
    assertEquals(pet, back);
    assertEquals(List.of(), assertInstanceOf(Pet.class, back).tags());
  }

  @Test
  void timestampsKeepNanosecondPrecision() throws IOException {
    Instant at = Instant.parse("2026-11-27T10:15:30.123456789Z");
    Pet pet = Pet.builder().addedAt(at).build();

    assertEquals(at, Pet.fromBytes(pet.toByteArray()).addedAt());
  }
}
