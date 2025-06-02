package com.grpcsample.service;

import com.grpcsample.grpc.GreetingServiceGrpc;
import com.grpcsample.grpc.HelloReply;
import com.grpcsample.grpc.HelloRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GreetingService extends GreetingServiceGrpc.GreetingServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(GreetingService.class);

    @Override
    public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        try {
            logger.info("收到 sayHello 請求，名稱: {}", request.getName());

            // 建立回應
            HelloReply reply = HelloReply.newBuilder()
                    .setMessage("你好，" + request.getName() + "! - 後端服務 - A")
                    .build();

            logger.info("發送回應: {}", reply.getMessage());

            // 發送回應
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
            logger.info("請求完成: sayHello");

        } catch (Exception e) {
            logger.error("處理請求時發生錯誤", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void sayHellosServerStream(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        try {
            logger.info("收到 sayHellosServerStream 請求，名稱: {}", request.getName());

            // 發送多個回應
            for (int i = 0; i < 5; i++) {
                try {
                    HelloReply reply = HelloReply.newBuilder()
                            .setMessage("串流回應 #" + i + " 給 " + request.getName())
                            .build();

                    logger.info("發送串流回應 #{}: {}", i, reply.getMessage());
                    responseObserver.onNext(reply);

                    Thread.sleep(200); // 模擬處理延遲
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("串流處理期間被中斷", e);
                    break;
                } catch (Exception e) {
                    logger.warn("發送串流回應時發生錯誤", e);
                    break;
                }
            }

            // 檢查線程是否被中斷
            if (!Thread.currentThread().isInterrupted()) {
                responseObserver.onCompleted();
                logger.info("請求完成: sayHellosServerStream");
            }

        } catch (Exception e) {
            logger.error("處理請求時發生錯誤", e);
            try {
                responseObserver.onError(e);
            } catch (Exception ex) {
                logger.warn("發送錯誤給客戶端時出現異常", ex);
            }
        }
    }

    @Override
    public StreamObserver<HelloRequest> sayHellosClientStream(StreamObserver<HelloReply> responseObserver) {
        logger.info("開始 sayHellosClientStream 呼叫");

        return new StreamObserver<HelloRequest>() {
            private final StringBuilder messageCollector = new StringBuilder();
            private int messageCount = 0;
            private final Object lock = new Object();
            private boolean completed = false;
            private boolean errored = false;

            @Override
            public void onNext(HelloRequest request) {
                synchronized (lock) {
                    if (completed || errored) {
                        logger.info("忽略消息，流已完成或發生錯誤");
                        return;
                    }

                    try {
                        logger.info("收到客戶端串流訊息 #{}: {}", messageCount, request.getName());
                        // 限制收集的訊息長度，避免記憶體無限增長
                        if (messageCollector.length() < 8192) { // 限制 8KB
                            messageCollector.append("[").append(messageCount++).append(": ").append(request.getName()).append("] ");
                        } else {
                            // 只增加計數但不添加內容
                            messageCount++;
                            logger.warn("訊息收集器已達到大小限制，只記錄計數");
                        }
                    } catch (Exception e) {
                        logger.error("處理消息時出錯", e);
                        errored = true;
                        responseObserver.onError(
                                Status.INTERNAL.withDescription("處理訊息時發生錯誤: " + e.getMessage()).asException()
                        );
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                synchronized (lock) {
                    if (errored || completed) {
                        return;
                    }

                    errored = true;

                    if (t instanceof StatusRuntimeException &&
                            ((StatusRuntimeException)t).getStatus().getCode() == Status.Code.CANCELLED) {
                        logger.info("客戶端取消了串流，已處理 {} 個消息", messageCount);
                    } else {
                        logger.error("客戶端串流錯誤", t);
                    }

                    // 如果已經處理了一些消息，可以選擇性地發送部分結果
                    if (messageCount > 0) {
                        try {
                            logger.info("客戶端取消但已處理部分消息，返回部分結果");
                            HelloReply reply = HelloReply.newBuilder()
                                    .setMessage("已處理 " + messageCount + " 個消息 (客戶端已取消): " +
                                            messageCollector.toString())
                                    .build();
                            responseObserver.onNext(reply);
                            responseObserver.onCompleted();
                        } catch (Exception e) {
                            logger.warn("在客戶端取消後嘗試發送響應時出錯", e);
                            // 確保流關閉
                            try {
                                responseObserver.onCompleted();
                            } catch (Exception ex) {
                                // 忽略最終的完成異常
                            }
                        }
                    } else {
                        // 確保流在錯誤時關閉
                        try {
                            responseObserver.onCompleted();
                        } catch (Exception ex) {
                            // 忽略最終的完成異常
                        }
                    }
                }
            }

            @Override
            public void onCompleted() {
                synchronized (lock) {
                    if (completed || errored) {
                        logger.info("忽略 onCompleted，流已完成或發生錯誤");
                        return;
                    }

                    completed = true;
                    logger.info("客戶端完成串流傳送，共接收 {} 個訊息", messageCount);

                    try {
                        // 檢查收集器大小，避免返回過大的響應
                        String collectedMessages = messageCollector.length() > 1024 ?
                                messageCollector.substring(0, 1024) + "... [訊息過長，已截斷]" :
                                messageCollector.toString();

                        HelloReply reply = HelloReply.newBuilder()
                                .setMessage("已收到 " + messageCount + " 個訊息: " + collectedMessages)
                                .build();

                        logger.info("發送匯總回應: {}", reply.getMessage());
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                        logger.info("請求完成: sayHellosClientStream");
                    } catch (Exception e) {
                        logger.error("發送響應時出錯", e);
                        try {
                            responseObserver.onError(
                                    Status.INTERNAL.withDescription("發送響應時出錯: " + e.getMessage()).asException()
                            );
                        } catch (Exception ex) {
                            // 忽略最終的錯誤異常
                        }
                    }
                }
            }
        };
    }

    @Override
    public StreamObserver<HelloRequest> sayHellosBidirectional(StreamObserver<HelloReply> responseObserver) {
        logger.info("開始 sayHellosBidirectional 呼叫");

        return new StreamObserver<HelloRequest>() {
            private int messageCount = 0;
            private final Object lock = new Object();
            private boolean completed = false;
            private boolean errored = false;

            @Override
            public void onNext(HelloRequest request) {
                synchronized (lock) {
                    if (completed || errored) {
                        logger.info("串流已完成或出錯，忽略新的消息");
                        return;
                    }

                    logger.info("收到雙向串流請求 #{}: {}", messageCount, request.getName());

                    try {
                        HelloReply reply = HelloReply.newBuilder()
                                .setMessage("雙向串流回應 #" + messageCount + " 給 " + request.getName())
                                .build();

                        logger.info("發送雙向串流回應 #{}: {}", messageCount, reply.getMessage());
                        responseObserver.onNext(reply);
                        messageCount++;
                    } catch (Exception e) {
                        logger.warn("在處理訊息時發生錯誤: {}", e.getMessage());
                        errored = true;
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                synchronized (lock) {
                    if (completed || errored) {
                        return;
                    }

                    errored = true;

                    if (t instanceof StatusRuntimeException &&
                            ((StatusRuntimeException)t).getStatus().getCode() == Status.Code.CANCELLED) {
                        logger.info("客戶端取消了雙向串流，處理了 {} 個訊息", messageCount);
                    } else {
                        logger.error("雙向串流錯誤", t);
                    }
                }
            }

            @Override
            public void onCompleted() {
                synchronized (lock) {
                    if (completed || errored) {
                        return;
                    }

                    completed = true;
                    logger.info("雙向串流完成，處理了 {} 個訊息", messageCount);
                    try {
                        responseObserver.onCompleted();
                        logger.info("請求完成: sayHellosBidirectional");
                    } catch (Exception e) {
                        logger.warn("完成請求時發生錯誤", e);
                    }
                }
            }
        };
    }
}