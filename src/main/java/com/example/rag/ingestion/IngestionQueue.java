package com.example.rag.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.UUID;

/**
 * Service component responsible for dispatching document identifiers to an AWS SQS queue.
 * This facilitates asynchronous downstream processing for the RAG ingestion pipeline.
 */
@Component
public class IngestionQueue {

    private final SqsClient sqsClient;
    private final String queueUrl;

    /**
     * Constructs the IngestionQueue and initializes the AWS SQS client.
     * 
     * @param region   The AWS deployment region (e.g., "us-east-1") injected from application properties.
     * @param queueUrl The destination SQS queue URL injected from application properties.
     */
    public IngestionQueue(@Value("${aws.region}") String region,
                           @Value("${aws.sqs.ingestion-queue-url}") String queueUrl) {
        // Build the synchronous SQS client scoped to the specified AWS region
        this.sqsClient = SqsClient.builder().region(Region.of(region)).build();
        this.queueUrl = queueUrl;
    }

    /**
     * Publishes a document ID to the configured SQS queue.
     * 
     * @param documentId The unique identifier of the document awaiting ingestion.
     */
    public void enqueue(UUID documentId) {
        // Construct the message payload with the UUID string and send it to the queue
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(documentId.toString())
                .build());
    }
}