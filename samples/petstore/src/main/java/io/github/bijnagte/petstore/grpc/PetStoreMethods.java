package io.github.bijnagte.petstore.grpc;

import io.github.bijnagte.petstore.model.DeletePetRequest;
import io.github.bijnagte.petstore.model.DeletePetResponse;
import io.github.bijnagte.petstore.model.GetOrderRequest;
import io.github.bijnagte.petstore.model.GetPetRequest;
import io.github.bijnagte.petstore.model.ListPetsRequest;
import io.github.bijnagte.petstore.model.ListPetsResponse;
import io.github.bijnagte.petstore.model.Order;
import io.github.bijnagte.petstore.model.OrderStatusUpdate;
import io.github.bijnagte.petstore.model.Pet;
import io.github.bijnagte.petstore.model.PlaceOrderRequest;
import io.github.bijnagte.petstore.model.WatchOrderRequest;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;

/**
 * Every RPC of the pet store, described once and shared by the servers and the clients. This takes
 * the place of the {@code *Grpc} class that protoc-gen-grpc-java would have generated.
 */
public final class PetStoreMethods {

  /** The pet service name. */
  public static final String PET_SERVICE = "petstore.v1.PetService";

  /** The order service name. */
  public static final String ORDER_SERVICE = "petstore.v1.OrderService";

  /** Creates a pet. */
  public static final MethodDescriptor<Pet, Pet> CREATE_PET =
      ServiceDefinitions.method(
          PET_SERVICE, "CreatePet", MethodType.UNARY, Pet::fromBytes, Pet::fromBytes);

  /** Fetches a pet. */
  public static final MethodDescriptor<GetPetRequest, Pet> GET_PET =
      ServiceDefinitions.method(
          PET_SERVICE, "GetPet", MethodType.UNARY, GetPetRequest::fromBytes, Pet::fromBytes);

  /** Lists all pets. */
  public static final MethodDescriptor<ListPetsRequest, ListPetsResponse> LIST_PETS =
      ServiceDefinitions.method(
          PET_SERVICE,
          "ListPets",
          MethodType.UNARY,
          ListPetsRequest::fromBytes,
          ListPetsResponse::fromBytes);

  /** Replaces a pet; the id in the pet picks which one. */
  public static final MethodDescriptor<Pet, Pet> UPDATE_PET =
      ServiceDefinitions.method(
          PET_SERVICE, "UpdatePet", MethodType.UNARY, Pet::fromBytes, Pet::fromBytes);

  /** Deletes a pet. */
  public static final MethodDescriptor<DeletePetRequest, DeletePetResponse> DELETE_PET =
      ServiceDefinitions.method(
          PET_SERVICE,
          "DeletePet",
          MethodType.UNARY,
          DeletePetRequest::fromBytes,
          DeletePetResponse::fromBytes);

  /** Places an order. */
  public static final MethodDescriptor<PlaceOrderRequest, Order> PLACE_ORDER =
      ServiceDefinitions.method(
          ORDER_SERVICE,
          "PlaceOrder",
          MethodType.UNARY,
          PlaceOrderRequest::fromBytes,
          Order::fromBytes);

  /** Fetches an order. */
  public static final MethodDescriptor<GetOrderRequest, Order> GET_ORDER =
      ServiceDefinitions.method(
          ORDER_SERVICE,
          "GetOrder",
          MethodType.UNARY,
          GetOrderRequest::fromBytes,
          Order::fromBytes);

  /** Streams an order's status changes until it is delivered. */
  public static final MethodDescriptor<WatchOrderRequest, OrderStatusUpdate> WATCH_ORDER =
      ServiceDefinitions.method(
          ORDER_SERVICE,
          "WatchOrder",
          MethodType.SERVER_STREAMING,
          WatchOrderRequest::fromBytes,
          OrderStatusUpdate::fromBytes);

  private PetStoreMethods() {}
}
