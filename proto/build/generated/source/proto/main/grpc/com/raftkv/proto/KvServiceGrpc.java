package com.raftkv.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Client-facing KV service
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.63.0)",
    comments = "Source: kv.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class KvServiceGrpc {

  private KvServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "raftkv.KvService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.raftkv.proto.GetRequest,
      com.raftkv.proto.GetResponse> getGetMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Get",
      requestType = com.raftkv.proto.GetRequest.class,
      responseType = com.raftkv.proto.GetResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.raftkv.proto.GetRequest,
      com.raftkv.proto.GetResponse> getGetMethod() {
    io.grpc.MethodDescriptor<com.raftkv.proto.GetRequest, com.raftkv.proto.GetResponse> getGetMethod;
    if ((getGetMethod = KvServiceGrpc.getGetMethod) == null) {
      synchronized (KvServiceGrpc.class) {
        if ((getGetMethod = KvServiceGrpc.getGetMethod) == null) {
          KvServiceGrpc.getGetMethod = getGetMethod =
              io.grpc.MethodDescriptor.<com.raftkv.proto.GetRequest, com.raftkv.proto.GetResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Get"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.GetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.GetResponse.getDefaultInstance()))
              .setSchemaDescriptor(new KvServiceMethodDescriptorSupplier("Get"))
              .build();
        }
      }
    }
    return getGetMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.raftkv.proto.PutRequest,
      com.raftkv.proto.PutResponse> getPutMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Put",
      requestType = com.raftkv.proto.PutRequest.class,
      responseType = com.raftkv.proto.PutResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.raftkv.proto.PutRequest,
      com.raftkv.proto.PutResponse> getPutMethod() {
    io.grpc.MethodDescriptor<com.raftkv.proto.PutRequest, com.raftkv.proto.PutResponse> getPutMethod;
    if ((getPutMethod = KvServiceGrpc.getPutMethod) == null) {
      synchronized (KvServiceGrpc.class) {
        if ((getPutMethod = KvServiceGrpc.getPutMethod) == null) {
          KvServiceGrpc.getPutMethod = getPutMethod =
              io.grpc.MethodDescriptor.<com.raftkv.proto.PutRequest, com.raftkv.proto.PutResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Put"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.PutRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.PutResponse.getDefaultInstance()))
              .setSchemaDescriptor(new KvServiceMethodDescriptorSupplier("Put"))
              .build();
        }
      }
    }
    return getPutMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.raftkv.proto.DeleteRequest,
      com.raftkv.proto.DeleteResponse> getDeleteMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Delete",
      requestType = com.raftkv.proto.DeleteRequest.class,
      responseType = com.raftkv.proto.DeleteResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.raftkv.proto.DeleteRequest,
      com.raftkv.proto.DeleteResponse> getDeleteMethod() {
    io.grpc.MethodDescriptor<com.raftkv.proto.DeleteRequest, com.raftkv.proto.DeleteResponse> getDeleteMethod;
    if ((getDeleteMethod = KvServiceGrpc.getDeleteMethod) == null) {
      synchronized (KvServiceGrpc.class) {
        if ((getDeleteMethod = KvServiceGrpc.getDeleteMethod) == null) {
          KvServiceGrpc.getDeleteMethod = getDeleteMethod =
              io.grpc.MethodDescriptor.<com.raftkv.proto.DeleteRequest, com.raftkv.proto.DeleteResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Delete"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.DeleteRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.DeleteResponse.getDefaultInstance()))
              .setSchemaDescriptor(new KvServiceMethodDescriptorSupplier("Delete"))
              .build();
        }
      }
    }
    return getDeleteMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.raftkv.proto.GetNodeRoleRequest,
      com.raftkv.proto.GetNodeRoleResponse> getGetNodeRoleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetNodeRole",
      requestType = com.raftkv.proto.GetNodeRoleRequest.class,
      responseType = com.raftkv.proto.GetNodeRoleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.raftkv.proto.GetNodeRoleRequest,
      com.raftkv.proto.GetNodeRoleResponse> getGetNodeRoleMethod() {
    io.grpc.MethodDescriptor<com.raftkv.proto.GetNodeRoleRequest, com.raftkv.proto.GetNodeRoleResponse> getGetNodeRoleMethod;
    if ((getGetNodeRoleMethod = KvServiceGrpc.getGetNodeRoleMethod) == null) {
      synchronized (KvServiceGrpc.class) {
        if ((getGetNodeRoleMethod = KvServiceGrpc.getGetNodeRoleMethod) == null) {
          KvServiceGrpc.getGetNodeRoleMethod = getGetNodeRoleMethod =
              io.grpc.MethodDescriptor.<com.raftkv.proto.GetNodeRoleRequest, com.raftkv.proto.GetNodeRoleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetNodeRole"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.GetNodeRoleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.raftkv.proto.GetNodeRoleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new KvServiceMethodDescriptorSupplier("GetNodeRole"))
              .build();
        }
      }
    }
    return getGetNodeRoleMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static KvServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KvServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KvServiceStub>() {
        @java.lang.Override
        public KvServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KvServiceStub(channel, callOptions);
        }
      };
    return KvServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static KvServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KvServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KvServiceBlockingStub>() {
        @java.lang.Override
        public KvServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KvServiceBlockingStub(channel, callOptions);
        }
      };
    return KvServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static KvServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KvServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KvServiceFutureStub>() {
        @java.lang.Override
        public KvServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KvServiceFutureStub(channel, callOptions);
        }
      };
    return KvServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Client-facing KV service
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void get(com.raftkv.proto.GetRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.GetResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMethod(), responseObserver);
    }

    /**
     */
    default void put(com.raftkv.proto.PutRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.PutResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPutMethod(), responseObserver);
    }

    /**
     */
    default void delete(com.raftkv.proto.DeleteRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.DeleteResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteMethod(), responseObserver);
    }

    /**
     */
    default void getNodeRole(com.raftkv.proto.GetNodeRoleRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.GetNodeRoleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetNodeRoleMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service KvService.
   * <pre>
   * Client-facing KV service
   * </pre>
   */
  public static abstract class KvServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return KvServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service KvService.
   * <pre>
   * Client-facing KV service
   * </pre>
   */
  public static final class KvServiceStub
      extends io.grpc.stub.AbstractAsyncStub<KvServiceStub> {
    private KvServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KvServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KvServiceStub(channel, callOptions);
    }

    /**
     */
    public void get(com.raftkv.proto.GetRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.GetResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void put(com.raftkv.proto.PutRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.PutResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPutMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void delete(com.raftkv.proto.DeleteRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.DeleteResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getNodeRole(com.raftkv.proto.GetNodeRoleRequest request,
        io.grpc.stub.StreamObserver<com.raftkv.proto.GetNodeRoleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetNodeRoleMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service KvService.
   * <pre>
   * Client-facing KV service
   * </pre>
   */
  public static final class KvServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<KvServiceBlockingStub> {
    private KvServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KvServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KvServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.raftkv.proto.GetResponse get(com.raftkv.proto.GetRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.raftkv.proto.PutResponse put(com.raftkv.proto.PutRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPutMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.raftkv.proto.DeleteResponse delete(com.raftkv.proto.DeleteRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.raftkv.proto.GetNodeRoleResponse getNodeRole(com.raftkv.proto.GetNodeRoleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetNodeRoleMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service KvService.
   * <pre>
   * Client-facing KV service
   * </pre>
   */
  public static final class KvServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<KvServiceFutureStub> {
    private KvServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KvServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KvServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.raftkv.proto.GetResponse> get(
        com.raftkv.proto.GetRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.raftkv.proto.PutResponse> put(
        com.raftkv.proto.PutRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPutMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.raftkv.proto.DeleteResponse> delete(
        com.raftkv.proto.DeleteRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.raftkv.proto.GetNodeRoleResponse> getNodeRole(
        com.raftkv.proto.GetNodeRoleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetNodeRoleMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET = 0;
  private static final int METHODID_PUT = 1;
  private static final int METHODID_DELETE = 2;
  private static final int METHODID_GET_NODE_ROLE = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET:
          serviceImpl.get((com.raftkv.proto.GetRequest) request,
              (io.grpc.stub.StreamObserver<com.raftkv.proto.GetResponse>) responseObserver);
          break;
        case METHODID_PUT:
          serviceImpl.put((com.raftkv.proto.PutRequest) request,
              (io.grpc.stub.StreamObserver<com.raftkv.proto.PutResponse>) responseObserver);
          break;
        case METHODID_DELETE:
          serviceImpl.delete((com.raftkv.proto.DeleteRequest) request,
              (io.grpc.stub.StreamObserver<com.raftkv.proto.DeleteResponse>) responseObserver);
          break;
        case METHODID_GET_NODE_ROLE:
          serviceImpl.getNodeRole((com.raftkv.proto.GetNodeRoleRequest) request,
              (io.grpc.stub.StreamObserver<com.raftkv.proto.GetNodeRoleResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.raftkv.proto.GetRequest,
              com.raftkv.proto.GetResponse>(
                service, METHODID_GET)))
        .addMethod(
          getPutMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.raftkv.proto.PutRequest,
              com.raftkv.proto.PutResponse>(
                service, METHODID_PUT)))
        .addMethod(
          getDeleteMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.raftkv.proto.DeleteRequest,
              com.raftkv.proto.DeleteResponse>(
                service, METHODID_DELETE)))
        .addMethod(
          getGetNodeRoleMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.raftkv.proto.GetNodeRoleRequest,
              com.raftkv.proto.GetNodeRoleResponse>(
                service, METHODID_GET_NODE_ROLE)))
        .build();
  }

  private static abstract class KvServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    KvServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.raftkv.proto.KvProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("KvService");
    }
  }

  private static final class KvServiceFileDescriptorSupplier
      extends KvServiceBaseDescriptorSupplier {
    KvServiceFileDescriptorSupplier() {}
  }

  private static final class KvServiceMethodDescriptorSupplier
      extends KvServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    KvServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (KvServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new KvServiceFileDescriptorSupplier())
              .addMethod(getGetMethod())
              .addMethod(getPutMethod())
              .addMethod(getDeleteMethod())
              .addMethod(getGetNodeRoleMethod())
              .build();
        }
      }
    }
    return result;
  }
}
