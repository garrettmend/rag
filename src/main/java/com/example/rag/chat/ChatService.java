package com.example.rag.chat;

import com.example.rag.storage.ChunkResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;

@Service
public class ChatService {

    private final BedrockRuntimeClient client;

    @Value("${aws.bedrock.chat-model-id}")
    private String modelId;

    public ChatService(@Value("${aws.region}") String region) {
        this.client = BedrockRuntimeClient.builder().region(Region.of(region)).build();
    }

    public record AnsweredQuery(String answer, List<ChunkResult> citations) {}

    public AnsweredQuery answer(String question, List<ChunkResult> chunks) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            context.append("[%d] %s\n\n".formatted(i + 1, chunks.get(i).content()));
        }

        String prompt = """
                Answer the question using only the numbered sources below. \
                Cite sources inline using their bracket number, like [1] or [2][3]. \
                If the sources don't contain the answer, say you don't know.

                Sources:
                %s
                Question: %s
                """.formatted(context, question);

        Message message = Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(prompt))
                .build();

        ConverseResponse response = client.converse(ConverseRequest.builder()
                .modelId(modelId)
                .messages(message)
                .build());

        String answerText = response.output().message().content().get(0).text();
        return new AnsweredQuery(answerText, chunks);
    }
}