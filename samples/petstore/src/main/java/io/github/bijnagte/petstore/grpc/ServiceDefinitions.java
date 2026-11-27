package io.github.bijnagte.petstore.grpc;

import bijnagte.recordbuf.runtime.ProtobufMarshaller;
import bijnagte.recordbuf.runtime.ProtobufMessage;
import io.github.bijnagte.petstore.domain.NotFoundException;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The small piece of gRPC plumbing this sample needs. recordbuf generates records, not gRPC stubs,
 * so the service definitions are assembled by hand: a {@link MethodDescriptor} per RPC whose
 * marshallers are recordbuf's {@link ProtobufMarshaller}, and a handler per method.
 *
 * <p>This is the part a future runtime helper could absorb. Everything else in the gRPC package
 * only says which methods exist and what they do.
 */
final class ServiceDefinitions {

  private ServiceDefinitions() {}

  /**
   * Builds a gRPC marshaller for a recordbuf record from the record's generated {@code fromBytes}.
   *
   * @param decode the record's static byte-array decoder
   * @return a marshaller that reads and writes the record on the protobuf wire
   * @param <T> the record type
   */
  static <T extends ProtobufMessage> MethodDescriptor.Marshaller<T> marshaller(
      ProtobufMarshaller.ByteDecoder<T> decode) {
    ProtobufMarshaller<T> wire = ProtobufMarshaller.of(decode);
    return new MethodDescriptor.Marshaller<>() {
      @Override
      public InputStream stream(T value) {
        try {
          return new ByteArrayInputStream(value.toByteArray());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      @Override
      public T parse(InputStream stream) {
        try {
          return wire.read(stream);
        } catch (IOException | UncheckedIOException e) {
          throw Status.INVALID_ARGUMENT
              .withDescription("Not a valid message")
              .withCause(e)
              .asRuntimeException();
        }
      }
    };
  }

  /**
   * Describes one RPC.
   *
   * @param service the full service name, for example {@code petstore.v1.PetService}
   * @param method the method name
   * @param type unary or server streaming
   * @param request the request record's decoder
   * @param response the response record's decoder
   * @return the method descriptor, usable by both server and client
   * @param <Req> the request type
   * @param <Resp> the response type
   */
  static <Req extends ProtobufMessage, Resp extends ProtobufMessage>
      MethodDescriptor<Req, Resp> method(
          String service,
          String method,
          MethodType type,
          ProtobufMarshaller.ByteDecoder<Req> request,
          ProtobufMarshaller.ByteDecoder<Resp> response) {
    return MethodDescriptor.<Req, Resp>newBuilder()
        .setType(type)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(service, method))
        .setRequestMarshaller(marshaller(request))
        .setResponseMarshaller(marshaller(response))
        .build();
  }

  /**
   * Starts a service definition.
   *
   * @param name the full service name
   * @return a builder to add methods to
   */
  static Builder service(String name) {
    return new Builder(name);
  }

  /** Collects handlers into a {@link BindableService} that gRPC servers can register. */
  static final class Builder {

    private final ServerServiceDefinition.Builder definition;

    private Builder(String name) {
      this.definition = ServerServiceDefinition.builder(name);
    }

    /**
     * Adds a unary method. A {@link NotFoundException} from the handler becomes {@code NOT_FOUND}.
     *
     * @param method the method descriptor
     * @param handler computes the response
     * @return this builder
     * @param <Req> the request type
     * @param <Resp> the response type
     */
    <Req extends ProtobufMessage, Resp extends ProtobufMessage> Builder unary(
        MethodDescriptor<Req, Resp> method, Function<Req, Resp> handler) {
      definition.addMethod(
          method,
          ServerCalls.asyncUnaryCall((request, observer) -> {
            try {
              observer.onNext(handler.apply(request));
              observer.onCompleted();
            } catch (NotFoundException e) {
              observer.onError(notFound(e));
            }
          }));
      return this;
    }

    /**
     * Adds a server-streaming method. The handler owns the stream and completes it.
     *
     * @param method the method descriptor
     * @param handler writes responses to the observer
     * @return this builder
     * @param <Req> the request type
     * @param <Resp> the response type
     */
    <Req extends ProtobufMessage, Resp extends ProtobufMessage> Builder serverStreaming(
        MethodDescriptor<Req, Resp> method, BiConsumer<Req, StreamObserver<Resp>> handler) {
      definition.addMethod(
          method,
          ServerCalls.asyncServerStreamingCall((request, observer) -> {
            try {
              handler.accept(request, observer);
            } catch (NotFoundException e) {
              observer.onError(notFound(e));
            }
          }));
      return this;
    }

    /**
     * Finishes the definition.
     *
     * @return a service gRPC servers can bind
     */
    BindableService build() {
      ServerServiceDefinition built = definition.build();
      return () -> built;
    }

    private static Throwable notFound(NotFoundException e) {
      return Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    }
  }
}
