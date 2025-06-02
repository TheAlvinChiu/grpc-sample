package com.grpcsample.util;

import com.grpcsample.client.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * 命令行測試工具，用於測試 gRPC 服務
 */
public class GrpcTester {
    private static final Logger logger = LoggerFactory.getLogger(GrpcTester.class);

    public static void main(String[] args) {
        String host = "localhost";
        int port = 50051;
        boolean useTls = true; // 默認使用 TLS

        // 解析命令行參數
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                if ("--host".equals(args[i]) && i + 1 < args.length) {
                    host = args[++i];
                } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                    port = Integer.parseInt(args[++i]);
                } else if ("--no-tls".equals(args[i])) {
                    useTls = false;
                }
            }
        }

        logger.info("Connecting to gRPC server at {}:{} with TLS {}",
                host, port, useTls ? "enabled" : "disabled");

        GrpcClient client = null;
        try {
            // 創建客戶端
            if (useTls) {
                File certFile = new File("src/main/resources/keystore/grpc-server-cert.pem");
                if (!certFile.exists()) {
                    logger.error("Certificate file not found: {}", certFile.getAbsolutePath());
                    logger.info("Please run KeytoolCertificateGenerator first");
                    return;
                }
                client = new GrpcClient(host, port, certFile);
            } else {
                client = new GrpcClient(host, port);
            }

            // 測試所有 RPC 方法
            runAllTests(client);

        } catch (Exception e) {
            logger.error("Error during gRPC testing", e);
        } finally {
            if (client != null) {
                try {
                    client.shutdown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Interrupted during client shutdown", e);
                }
            }
        }
    }

    private static void runAllTests(GrpcClient client) {
        // 測試 Unary RPC (簡單 RPC)
        testUnaryRpc(client);

        // 測試 Server Streaming RPC
        testServerStreamingRpc(client);

        // 測試 Client Streaming RPC
        testClientStreamingRpc(client);

        // 測試 Bidirectional Streaming RPC
        testBidirectionalStreamingRpc(client);
    }

    private static void testUnaryRpc(GrpcClient client) {
        logger.info("\n==== Testing Unary RPC ====");
        try {
            String response = client.sayHello("Test User - Unary");
            logger.info("Unary RPC Response: {}", response);
            logger.info("Unary RPC test: SUCCESS");
        } catch (Exception e) {
            logger.error("Unary RPC test: FAILED", e);
        }
    }

    private static void testServerStreamingRpc(GrpcClient client) {
        logger.info("\n==== Testing Server Streaming RPC ====");
        try {
            client.testServerStream("Test User - Server Stream");
            logger.info("Server Streaming RPC test: SUCCESS");
        } catch (Exception e) {
            logger.error("Server Streaming RPC test: FAILED", e);
        }
    }

    private static void testClientStreamingRpc(GrpcClient client) {
        logger.info("\n==== Testing Client Streaming RPC ====");
        try {
            List<String> messages = Arrays.asList(
                    "Client Stream Message 1",
                    "Client Stream Message 2",
                    "Client Stream Message 3",
                    "Client Stream Message 4",
                    "Client Stream Message 5"
            );
            String response = client.testClientStream(messages);
            logger.info("Client Streaming RPC Response: {}", response);
            logger.info("Client Streaming RPC test: SUCCESS");
        } catch (Exception e) {
            logger.error("Client Streaming RPC test: FAILED", e);
        }
    }

    private static void testBidirectionalStreamingRpc(GrpcClient client) {
        logger.info("\n==== Testing Bidirectional Streaming RPC ====");
        try {
            List<String> messages = Arrays.asList(
                    "Bidirectional Message 1",
                    "Bidirectional Message 2",
                    "Bidirectional Message 3",
                    "Bidirectional Message 4",
                    "Bidirectional Message 5"
            );
            client.testBidirectionalStream(messages);
            logger.info("Bidirectional Streaming RPC test: SUCCESS");
        } catch (Exception e) {
            logger.error("Bidirectional Streaming RPC test: FAILED", e);
        }
    }
}