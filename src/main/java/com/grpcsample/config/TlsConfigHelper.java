package com.grpcsample.config;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNames;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;

@Component
public class TlsConfigHelper {
    private static final Logger logger = LoggerFactory.getLogger(TlsConfigHelper.class);

    @Value("${grpc.server.tls.keystore-path}")
    private Resource keystoreResource;

    @Value("${grpc.server.tls.keystore-password}")
    private String keystorePassword;

    @Value("${grpc.server.tls.keystore-type}")
    private String keystoreType;

    @Value("${grpc.server.tls.key-alias}")
    private String keyAlias;

    public SslContext buildServerSslContext() {
        try {
            logger.info("Building TLS context for gRPC server");

            // 加載 KeyStore
            KeyStore keyStore = loadKeyStore();
            if (keyStore == null) {
                throw new IllegalStateException("Failed to load keystore");
            }

            // 檢查 KeyStore 內容
            if (!keyStore.containsAlias(keyAlias)) {
                throw new IllegalStateException("KeyStore does not contain alias: " + keyAlias);
            }

            // 獲取私鑰
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());

            // 構建 SSL 上下文 - 顯式配置 ALPN
            SslContextBuilder sslBuilder = SslContextBuilder.forServer(kmf);

            // 使用 gRPC 特定的 SSL 上下文構建器
            SslContext sslContext = GrpcSslContexts.configure(sslBuilder)
                    // 重要 - 確保顯式支持 HTTP/2
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            // 確保 HTTP/2 優先於 HTTP/1.1
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1
                    ))
                    .build();

            logger.info("Successfully built TLS context for gRPC server");
            return sslContext;

        } catch (Exception e) {
            logger.error("Failed to build TLS context: {}", e.getMessage(), e);
            throw new RuntimeException("Could not build TLS context", e);
        }
    }

    private KeyStore loadKeyStore() {
        try {
            logger.info("Loading keystore from: {}", keystoreResource);

            if (!keystoreResource.exists()) {
                logger.error("Keystore resource does not exist: {}", keystoreResource);
                return null;
            }

            KeyStore keyStore = KeyStore.getInstance(keystoreType);
            try (InputStream is = keystoreResource.getInputStream()) {
                keyStore.load(is, keystorePassword.toCharArray());
                logger.info("Successfully loaded keystore with type: {}", keystoreType);
                return keyStore;
            }
        } catch (Exception e) {
            logger.error("Failed to load keystore: {}", e.getMessage(), e);
            return null;
        }
    }
}