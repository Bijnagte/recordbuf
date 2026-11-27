package io.github.bijnagte.petstore.grpc;

import bijnagte.recordbuf.runtime.ProtobufMessage;
import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.stub.ClientCalls;
import java.util.Iterator;

/**
 * Calls the pet store over a gRPC channel using the shared {@link PetStoreMethods}. Used by the
 * tests and handy for trying the server from a {@code main} or a JShell session.
 */
public final class GrpcClients {

  private GrpcClients() {}

  /**
   * Makes a blocking unary call.
   *
   * @param channel the channel to the server
   * @param method the method to call
   * @param request the request record
   * @return the response record
   * @param <Req> the request type
   * @param <Resp> the response type
   */
  public static <Req extends ProtobufMessage, Resp extends ProtobufMessage> Resp call(
      Channel channel, MethodDescriptor<Req, Resp> method, Req request) {
    return ClientCalls.blockingUnaryCall(channel, method, io.grpc.CallOptions.DEFAULT, request);
  }

  /**
   * Makes a blocking server-streaming call.
   *
   * @param channel the channel to the server
   * @param method the method to call
   * @param request the request record
   * @return the responses as they arrive
   * @param <Req> the request type
   * @param <Resp> the response type
   */
  public static <Req extends ProtobufMessage, Resp extends ProtobufMessage> Iterator<Resp> stream(
      Channel channel, MethodDescriptor<Req, Resp> method, Req request) {
    return ClientCalls.blockingServerStreamingCall(
        channel, method, io.grpc.CallOptions.DEFAULT, request);
  }
}
