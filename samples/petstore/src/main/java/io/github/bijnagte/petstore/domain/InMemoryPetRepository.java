package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.Pet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.stereotype.Repository;

/** A {@link PetRepository} backed by a sorted concurrent map. */
@Repository
public class InMemoryPetRepository implements PetRepository {

  private final ConcurrentSkipListMap<UUID, Pet> pets = new ConcurrentSkipListMap<>();

  @Override
  public Pet save(Pet pet) {
    pets.put(pet.id(), pet);
    return pet;
  }

  @Override
  public Optional<Pet> findById(UUID id) {
    return Optional.ofNullable(pets.get(id));
  }

  @Override
  public List<Pet> findAll() {
    return List.copyOf(pets.values());
  }

  @Override
  public boolean deleteById(UUID id) {
    return pets.remove(id) != null;
  }
}
