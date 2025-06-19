package com.grpcsample;

import com.grpcsample.config.TlsConfigHelper;
import com.grpcsample.service.GreetingService;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class GrpcSampleApplication {
    private static final Logger logger = LoggerFactory.getLogger(GrpcSampleApplication.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        logger.info("Starting gRPC Sample Application");
        ApplicationContext context = SpringApplication.run(GrpcSampleApplication.class, args);

        // Get the server bean
        GrpcServer grpcServer = context.getBean(GrpcServer.class);
        grpcServer.start();

        // Keep the server running
        grpcServer.blockUntilShutdown();
    }

    @Component
    public static class GrpcServer {
        private Server server;
        private final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

        @Value("${grpc.server.port}")
        private int port;

        @Value("${grpc.server.tls.enabled:false}")
        private boolean tlsEnabled;

        @Autowired
        private GreetingService greetingService;

        @Autowired
        private TlsConfigHelper tlsConfigHelper;

        public void start() throws IOException {
            logger.info("Starting gRPC server on port {} with TLS {}", port, tlsEnabled ? "enabled" : "disabled");

            ServerBuilder<?> serverBuilder;

            if (tlsEnabled) {
                // Use NettyServerBuilder to support TLS
                try {
                    serverBuilder = NettyServerBuilder.forAddress(new InetSocketAddress("0.0.0.0", port))
                            .addService(greetingService)
                            .sslContext(tlsConfigHelper.buildServerSslContext())
                            .intercept(createLoggingInterceptor())
                            .keepAliveTime(30, TimeUnit.SECONDS)
                            .keepAliveTimeout(10, TimeUnit.SECONDS)
                            .permitKeepAliveWithoutCalls(true);

                    logger.info("Successfully configured TLS for gRPC server on port {}", port);
                } catch (Exception e) {
                    logger.error("Failed to configure TLS: {}", e.getMessage(), e);
                    logger.warn("Falling back to plaintext mode due to TLS configuration failure");
                    // If TLS configuration fails, fallback to plaintext mode and update status
                    tlsEnabled = false;
                    serverBuilder = ServerBuilder.forPort(port)
                            .addService(greetingService)
                            .intercept(createLoggingInterceptor())
                            .keepAliveTime(30, TimeUnit.SECONDS)
                            .keepAliveTimeout(10, TimeUnit.SECONDS)
                            .permitKeepAliveWithoutCalls(true);
                }
            } else {
                // Plaintext mode
                logger.info("Configuring gRPC server in plaintext mode on port {}", port);
                serverBuilder = ServerBuilder.forPort(port)
                        .addService(greetingService)
                        .intercept(createLoggingInterceptor())
                        .keepAliveTime(30, TimeUnit.SECONDS)
                        .keepAliveTimeout(10, TimeUnit.SECONDS)
                        .permitKeepAliveWithoutCalls(true);
            }

            server = serverBuilder.build();
            server.start();

            logger.info("gRPC Server started successfully on port {} with TLS {}",
                    port, tlsEnabled ? "enabled" : "disabled");

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down gRPC server");
                try {
                    GrpcServer.this.stop();
                } catch (InterruptedException e) {
                    logger.error("Error during server shutdown", e);
                }
            }));
        }

        private ServerInterceptor createLoggingInterceptor() {
            return new ServerInterceptor() {
                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                    String methodName = call.getMethodDescriptor().getFullMethodName();
                    String peerAddress = String.valueOf(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));

                    logger.info("Received request: method={}, peer={}", methodName, peerAddress);

                    // 打印所有頭部信息
                    for (String key : headers.keys()) {
                        if (!key.endsWith("-bin")) {
                            logger.debug("Header: {} = {}", key,
                                    headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)));
                        }
                    }

                    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
                            next.startCall(call, headers)) {
                        @Override
                        public void onMessage(ReqT message) {
                            logger.debug("Received message: {}", message);
                            super.onMessage(message);
                        }

                        @Override
                        public void onCancel() {
                            logger.info("Client cancelled request: method={}, peer={}", methodName, peerAddress);
                            super.onCancel();
                        }

                        @Override
                        public void onComplete() {
                            logger.info("Request completed: method={}, peer={}", methodName, peerAddress);
                            super.onComplete();
                        }
                    };
                }
            };
        }

        private void stop() throws InterruptedException {
            if (server != null) {
                // Graceful shutdown
                server.shutdown();
                boolean terminated = server.awaitTermination(30, TimeUnit.SECONDS);
                if (!terminated) {
                    logger.warn("Server did not terminate in the specified time. Forcing shutdown.");
                    server.shutdownNow();
                }
                logger.info("gRPC server shut down successfully");
            }
        }

        public void blockUntilShutdown() throws InterruptedException {
            if (server != null) {
                server.awaitTermination();
            }
        }
    }
}