package io.github.bijnagte.petstore.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bijnagte.petstore.legacy.LegacyPet;
import io.github.bijnagte.petstore.model.Cat;
import io.github.bijnagte.petstore.model.Pet;
import io.github.bijnagte.petstore.model.PetStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Schema evolution on the protobuf wire. A service built against an older schema reads a message
 * from a newer one, passes it on, and loses nothing.
 */
class UnknownFieldsTest {

  private final Pet newer =
      Pet.builder()
          .id("42")
          .name("Felix")
          .kind(new Pet.Kind.CatValue(Cat.builder().breed("Siamese").indoor(true).build()))
          .status(PetStatus.PET_STATUS_RESERVED)
          .addedAt(Instant.parse("2026-11-27T10:15:30.123456789Z"))
          .tags(List.of("calm", "quiet"))
          .priceCents(19_900)
          .build();

  /** Covers AE3. */
  @Test
  void fieldsTheOlderSchemaLacksSurviveADecodeAndEncode() throws IOException {
    byte[] wire = newer.toByteArray();

    LegacyPet legacy = LegacyPet.fromBytes(wire);
    byte[] forwarded = legacy.toByteArray();

    assertEquals("Felix", legacy.name());
    assertEquals(newer, Pet.fromBytes(forwarded));
  }

  @Test
  void theCarrierHoldsWhatTheOlderSchemaDoesNotModel() throws IOException {
    LegacyPet legacy = LegacyPet.fromBytes(newer.toByteArray());

    assertFalse(legacy.unknownFields().isEmpty());
    assertTrue(
        LegacyPet.fromBytes(LegacyPet.builder().id("1").build().toByteArray())
            .unknownFields()
            .isEmpty());
  }

  @Test
  void forwardedBytesHoldEverythingTheOriginalDid() throws IOException {
    byte[] wire = newer.toByteArray();

    byte[] forwarded = LegacyPet.fromBytes(wire).toByteArray();

    assertEquals(wire.length, forwarded.length);
  }
}
