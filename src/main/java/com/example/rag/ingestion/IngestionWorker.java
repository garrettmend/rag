package com.example.rag.ingestion;

import com.example.rag.embedding.EmbeddingService;
import com.example.rag.storage.WorkerVectorStoreRepository;
import com.example.rag.util.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Long-polls the ingestion queue and processes each document: chunks it, embeds every
 * chunk, and moves its status PENDING -> PROCESSING -> READY (or FAILED on error).
 * On failure the message isn't deleted, so SQS redelivers it after the visibility
 * timeout; retries are safe only because chunk insertion is idempotent.
 */
@Component
public class IngestionWorker {

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final WorkerVectorStoreRepository repository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;

    public IngestionWorker(@Value("${aws.region}") String region,
                            @Value("${aws.sqs.ingestion-queue-url}") String queueUrl,
                            WorkerVectorStoreRepository repository,
                            ChunkingService chunkingService,
                            EmbeddingService embeddingService) {
        this.sqsClient = SqsClient.builder().region(Region.of(region)).build();
        this.queueUrl = queueUrl;
        this.repository = repository;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()//api call
                .queueUrl(queueUrl)
                .maxNumberOfMessages(5)
                .waitTimeSeconds(10) // long polling
                .build());

        for (Message message : response.messages()) {
            processMessage(message);
        }
    }

    private void processMessage(Message message) {
        UUID documentId = UUID.fromString(message.body());
        try {
            var doc = repository.getDocumentForProcessing(documentId);
            repository.updateDocumentStatus(documentId, "PROCESSING");

            List<String> chunks = chunkingService.chunk(doc.rawText());
            for (String chunk : chunks) {
                String chunkHash = Hashing.sha256(chunk);
                float[] embedding = embeddingService.embed(chunk);
                repository.insertChunkIfAbsent(doc.tenantId(), documentId, chunk, chunkHash, embedding);
            }

            repository.updateDocumentStatus(documentId, "READY");

            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        } catch (Exception e) {
            // Message is left on the queue on purpose. It reappears after the
            // visibility timeout and gets retried — which is safe only because
            // insertChunkIfAbsent is idempotent.
            repository.updateDocumentStatus(documentId, "FAILED");
        }
    }
}
