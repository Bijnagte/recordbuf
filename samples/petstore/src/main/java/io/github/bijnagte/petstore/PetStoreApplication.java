package io.github.bijnagte.petstore;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** The pet store sample: one set of recordbuf records served over REST and gRPC. */
@SpringBootApplication
public class PetStoreApplication {

  /**
   * Starts the application.
   *
   * @param args command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(PetStoreApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
