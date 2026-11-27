package io.github.bijnagte.petstore.grpc;

import io.github.bijnagte.petstore.domain.PetService;
import io.github.bijnagte.petstore.model.DeletePetResponse;
import io.github.bijnagte.petstore.model.ListPetsResponse;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import org.springframework.stereotype.Component;

/** The gRPC pet endpoints. They call the same {@link PetService} as the REST controller. */
@Component
public class PetGrpcService implements BindableService {

  private final ServerServiceDefinition definition;

  PetGrpcService(PetService pets) {
    this.definition =
        ServiceDefinitions.service(PetStoreMethods.PET_SERVICE)
            .unary(PetStoreMethods.CREATE_PET, pets::create)
            .unary(PetStoreMethods.GET_PET, request -> pets.get(request.id()))
            .unary(
                PetStoreMethods.LIST_PETS,
                request -> ListPetsResponse.builder().pets(pets.list()).build())
            .unary(PetStoreMethods.UPDATE_PET, pet -> pets.update(pet.id(), pet))
            .unary(
                PetStoreMethods.DELETE_PET,
                request -> {
                  pets.delete(request.id());
                  return DeletePetResponse.builder().build();
                })
            .build()
            .bindService();
  }

  @Override
  public ServerServiceDefinition bindService() {
    return definition;
  }
}
