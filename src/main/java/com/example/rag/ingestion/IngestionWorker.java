package com.example.rag.ingestion;

import com.example.rag.embedding.EmbeddingService;
import com.example.rag.storage.WorkerVectorStoreRepository;
import com.example.rag.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Background worker that long-polls the ingestion queue and processes each queued document.
 * It chunks the document, embeds every chunk, and moves its status PENDING to PROCESSING to READY, or to FAILED on error.
 * Failed messages are left on the queue for SQS to redeliver after the visibility timeout, which is safe because chunk insertion is idempotent.
 */
@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final WorkerVectorStoreRepository repository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;

    /**
     * Creates the worker with an SQS client for the given region.
     *
     * @param region           the AWS region
     * @param queueUrl         the ingestion queue URL
     * @param repository       cross-tenant storage for documents and chunks
     * @param chunkingService  splits document text into chunks
     * @param embeddingService embeds each chunk
     */
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

    /**
     * Long-polls for up to five messages (waiting up to 10 seconds) and processes each one.
     * Runs again one second after the previous poll finishes.
     */
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(5)
                .waitTimeSeconds(10)
                .build());

        for (Message message : response.messages()) {
            processMessage(message);
        }
    }

    /**
     * Chunks, embeds, and stores one document, then deletes its message on success.
     * <p>
     * On failure the document is marked FAILED and the message is deliberately left on the queue,
     * so it reappears after the visibility timeout and is retried; this is safe only because
     * {@link WorkerVectorStoreRepository#insertChunkIfAbsent} is idempotent.
     *
     * @param message the SQS message whose body is the document ID
     */
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
            log.error("Failed to ingest document {}", documentId, e);
            repository.updateDocumentStatus(documentId, "FAILED");
        }
    }
}
