package com.example.mediaservice;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.42.2)",
    comments = "Source: media.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class MediaServiceGrpc {

  private MediaServiceGrpc() {}

  public static final String SERVICE_NAME = "MediaService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.example.mediaservice.VideoChunk,
      com.example.mediaservice.UploadResponse> getUploadVideoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UploadVideo",
      requestType = com.example.mediaservice.VideoChunk.class,
      responseType = com.example.mediaservice.UploadResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.example.mediaservice.VideoChunk,
      com.example.mediaservice.UploadResponse> getUploadVideoMethod() {
    io.grpc.MethodDescriptor<com.example.mediaservice.VideoChunk, com.example.mediaservice.UploadResponse> getUploadVideoMethod;
    if ((getUploadVideoMethod = MediaServiceGrpc.getUploadVideoMethod) == null) {
      synchronized (MediaServiceGrpc.class) {
        if ((getUploadVideoMethod = MediaServiceGrpc.getUploadVideoMethod) == null) {
          MediaServiceGrpc.getUploadVideoMethod = getUploadVideoMethod =
              io.grpc.MethodDescriptor.<com.example.mediaservice.VideoChunk, com.example.mediaservice.UploadResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UploadVideo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.example.mediaservice.VideoChunk.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.example.mediaservice.UploadResponse.getDefaultInstance()))
              .setSchemaDescriptor(new MediaServiceMethodDescriptorSupplier("UploadVideo"))
              .build();
        }
      }
    }
    return getUploadVideoMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.example.mediaservice.Empty,
      com.example.mediaservice.VideoList> getGetVideoListMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetVideoList",
      requestType = com.example.mediaservice.Empty.class,
      responseType = com.example.mediaservice.VideoList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.example.mediaservice.Empty,
      com.example.mediaservice.VideoList> getGetVideoListMethod() {
    io.grpc.MethodDescriptor<com.example.mediaservice.Empty, com.example.mediaservice.VideoList> getGetVideoListMethod;
    if ((getGetVideoListMethod = MediaServiceGrpc.getGetVideoListMethod) == null) {
      synchronized (MediaServiceGrpc.class) {
        if ((getGetVideoListMethod = MediaServiceGrpc.getGetVideoListMethod) == null) {
          MediaServiceGrpc.getGetVideoListMethod = getGetVideoListMethod =
              io.grpc.MethodDescriptor.<com.example.mediaservice.Empty, com.example.mediaservice.VideoList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetVideoList"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.example.mediaservice.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.example.mediaservice.VideoList.getDefaultInstance()))
              .setSchemaDescriptor(new MediaServiceMethodDescriptorSupplier("GetVideoList"))
              .build();
        }
      }
    }
    return getGetVideoListMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.example.mediaservice.VideoRequest,
      com.example.mediaservice.VideoResponse> getGetVideoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetVideo",
      requestType = com.example.mediaservice.VideoRequest.class,
      responseType = com.example.mediaservice.VideoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.example.mediaservice.VideoRequest,
      com.example.mediaservice.VideoResponse> getGetVideoMethod() {
    io.grpc.MethodDescriptor<com.example.mediaservice.VideoRequest, com.example.mediaservice.VideoResponse> getGetVideoMethod;
    if ((getGetVideoMethod = MediaServiceGrpc.getGetVideoMethod) == null) {
      synchronized (MediaServiceGrpc.class) {
        if ((getGetVideoMethod = MediaServiceGrpc.getGetVideoMethod) == null) {
          MediaServiceGrpc.getGetVideoMethod = getGetVideoMethod =
              io.grpc.MethodDescriptor.<com.example.mediaservice.VideoRequest, com.example.mediaservice.VideoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetVideo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.example.mediaservice.VideoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.example.mediaservice.VideoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new MediaServiceMethodDescriptorSupplier("GetVideo"))
              .build();
        }
      }
    }
    return getGetVideoMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static MediaServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MediaServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MediaServiceStub>() {
        @java.lang.Override
        public MediaServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MediaServiceStub(channel, callOptions);
        }
      };
    return MediaServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static MediaServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MediaServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MediaServiceBlockingStub>() {
        @java.lang.Override
        public MediaServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MediaServiceBlockingStub(channel, callOptions);
        }
      };
    return MediaServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static MediaServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MediaServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MediaServiceFutureStub>() {
        @java.lang.Override
        public MediaServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MediaServiceFutureStub(channel, callOptions);
        }
      };
    return MediaServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class MediaServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void uploadVideo(com.example.mediaservice.VideoChunk request,
        io.grpc.stub.StreamObserver<com.example.mediaservice.UploadResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUploadVideoMethod(), responseObserver);
    }

    /**
     */
    public void getVideoList(com.example.mediaservice.Empty request,
        io.grpc.stub.StreamObserver<com.example.mediaservice.VideoList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetVideoListMethod(), responseObserver);
    }

    /**
     */
    public void getVideo(com.example.mediaservice.VideoRequest request,
        io.grpc.stub.StreamObserver<com.example.mediaservice.VideoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetVideoMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getUploadVideoMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.example.mediaservice.VideoChunk,
                com.example.mediaservice.UploadResponse>(
                  this, METHODID_UPLOAD_VIDEO)))
          .addMethod(
            getGetVideoListMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.example.mediaservice.Empty,
                com.example.mediaservice.VideoList>(
                  this, METHODID_GET_VIDEO_LIST)))
          .addMethod(
            getGetVideoMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.example.mediaservice.VideoRequest,
                com.example.mediaservice.VideoResponse>(
                  this, METHODID_GET_VIDEO)))
          .build();
    }
  }

  /**
   */
  public static final class MediaServiceStub extends io.grpc.stub.AbstractAsyncStub<MediaServiceStub> {
    private MediaServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MediaServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MediaServiceStub(channel, callOptions);
    }

    /**
     */
    public void uploadVideo(com.example.mediaservice.VideoChunk request,
        io.grpc.stub.StreamObserver<com.example.mediaservice.UploadResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUploadVideoMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getVideoList(com.example.mediaservice.Empty request,
        io.grpc.stub.StreamObserver<com.example.mediaservice.VideoList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetVideoListMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getVideo(com.example.mediaservice.VideoRequest request,
        io.grpc.stub.StreamObserver<com.example.mediaservice.VideoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetVideoMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class MediaServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<MediaServiceBlockingStub> {
    private MediaServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MediaServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MediaServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.example.mediaservice.UploadResponse uploadVideo(com.example.mediaservice.VideoChunk request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUploadVideoMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.example.mediaservice.VideoList getVideoList(com.example.mediaservice.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetVideoListMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.example.mediaservice.VideoResponse getVideo(com.example.mediaservice.VideoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetVideoMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class MediaServiceFutureStub extends io.grpc.stub.AbstractFutureStub<MediaServiceFutureStub> {
    private MediaServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MediaServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MediaServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.example.mediaservice.UploadResponse> uploadVideo(
        com.example.mediaservice.VideoChunk request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUploadVideoMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.example.mediaservice.VideoList> getVideoList(
        com.example.mediaservice.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetVideoListMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.example.mediaservice.VideoResponse> getVideo(
        com.example.mediaservice.VideoRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetVideoMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UPLOAD_VIDEO = 0;
  private static final int METHODID_GET_VIDEO_LIST = 1;
  private static final int METHODID_GET_VIDEO = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final MediaServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(MediaServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_UPLOAD_VIDEO:
          serviceImpl.uploadVideo((com.example.mediaservice.VideoChunk) request,
              (io.grpc.stub.StreamObserver<com.example.mediaservice.UploadResponse>) responseObserver);
          break;
        case METHODID_GET_VIDEO_LIST:
          serviceImpl.getVideoList((com.example.mediaservice.Empty) request,
              (io.grpc.stub.StreamObserver<com.example.mediaservice.VideoList>) responseObserver);
          break;
        case METHODID_GET_VIDEO:
          serviceImpl.getVideo((com.example.mediaservice.VideoRequest) request,
              (io.grpc.stub.StreamObserver<com.example.mediaservice.VideoResponse>) responseObserver);
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

  private static abstract class MediaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    MediaServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.example.mediaservice.Media.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("MediaService");
    }
  }

  private static final class MediaServiceFileDescriptorSupplier
      extends MediaServiceBaseDescriptorSupplier {
    MediaServiceFileDescriptorSupplier() {}
  }

  private static final class MediaServiceMethodDescriptorSupplier
      extends MediaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    MediaServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (MediaServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new MediaServiceFileDescriptorSupplier())
              .addMethod(getUploadVideoMethod())
              .addMethod(getGetVideoListMethod())
              .addMethod(getGetVideoMethod())
              .build();
        }
      }
    }
    return result;
  }
}
