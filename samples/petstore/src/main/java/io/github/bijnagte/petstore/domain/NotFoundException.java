package io.github.bijnagte.petstore.domain;

/** Thrown when a pet or order does not exist. Each transport maps it to its own "not found". */
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param kind what was looked up, for example {@code pet}
   * @param id the id that has no match
   */
  public NotFoundException(String kind, String id) {
    super("No " + kind + " with id " + id);
  }
}
