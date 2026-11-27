package io.github.bijnagte.petstore.grpc;

import io.github.bijnagte.petstore.model.ListPetsRequest;
import io.github.bijnagte.petstore.model.Pet;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists the pets of a running pet store over gRPC. Start the app, then {@code ./gradlew
 * runGrpcClient}. The generic gRPC tools such as grpcurl cannot call this server's methods by name
 * because recordbuf generates no service descriptors, so this client is the way to try it.
 */
public final class ExampleClient {

  private static final Logger LOG = LoggerFactory.getLogger(ExampleClient.class);

  private ExampleClient() {}

  /**
   * Prints every pet.
   *
   * @param args an optional {@code host:port}, default {@code localhost:9090}
   * @throws InterruptedException if interrupted while shutting down
   */
  public static void main(String[] args) throws InterruptedException {
    String target = args.length > 0 ? args[0] : "localhost:9090";
    ManagedChannel channel = NettyChannelBuilder.forTarget(target).usePlaintext().build();
    try {
      for (Pet pet :
          GrpcClients.call(channel, PetStoreMethods.LIST_PETS, ListPetsRequest.builder().build())
              .pets()) {
        LOG.info("{}", pet);
      }
    } finally {
      channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }
}
