# gRPC Sample

本專案示範如何使用 **Spring Boot** 與 **gRPC** 建立具備 TLS 的服務端與客戶端。專案使用 **Java 21** 以及 Gradle Wrapper (8.11.1) 進行建置，`greeting.proto` 內定義了基本的 gRPC 服務。

## 主要功能
- gRPC 伺服器支援四種 RPC 互動：Unary、Server Streaming、Client Streaming 以及 Bidirectional Streaming。
- 內建 `GrpcClient`、`GrpcTester` 與 `RequestHelper`，可用於測試與產生請求範例。
- 提供 `generateCerts` 任務自動產生測試用 TLS 憑證，`runClient` 任務可以直接啟動範例客戶端。

## 原始碼結構
- `src/main/java` – 伺服器端、客戶端及相關工具程式。
- `src/main/proto` – gRPC 服務定義檔案。
- `src/main/resources` – 設定檔與 keystore 位置。
- `src/test/java` – 測試程式。

### gRPC 服務定義
檔案 `greeting.proto` 定義了以下方法：

```proto
service GreetingService {
  rpc sayHello (HelloRequest) returns (HelloReply) {}
  rpc sayHellosServerStream (HelloRequest) returns (stream HelloReply) {}
  rpc sayHellosClientStream (stream HelloRequest) returns (HelloReply) {}
  rpc sayHellosBidirectional (stream HelloRequest) returns (stream HelloReply) {}
}
```

## 建置與執行
1. **產生憑證（可選）**
   ```bash
   ./gradlew generateCerts
   ```
   此命令會使用 `KeytoolCertificateGenerator` 在 `src/main/resources/keystore` 生成自簽名憑證。

2. **啟動 gRPC Server**
   ```bash
   ./gradlew bootRun
   ```
   伺服器預設監聽在 `50051` 連接埠，可於 `application.properties` 調整設定。

3. **執行範例 Client**
   ```bash
   ./gradlew runClient
   ```
   預設會連線到 localhost:50051 並使用 TLS，可在執行時調整參數。

更多自訂任務與設定可參考 `build.gradle`：
```groovy
// generateCerts 任務
// runClient 任務
// printClasspath 任務
```

## 設定檔摘錄
`application.properties` 範例如下：
```properties
grpc.server.port=50051
grpc.server.tls.enabled=true
grpc.server.tls.keystore-path=classpath:keystore/grpc-server.p12
```

## 測試
執行單元測試：
```bash
./gradlew test
```
若執行時因環境限制無法下載 Gradle 依賴，指令可能失敗並出現類似錯誤：
```
Unable to tunnel through proxy. Proxy returns "HTTP/1.1 403 Forbidden"
```

