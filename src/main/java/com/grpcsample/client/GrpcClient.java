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
     * 創建不使用 TLS 的客戶端
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
     * 創建使用 TLS 的客戶端
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
        // 構建 SSL 上下文，啟用 HTTP/2 ALPN
        SslContext sslContext = GrpcSslContexts.forClient()
                .trustManager(certFile)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();

        // 創建 TLS 通道
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
     * 同步調用 sayHello 方法
     */
    public String sayHello(String name) {
        logger.info("發送 sayHello 請求，名稱: {}", name);
        try {
            HelloRequest request = HelloRequest.newBuilder().setName(name).build();
            HelloReply response = blockingStub.sayHello(request);
            logger.info("收到回應: {}", response.getMessage());
            return response.getMessage();
        } catch (Exception e) {
            logger.error("sayHello 調用失敗: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 測試服務端流式 RPC
     */
    public void testServerStream(String name) {
        logger.info("測試服務端流式 RPC，名稱: {}", name);
        HelloRequest request = HelloRequest.newBuilder().setName(name).build();

        final CountDownLatch finishLatch = new CountDownLatch(1);

        asyncStub.sayHellosServerStream(request, new StreamObserver<HelloReply>() {
            int responseCount = 0;

            @Override
            public void onNext(HelloReply value) {
                logger.info("收到服務端串流回應 #{}: {}", responseCount++, value.getMessage());
            }

            @Override
            public void onError(Throwable t) {
                logger.error("服務端串流錯誤: {}", t.getMessage(), t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("服務端串流完成，共收到 {} 條回應", responseCount);
                finishLatch.countDown();
            }
        });

        try {
            if (!finishLatch.await(30, TimeUnit.SECONDS)) {
                logger.warn("服務端串流超時!");
            }
        } catch (InterruptedException e) {
            logger.error("等待服務端串流完成時被中斷", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 測試客戶端流式 RPC
     */
    public String testClientStream(List<String> names) {
        logger.info("測試客戶端流式 RPC，名稱列表: {}", names);

        final CountDownLatch finishLatch = new CountDownLatch(1);
        final AtomicReference<String> response = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        StreamObserver<HelloReply> responseObserver = new StreamObserver<HelloReply>() {
            @Override
            public void onNext(HelloReply value) {
                logger.info("收到客戶端串流回應: {}", value.getMessage());
                response.set(value.getMessage());
            }

            @Override
            public void onError(Throwable t) {
                logger.error("客戶端串流錯誤: {}", t.getMessage(), t);
                error.set(t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("客戶端串流完成");
                finishLatch.countDown();
            }
        };

        StreamObserver<HelloRequest> requestObserver = asyncStub.sayHellosClientStream(responseObserver);

        try {
            for (String name : names) {
                logger.info("發送客戶端串流請求: {}", name);
                HelloRequest request = HelloRequest.newBuilder().setName(name).build();
                requestObserver.onNext(request);

                // 短暫延遲，避免過快發送
                Thread.sleep(200);
            }

            logger.info("客戶端完成發送請求");
            requestObserver.onCompleted();

            // 等待服務端回應
            if (!finishLatch.await(30, TimeUnit.SECONDS)) {
                logger.warn("客戶端串流超時!");
                return "客戶端串流超時!";
            }

            if (error.get() != null) {
                throw new RuntimeException("客戶端串流錯誤", error.get());
            }

            return response.get();

        } catch (InterruptedException e) {
            logger.error("等待客戶端串流完成時被中斷", e);
            Thread.currentThread().interrupt();
            requestObserver.onError(e);
            return "客戶端串流被中斷: " + e.getMessage();
        } catch (Exception e) {
            logger.error("客戶端串流處理錯誤: {}", e.getMessage(), e);
            requestObserver.onError(e);
            return "客戶端串流錯誤: " + e.getMessage();
        }
    }

    /**
     * 測試雙向流式 RPC
     */
    public void testBidirectionalStream(List<String> names) {
        logger.info("測試雙向流式 RPC，名稱列表: {}", names);

        final CountDownLatch finishLatch = new CountDownLatch(1);

        StreamObserver<HelloReply> responseObserver = new StreamObserver<HelloReply>() {
            int responseCount = 0;

            @Override
            public void onNext(HelloReply value) {
                logger.info("收到雙向串流回應 #{}: {}", responseCount++, value.getMessage());
            }

            @Override
            public void onError(Throwable t) {
                logger.error("雙向串流錯誤: {}", t.getMessage(), t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("雙向串流完成，共收到 {} 條回應", responseCount);
                finishLatch.countDown();
            }
        };

        StreamObserver<HelloRequest> requestObserver = asyncStub.sayHellosBidirectional(responseObserver);

        try {
            for (String name : names) {
                logger.info("發送雙向串流請求: {}", name);
                HelloRequest request = HelloRequest.newBuilder().setName(name).build();
                requestObserver.onNext(request);

                // 短暫延遲，避免過快發送
                Thread.sleep(500);
            }

            logger.info("客戶端完成發送請求");
            requestObserver.onCompleted();

            // 等待服務端完成
            if (!finishLatch.await(30, TimeUnit.SECONDS)) {
                logger.warn("雙向串流超時!");
            }

        } catch (InterruptedException e) {
            logger.error("等待雙向串流完成時被中斷", e);
            Thread.currentThread().interrupt();
            requestObserver.onError(e);
        } catch (Exception e) {
            logger.error("雙向串流處理錯誤: {}", e.getMessage(), e);
            requestObserver.onError(e);
        }
    }

    /**
     * 主方法用於測試客戶端
     */
    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 50051;
        boolean useTls = args.length > 0 && "tls".equals(args[0]);

        GrpcClient client = null;
        try {
            if (useTls) {
                System.out.println("Creating TLS client...");
                File certFile = new File("src/main/resources/keystore/grpc-server-cert.pem");
                client = new GrpcClient(host, port, certFile);
            } else {
                System.out.println("Creating plaintext client...");
                client = new GrpcClient(host, port);
            }

            // 測試同步 sayHello
            System.out.println("\n=== 測試同步 sayHello ===");
            String response = client.sayHello("Test User");
            System.out.println("Response from sayHello: " + response);

            // 測試服務端流 RPC
            System.out.println("\n=== 測試服務端流 RPC ===");
            client.testServerStream("Test User");

            // 測試客戶端流 RPC
            System.out.println("\n=== 測試客戶端流 RPC ===");
            List<String> clientStreamMessages = List.of("Message 1", "Message 2", "Message 3", "Message 4", "Message 5");
            String clientStreamResponse = client.testClientStream(clientStreamMessages);
            System.out.println("Response from client stream: " + clientStreamResponse);

            // 測試雙向流 RPC
            System.out.println("\n=== 測試雙向流 RPC ===");
            List<String> bidirectionalMessages = List.of("Bidirectional 1", "Bidirectional 2", "Bidirectional 3");
            client.testBidirectionalStream(bidirectionalMessages);

        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }
}