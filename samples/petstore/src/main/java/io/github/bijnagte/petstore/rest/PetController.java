package io.github.bijnagte.petstore.rest;

import io.github.bijnagte.petstore.domain.PetService;
import io.github.bijnagte.petstore.model.Pet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for pets. The request and response bodies are the generated recordbuf records;
 * Jackson reads and writes them through the annotations recordbuf generated on them.
 */
@RestController
@RequestMapping("/pets")
public class PetController {

  private final PetService pets;

  PetController(PetService pets) {
    this.pets = pets;
  }

  @GetMapping
  List<Pet> list() {
    return pets.list();
  }

  @GetMapping("/{id}")
  Pet get(@PathVariable String id) {
    return pets.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Pet create(@RequestBody Pet pet) {
    return pets.create(pet);
  }

  @PutMapping("/{id}")
  Pet update(@PathVariable String id, @RequestBody Pet pet) {
    return pets.update(id, pet);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id) {
    pets.delete(id);
  }
}
