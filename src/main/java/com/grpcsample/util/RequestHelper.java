package com.grpcsample.util;

import com.google.protobuf.ByteString;
import com.grpcsample.grpc.GreetingServiceGrpc;
import com.grpcsample.grpc.HelloRequest;
import com.grpcsample.grpc.HelloReply;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;

import java.io.File;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public class RequestHelper {
    public static void main(String[] args) {
        // 創建不安全連接的示例（舊方式）
        createUnsecureExample();

        // 創建安全連接的示例（TLS）
        createSecureExample();
    }

    private static void createUnsecureExample() {
        // 創建 HelloRequest
        HelloRequest helloRequest = HelloRequest.newBuilder()
                .setName("Test User")
                .build();

        // 將 request 轉換為 ByteString
        ByteString byteString = helloRequest.toByteString();

        // 轉換為 Base64
        String base64Encoded = Base64.getEncoder().encodeToString(byteString.toByteArray());

        // 輸出可以直接在 Postman 中使用的完整請求（不安全）
        System.out.println("Use this request in Postman (Unsecure):");
        System.out.println("{\n" +
                "    \"target_service\": \"localhost:50051\",\n" +
                "    \"method_name\": \"greeting.GreetingService/sayHello\",\n" +
                "    \"request_data\": \"" + base64Encoded + "\"\n" +
                "}");
    }

    private static void createSecureExample() {
        try {
            // 創建 HelloRequest
            HelloRequest helloRequest = HelloRequest.newBuilder()
                    .setName("Test User (TLS)")
                    .build();

            // 將 request 轉換為 ByteString
            ByteString byteString = helloRequest.toByteString();

            // 轉換為 Base64
            String base64Encoded = Base64.getEncoder().encodeToString(byteString.toByteArray());

            // 輸出可以直接在 Postman 中使用的完整請求（安全）
            System.out.println("\nUse this request in Postman (Secure):");
            System.out.println("{\n" +
                    "    \"target_service\": \"localhost:50051\",\n" +
                    "    \"method_name\": \"greeting.GreetingService/sayHello\",\n" +
                    "    \"request_data\": \"" + base64Encoded + "\",\n" +
                    "    \"use_tls\": true,\n" +
                    "    \"tls_skip_verify\": false\n" +
                    "}");

            // 直接測試 TLS gRPC 連接
            System.out.println("\nTesting direct TLS gRPC connection...");

            // 載入證書
            File certFile = new File("src/main/resources/keystore/grpc-server-cert.pem");

            // 構建 SSL 上下文
            SslContext sslContext = GrpcSslContexts.forClient()
                    .trustManager(certFile)
                    .build();

            // 創建 TLS 通道
            ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", 50051)
                    .sslContext(sslContext)
                    .build();

            // 創建 stub
            GreetingServiceGrpc.GreetingServiceBlockingStub blockingStub =
                    GreetingServiceGrpc.newBlockingStub(channel);

            // 發送請求
            try {
                HelloReply reply = blockingStub.sayHello(helloRequest);
                System.out.println("Response from TLS gRPC server: " + reply.getMessage());
            } catch (Exception e) {
                System.err.println("Error calling gRPC service: " + e.getMessage());
            } finally {
                // 關閉通道
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }

        } catch (Exception e) {
            System.err.println("Error creating secure example: " + e.getMessage());
            e.printStackTrace();
        }
    }
}