package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.Pet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for pets. The sample ships only an in-memory implementation; this interface is the seam
 * where a database would plug in. It stores and returns the generated records as they are.
 */
public interface PetRepository {

  /**
   * Stores a pet, replacing any pet with the same id.
   *
   * @param pet the pet to store
   * @return the stored pet
   */
  Pet save(Pet pet);

  /**
   * Looks a pet up.
   *
   * @param id the pet id
   * @return the pet, or empty when there is none
   */
  Optional<Pet> findById(UUID id);

  /**
   * Lists every pet.
   *
   * @return the pets, ordered by id
   */
  List<Pet> findAll();

  /**
   * Removes a pet.
   *
   * @param id the pet id
   * @return whether a pet was removed
   */
  boolean deleteById(UUID id);
}
