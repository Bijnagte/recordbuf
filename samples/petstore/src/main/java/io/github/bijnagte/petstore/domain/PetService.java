package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.Pet;
import io.github.bijnagte.petstore.model.PetStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Pet operations shared by the REST and gRPC endpoints. */
@Service
public class PetService {

  private final PetRepository pets;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param pets where pets are stored
   * @param clock the source of "now"
   */
  public PetService(PetRepository pets, Clock clock) {
    this.pets = pets;
    this.clock = clock;
  }

  /**
   * Adds a pet, assigning an id and the added-at time.
   *
   * @param pet the pet to add; any id it carries is ignored
   * @return the stored pet
   */
  public Pet create(Pet pet) {
    Pet.Builder stored = pet.toBuilder().id(UUID.randomUUID().toString()).addedAt(clock.instant());
    if (pet.status() == null || pet.status() == PetStatus.PET_STATUS_UNSPECIFIED) {
      stored.status(PetStatus.PET_STATUS_AVAILABLE);
    }
    return pets.save(stored.build());
  }

  /**
   * Finds a pet.
   *
   * @param id the pet id
   * @return the pet
   * @throws NotFoundException when there is no such pet
   */
  public Pet get(String id) {
    return pets.findById(id).orElseThrow(() -> new NotFoundException("pet", id));
  }

  /**
   * Lists every pet.
   *
   * @return the pets
   */
  public List<Pet> list() {
    return pets.findAll();
  }

  /**
   * Replaces a pet, keeping its id and original added-at time.
   *
   * @param id the pet id
   * @param pet the new content
   * @return the stored pet
   * @throws NotFoundException when there is no such pet
   */
  public Pet update(String id, Pet pet) {
    Pet existing = get(id);
    return pets.save(pet.toBuilder().id(id).addedAt(existing.addedAt()).build());
  }

  /**
   * Removes a pet.
   *
   * @param id the pet id
   * @throws NotFoundException when there is no such pet
   */
  public void delete(String id) {
    if (!pets.deleteById(id)) {
      throw new NotFoundException("pet", id);
    }
  }
}
