package com.grpcsample.client;

import com.grpcsample.grpc.GreetingServiceGrpc;
import com.grpcsample.grpc.HelloRequest;
import com.grpcsample.grpc.HelloReply;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNames;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class GrpcClient {
    private static final Logger logger = LoggerFactory.getLogger(GrpcClient.class);

    private final ManagedChannel channel;
    private final GreetingServiceGrpc.GreetingServiceBlockingStub blockingStub;
    private final GreetingServiceGrpc.GreetingServiceStub asyncStub;

    /**
     * Create client without TLS
     */
    public GrpcClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build());
    }

    /**
     * Create client with TLS
     */
    public GrpcClient(String host, int port, File certFile) throws Exception {
        this(createSecureChannel(host, port, certFile));
    }

    private GrpcClient(ManagedChannel channel) {
        this.channel = channel;
        blockingStub = GreetingServiceGrpc.newBlockingStub(channel);
        asyncStub = GreetingServiceGrpc.newStub(channel);
    }

    private static ManagedChannel createSecureChannel(String host, int port, File certFile) throws Exception {
        // Build SSL context with HTTP/2 ALPN enabled
        SslContext sslContext = GrpcSslContexts.forClient()
                .trustManager(certFile)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();

        // Create TLS channel
        return NettyChannelBuilder.forAddress(host, port)
                .sslContext(sslContext)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Synchronous call to sayHello method
     */
    public String sayHello(String name) {
        logger.info("Sending sayHello request, name: {}", name);
        try {
            HelloRequest request = HelloRequest.newBuilder().setName(name).build();
            HelloReply response = blockingStub.sayHello(request);
            logger.info("Received response: {}", response.getMessage());
            return response.getMessage();
        } catch (Exception e) {
            logger.error("sayHello call failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Test server streaming RPC
     */
    public void testServerStream(String name) {
        logger.info("Testing server streaming RPC, name: {}", name);
        HelloRequest request = HelloRequest.newBuilder().setName(name).build();

        final CountDownLatch finishLatch = new CountDownLatch(1);

        asyncStub.sayHellosServerStream(request, new StreamObserver<HelloReply>() {
            int responseCount = 0;

            @Override
            public void onNext(HelloReply value) {
                logger.info("Received server stream response #{}: {}", responseCount++, value.getMessage());
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Server stream error: {}", t.getMessage(), t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("Server stream completed, received {} responses", responseCount);
                finishLatch.countDown();
            }
        });

        try {
            if (!finishLatch.await(30, TimeUnit.SECONDS)) {
                logger.warn("Server stream timeout!");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for server stream completion", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Test client streaming RPC
     */
    public String testClientStream(List<String> names) {
        logger.info("Testing client streaming RPC, name list: {}", names);

        final CountDownLatch finishLatch = new CountDownLatch(1);
        final AtomicReference<String> response = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        StreamObserver<HelloReply> responseObserver = new StreamObserver<HelloReply>() {
            @Override
            public void onNext(HelloReply value) {
                logger.info("Received client stream response: {}", value.getMessage());
                response.set(value.getMessage());
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Client stream error: {}", t.getMessage(), t);
                error.set(t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("Client stream completed");
                finishLatch.countDown();
            }
        };

        StreamObserver<HelloRequest> requestObserver = asyncStub.sayHellosClientStream(responseObserver);

        try {
            for (String name : names) {
                logger.info("Sending client stream request: {}", name);
                HelloRequest request = HelloRequest.newBuilder().setName(name).build();
                requestObserver.onNext(request);

                // Brief delay to avoid sending too quickly
                Thread.sleep(200);
            }

            logger.info("Client completed sending requests");
            requestObserver.onCompleted();

            // Wait for server response
            if (!finishLatch.await(30, TimeUnit.SECONDS)) {
                logger.warn("Client stream timeout!");
                return "Client stream timeout!";
            }

            if (error.get() != null) {
                throw new RuntimeException("Client stream error", error.get());
            }

            return response.get();

        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for client stream completion", e);
            Thread.currentThread().interrupt();
            requestObserver.onError(e);
            return "Client stream interrupted: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Client stream processing error: {}", e.getMessage(), e);
            requestObserver.onError(e);
            return "Client stream error: " + e.getMessage();
        }
    }

    /**
     * Test bidirectional streaming RPC
     */
    public void testBidirectionalStream(List<String> names) {
        logger.info("Testing bidirectional streaming RPC, name list: {}", names);

        final CountDownLatch finishLatch = new CountDownLatch(1);

        StreamObserver<HelloReply> responseObserver = new StreamObserver<HelloReply>() {
            int responseCount = 0;

            @Override
            public void onNext(HelloReply value) {
                logger.info("Received bidirectional stream response #{}: {}", responseCount++, value.getMessage());
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Bidirectional stream error: {}", t.getMessage(), t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("Bidirectional stream completed, received {} responses", responseCount);
                finishLatch.countDown();
            }
        };

        StreamObserver<HelloRequest> requestObserver = asyncStub.sayHellosBidirectional(responseObserver);

        try {
            for (String name : names) {
                logger.info("Sending bidirectional stream request: {}", name);
                HelloRequest request = HelloRequest.newBuilder().setName(name).build();
                requestObserver.onNext(request);

                // Brief delay to avoid sending too quickly
                Thread.sleep(500);
            }

            logger.info("Client completed sending requests");
            requestObserver.onCompleted();

            // Wait for server completion
            if (!finishLatch.await(30, TimeUnit.SECONDS)) {
                logger.warn("Bidirectional stream timeout!");
            }

        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for bidirectional stream completion", e);
            Thread.currentThread().interrupt();
            requestObserver.onError(e);
        } catch (Exception e) {
            logger.error("Bidirectional stream processing error: {}", e.getMessage(), e);
            requestObserver.onError(e);
        }
    }

    /**
     * Main method for testing client
     */
    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 50051;
        String userName = args.length > 0 ? args[0] : "Test User";
        boolean useTls = args.length > 1 && "tls".equals(args[1]);

        GrpcClient client = null;
        try {
            if (useTls) {
                System.out.println("Creating TLS client...");
                File certFile = new File("src/main/resources/keystore/grpc-server-cert.pem");
                if (!certFile.exists()) {
                    System.err.println("Certificate file not found: " + certFile.getAbsolutePath());
                    System.err.println("Falling back to plaintext mode");
                    useTls = false;
                }
            }
            
            if (useTls) {
                File certFile = new File("src/main/resources/keystore/grpc-server-cert.pem");
                client = new GrpcClient(host, port, certFile);
            } else {
                System.out.println("Creating plaintext client...");
                client = new GrpcClient(host, port);
            }

            // Test synchronous sayHello
            System.out.println("\n=== Testing synchronous sayHello ===");
            String response = client.sayHello(userName);
            System.out.println("Response from sayHello: " + response);

            // Test server streaming RPC
            System.out.println("\n=== Testing server streaming RPC ===");
            client.testServerStream(userName);

            // Test client streaming RPC
            System.out.println("\n=== Testing client streaming RPC ===");
            List<String> clientStreamMessages = List.of("Message 1", "Message 2", "Message 3", "Message 4", "Message 5");
            String clientStreamResponse = client.testClientStream(clientStreamMessages);
            System.out.println("Response from client stream: " + clientStreamResponse);

            // Test bidirectional streaming RPC
            System.out.println("\n=== Testing bidirectional streaming RPC ===");
            List<String> bidirectionalMessages = List.of("Bidirectional 1", "Bidirectional 2", "Bidirectional 3");
            client.testBidirectionalStream(bidirectionalMessages);

        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }
}