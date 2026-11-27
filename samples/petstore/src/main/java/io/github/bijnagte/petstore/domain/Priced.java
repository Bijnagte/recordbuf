package io.github.bijnagte.petstore.domain;

/** Something with a price, implemented by the generated {@code Pet} record through the proto. */
public interface Priced {

  /**
   * Returns the price in cents.
   *
   * @return the price, in cents
   */
  long priceCents();
}
