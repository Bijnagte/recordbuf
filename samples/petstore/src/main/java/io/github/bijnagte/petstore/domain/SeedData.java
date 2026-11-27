package io.github.bijnagte.petstore.domain;

import io.github.bijnagte.petstore.model.Bird;
import io.github.bijnagte.petstore.model.Cat;
import io.github.bijnagte.petstore.model.Dog;
import io.github.bijnagte.petstore.model.Pet;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Adds a few pets at startup so the endpoints have something to show. */
@Component
public class SeedData implements ApplicationRunner {

  private final PetService petService;

  /**
   * Creates the seeder.
   *
   * @param petService the service the pets are added through
   */
  public SeedData(PetService petService) {
    this.petService = petService;
  }

  /**
   * The pets added at startup.
   *
   * @return the seed pets, without ids
   */
  static List<Pet> pets() {
    return List.of(
        Pet.builder()
            .name("Rex")
            .kind(new Pet.Kind.DogValue(Dog.builder().breed("Labrador").goodBoy(true).build()))
            .tags(List.of("friendly", "energetic"))
            .priceCents(25_000)
            .build(),
        Pet.builder()
            .name("Tom")
            .kind(new Pet.Kind.CatValue(Cat.builder().breed("Tabby").indoor(true).build()))
            .tags(List.of("calm"))
            .priceCents(18_000)
            .build(),
        Pet.builder()
            .name("Tweety")
            .kind(new Pet.Kind.BirdValue(Bird.builder().species("Canary").canTalk(false).build()))
            .priceCents(4_500)
            .build());
  }

  @Override
  public void run(ApplicationArguments args) {
    pets().forEach(petService::create);
  }
}
