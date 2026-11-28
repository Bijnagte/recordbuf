package io.github.bijnagte.petstore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bijnagte.petstore.model.Pet;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GenerationSmokeTest {

  @Test
  void generatedRecordRoundTripsOnTheWire() throws IOException {
    Pet pet = Pet.builder().id(UUID.randomUUID()).name("Rex").build();

    assertEquals(pet, Pet.fromBytes(pet.toByteArray()));
  }
}
