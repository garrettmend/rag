package com.example.rag.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.UUID;

/**
 * Publishes newly uploaded document IDs to the ingestion SQS queue.
 * Queuing decouples slow chunking and embedding from the upload request.
 * The ingestion worker consumes these messages and processes each document in the background.
 */
@Component
public class IngestionQueue {

    private final SqsClient sqsClient;
    private final String queueUrl;

    /**
     * Creates the queue publisher with an SQS client for the given region.
     *
     * @param region   the AWS region, such as "us-east-1"
     * @param queueUrl the ingestion queue URL
     */
    public IngestionQueue(@Value("${aws.region}") String region,
                           @Value("${aws.sqs.ingestion-queue-url}") String queueUrl) {
        this.sqsClient = SqsClient.builder().region(Region.of(region)).build();
        this.queueUrl = queueUrl;
    }

    /**
     * Sends a document ID to the ingestion queue.
     *
     * @param documentId the document awaiting ingestion
     */
    public void enqueue(UUID documentId) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(documentId.toString())
                .build());
    }
}
