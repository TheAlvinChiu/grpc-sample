package com.grpcsample.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用 keytool 命令生成證書 (不依賴於任何第三方庫)
 */
public class KeytoolCertificateGenerator {
    private static final Logger logger = LoggerFactory.getLogger(KeytoolCertificateGenerator.class);
    private static final String KEYSTORE_PASSWORD = "changeit";

    public static void main(String[] args) {
        try {
            KeytoolCertificateGenerator generator = new KeytoolCertificateGenerator();
            generator.generateServerCertificate();
            logger.info("Certificates generated successfully using keytool");
        } catch (Exception e) {
            logger.error("Failed to generate certificates", e);
            e.printStackTrace();
        }
    }

    /**
     * 生成伺服器證書
     */
    public void generateServerCertificate() throws Exception {
        // 確保目錄存在
        createKeystoreDirectory();

        // 生成伺服器密鑰和自簽名證書
        String serverKeystorePath = "src/main/resources/keystore/grpc-server.p12";

        // 刪除現有的 keystore (如果存在)
        Files.deleteIfExists(Paths.get(serverKeystorePath));

        // 生成新的 keystore 和證書
        // 重要：使用正確的 X.509v3 擴展支持 TLS 伺服器身份驗證
        String[] genKeyCmd = {
                "keytool", "-genkeypair",
                "-alias", "grpcServer",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650", // 有效期10年
                "-keystore", serverKeystorePath,
                "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-keypass", KEYSTORE_PASSWORD,
                "-dname", "CN=localhost,OU=Test,O=Grpc Sample,L=City,ST=State,C=TW",
                "-ext", "KeyUsage=digitalSignature,keyEncipherment",
                "-ext", "ExtendedKeyUsage=serverAuth,clientAuth",
                "-ext", "SAN=dns:localhost,dns:*.localhost,ip:127.0.0.1"
        };

        executeCommand(genKeyCmd);
        logger.info("Server keystore created at: {}", serverKeystorePath);

        // 導出伺服器證書
        String serverCertPath = "src/main/resources/keystore/grpc-server-cert.pem";
        String[] exportCertCmd = {
                "keytool", "-exportcert",
                "-alias", "grpcServer",
                "-keystore", serverKeystorePath,
                "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-rfc",
                "-file", serverCertPath
        };

        executeCommand(exportCertCmd);
        logger.info("Server certificate exported to: {}", serverCertPath);

        // 顯示證書詳細信息
        String[] showCertCmd = {
                "keytool", "-list",
                "-v",
                "-alias", "grpcServer",
                "-keystore", serverKeystorePath,
                "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD
        };

        Process process = Runtime.getRuntime().exec(showCertCmd);
        String output = new String(process.getInputStream().readAllBytes());
        logger.info("Certificate details:\n{}", output);
    }

    private void createKeystoreDirectory() {
        File keystoreDir = new File("src/main/resources/keystore");
        if (!keystoreDir.exists()) {
            keystoreDir.mkdirs();
            logger.info("Created keystore directory: {}", keystoreDir.getAbsolutePath());
        }
    }

    private void executeCommand(String[] command) throws Exception {
        logger.info("Executing command: {}", String.join(" ", command));
        Process process = Runtime.getRuntime().exec(command);
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorOutput = new String(process.getErrorStream().readAllBytes());
            logger.error("Command failed with exit code {}: {}", exitCode, errorOutput);
            throw new Exception("Failed to execute command: " + String.join(" ", command));
        }

        // 打印標準輸出
        String output = new String(process.getInputStream().readAllBytes());
        if (!output.isEmpty()) {
            logger.info("Command output: {}", output);
        }
    }
}