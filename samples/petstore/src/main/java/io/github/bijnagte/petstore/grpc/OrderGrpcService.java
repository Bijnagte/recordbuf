package io.github.bijnagte.petstore.grpc;

import io.github.bijnagte.petstore.domain.OrderService;
import io.github.bijnagte.petstore.model.OrderStatus;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCallStreamObserver;
import org.springframework.stereotype.Component;

/**
 * The gRPC order endpoints, including {@code WatchOrder}, the server-streaming call REST has no
 * counterpart for.
 */
@Component
public class OrderGrpcService implements BindableService {

  private final ServerServiceDefinition definition;

  OrderGrpcService(OrderService orders) {
    this.definition =
        ServiceDefinitions.service(PetStoreMethods.ORDER_SERVICE)
            .unary(PetStoreMethods.PLACE_ORDER, orders::place)
            .unary(PetStoreMethods.GET_ORDER, request -> orders.get(request.id()))
            .serverStreaming(
                PetStoreMethods.WATCH_ORDER,
                (request, observer) -> {
                  Runnable stop =
                      orders.watch(
                          request.orderId(),
                          update -> {
                            observer.onNext(update);
                            if (update.status() == OrderStatus.ORDER_STATUS_DELIVERED) {
                              observer.onCompleted();
                            }
                          });
                  ((ServerCallStreamObserver<?>) observer).setOnCancelHandler(stop::run);
                })
            .build()
            .bindService();
  }

  @Override
  public ServerServiceDefinition bindService() {
    return definition;
  }
}
