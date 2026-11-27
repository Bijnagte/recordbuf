package io.github.bijnagte.petstore.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bijnagte.petstore.model.Pet;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ServiceDefinitionsTest {

  @Test
  void marshallerRoundTripsARecord() throws IOException {
    var marshaller = ServiceDefinitions.marshaller(Pet::fromBytes);
    Pet pet = Pet.builder().id("1").name("Rex").build();

    assertEquals(pet, marshaller.parse(marshaller.stream(pet)));
  }

  @Test
  void garbageBecomesInvalidArgument() {
    var marshaller = ServiceDefinitions.marshaller(Pet::fromBytes);

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () -> marshaller.parse(new ByteArrayInputStream(new byte[] {(byte) 0xff, 0x01})));
    assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
  }
}
