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

@Service //spring creates automatically
/**
 * Generates normalized 1024-dimensional text embeddings with Amazon Bedrock
 * and the configured Titan embedding model.
 */
public class EmbeddingService {

    private final BedrockRuntimeClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${aws.bedrock.embedding-model-id}")
    private String modelId;

    public EmbeddingService(@Value("${aws.region}") String region) {
        this.client = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .build();
    }

    // Sends the text to Bedrock and converts the returned embedding JSON to a float array.
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
//Spring creates it automatically because it has @Service.
//Reads the AWS region and embedding model ID from application.yml.
//Builds an AWS Bedrock Runtime client.
//Sends the text to Titan Embeddings V2 with:
//1024 dimensions
//normalized output
//Reads the returned JSON embedding.
//Converts it into a Java float[].