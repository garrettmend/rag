package com.example.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates text embeddings with the configured Amazon Bedrock Titan embedding model.
 * Every request asks for 1024-dimensional, normalized vectors so they match the pgvector column and cosine search.
 * The returned JSON embedding is converted into a Java float array for storage and querying.
 */
@Service
public class EmbeddingService {

    private final BedrockRuntimeClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${aws.bedrock.embedding-model-id}")
    private String modelId;

    /**
     * Creates the service with a synchronous Bedrock client.
     *
     * @param region the AWS region hosting the embedding model
     */
    public EmbeddingService(@Value("${aws.region}") String region) {
        this.client = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Embeds the given text as a normalized 1024-dimensional vector.
     *
     * @param text the text to embed
     * @return the embedding values
     * @throws RuntimeException if the Bedrock call or response parsing fails
     */
    public float[] embed(String text) {
        try {
            var body = mapper.createObjectNode();
            body.put("inputText", text);
            body.put("dimensions", 1024);
            body.put("normalize", true);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(body)))
                    .build();

            InvokeModelResponse response = client.invokeModel(request);
            JsonNode responseBody = mapper.readTree(response.body().asUtf8String());

            List<Float> values = new ArrayList<>();
            responseBody.get("embedding").forEach(n -> values.add((float) n.asDouble()));

            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }
}
