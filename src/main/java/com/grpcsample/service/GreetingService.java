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
            logger.info("Received sayHello request, name: {}", request.getName());

            // Build response
            HelloReply reply = HelloReply.newBuilder()
                    .setMessage("Hello, " + request.getName() + "! - Backend Service - A")
                    .build();

            logger.info("Sending response: {}", reply.getMessage());

            // Send response
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
            logger.info("Request completed: sayHello");

        } catch (Exception e) {
            logger.error("Error occurred while processing request", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void sayHellosServerStream(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        try {
            logger.info("Received sayHellosServerStream request, name: {}", request.getName());

            // Send multiple responses
            for (int i = 0; i < 5; i++) {
                try {
                    HelloReply reply = HelloReply.newBuilder()
                            .setMessage("Stream response #" + i + " for " + request.getName())
                            .build();

                    logger.info("Sending stream response #{}: {}", i, reply.getMessage());
                    responseObserver.onNext(reply);

                    Thread.sleep(200); // Simulate processing delay
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted during stream processing", e);
                    break;
                } catch (Exception e) {
                    logger.warn("Error occurred while sending stream response", e);
                    break;
                }
            }

            // Check if thread was interrupted
            if (!Thread.currentThread().isInterrupted()) {
                responseObserver.onCompleted();
                logger.info("Request completed: sayHellosServerStream");
            }

        } catch (Exception e) {
            logger.error("Error occurred while processing request", e);
            try {
                responseObserver.onError(e);
            } catch (Exception ex) {
                logger.warn("Exception occurred while sending error to client", ex);
            }
        }
    }

    @Override
    public StreamObserver<HelloRequest> sayHellosClientStream(StreamObserver<HelloReply> responseObserver) {
        logger.info("Starting sayHellosClientStream call");

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
                        logger.info("Ignoring message, stream completed or errored");
                        return;
                    }

                    try {
                        logger.info("Received client stream message #{}: {}", messageCount, request.getName());
                        // Limit collected message length to avoid unlimited memory growth
                        if (messageCollector.length() < 8192) { // Limit to 8KB
                            messageCollector.append("[").append(messageCount++).append(": ").append(request.getName()).append("] ");
                        } else {
                            // Only increment count but don't add content
                            messageCount++;
                            logger.warn("Message collector reached size limit, only recording count");
                        }
                    } catch (Exception e) {
                        logger.error("Error processing message", e);
                        errored = true;
                        responseObserver.onError(
                                Status.INTERNAL.withDescription("Error processing message: " + e.getMessage()).asException()
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
                        logger.info("Client cancelled stream, processed {} messages", messageCount);
                    } else {
                        logger.error("Client stream error", t);
                    }

                    // If some messages were processed, optionally send partial results
                    if (messageCount > 0) {
                        try {
                            logger.info("Client cancelled but partial messages processed, returning partial results");
                            HelloReply reply = HelloReply.newBuilder()
                                    .setMessage("Processed " + messageCount + " messages (client cancelled): " +
                                            messageCollector.toString())
                                    .build();
                            responseObserver.onNext(reply);
                            responseObserver.onCompleted();
                        } catch (Exception e) {
                            logger.warn("Error trying to send response after client cancellation", e);
                            // Ensure stream is closed
                            try {
                                responseObserver.onCompleted();
                            } catch (Exception ex) {
                                // Ignore final completion exception
                            }
                        }
                    } else {
                        // Ensure stream is closed on error
                        try {
                            responseObserver.onCompleted();
                        } catch (Exception ex) {
                            // Ignore final completion exception
                        }
                    }
                }
            }

            @Override
            public void onCompleted() {
                synchronized (lock) {
                    if (completed || errored) {
                        logger.info("Ignoring onCompleted, stream already completed or errored");
                        return;
                    }

                    completed = true;
                    logger.info("Client completed stream transmission, received {} messages total", messageCount);

                    try {
                        // Check collector size to avoid returning oversized response
                        String collectedMessages = messageCollector.length() > 1024 ?
                                messageCollector.substring(0, 1024) + "... [message too long, truncated]" :
                                messageCollector.toString();

                        HelloReply reply = HelloReply.newBuilder()
                                .setMessage("Received " + messageCount + " messages: " + collectedMessages)
                                .build();

                        logger.info("Sending summary response: {}", reply.getMessage());
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                        logger.info("Request completed: sayHellosClientStream");
                    } catch (Exception e) {
                        logger.error("Error sending response", e);
                        try {
                            responseObserver.onError(
                                    Status.INTERNAL.withDescription("Error sending response: " + e.getMessage()).asException()
                            );
                        } catch (Exception ex) {
                            // Ignore final error exception
                        }
                    }
                }
            }
        };
    }

    @Override
    public StreamObserver<HelloRequest> sayHellosBidirectional(StreamObserver<HelloReply> responseObserver) {
        logger.info("Starting sayHellosBidirectional call");

        return new StreamObserver<HelloRequest>() {
            private int messageCount = 0;
            private final Object lock = new Object();
            private boolean completed = false;
            private boolean errored = false;

            @Override
            public void onNext(HelloRequest request) {
                synchronized (lock) {
                    if (completed || errored) {
                        logger.info("Stream completed or errored, ignoring new message");
                        return;
                    }

                    logger.info("Received bidirectional stream request #{}: {}", messageCount, request.getName());

                    try {
                        HelloReply reply = HelloReply.newBuilder()
                                .setMessage("Bidirectional stream response #" + messageCount + " for " + request.getName())
                                .build();

                        logger.info("Sending bidirectional stream response #{}: {}", messageCount, reply.getMessage());
                        responseObserver.onNext(reply);
                        messageCount++;
                    } catch (Exception e) {
                        logger.warn("Error occurred while processing message: {}", e.getMessage());
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
                        logger.info("Client cancelled bidirectional stream, processed {} messages", messageCount);
                    } else {
                        logger.error("Bidirectional stream error", t);
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
                    logger.info("Bidirectional stream completed, processed {} messages", messageCount);
                    try {
                        responseObserver.onCompleted();
                        logger.info("Request completed: sayHellosBidirectional");
                    } catch (Exception e) {
                        logger.warn("Error occurred while completing request", e);
                    }
                }
            }
        };
    }
}