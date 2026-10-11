package com.example.rag.chat;

import com.example.rag.storage.ChunkResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Streams answers token by token from an Amazon Bedrock chat model to the browser over Server-Sent Events.
 * It builds the same numbered-source, citation-only prompt as the blocking chat service but uses the async Bedrock client.
 * Each text delta is forwarded as a JSON-wrapped "delta" event, and the emitter is completed or failed when Bedrock finishes.
 */
@Service
public class StreamingChatService {

    private final BedrockRuntimeAsyncClient asyncClient;

    @Value("${aws.bedrock.chat-model-id}")
    private String modelId;

    /**
     * Creates the service with an asynchronous Bedrock client so request threads are not blocked while text streams.
     *
     * @param region the AWS region hosting the Bedrock model
     */
    public StreamingChatService(@Value("${aws.region}") String region) {
        this.asyncClient = BedrockRuntimeAsyncClient.builder().region(Region.of(region)).build();
    }

    /**
     * Starts a streaming answer and returns immediately, pushing text to the emitter as Bedrock generates it.
     * <p>
     * Deltas are JSON-wrapped because SSE framing strips leading and trailing whitespace and newlines.
     * If a send fails (for example, the client disconnected) or Bedrock reports an error, the emitter
     * is completed with that error.
     *
     * @param question the user's question
     * @param chunks   the retrieved chunks, numbered from 1 in the prompt
     * @param emitter  the open SSE connection to write "delta" events to
     */
    public void streamAnswer(String question, List<ChunkResult> chunks, SseEmitter emitter) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            context.append("[%d] %s\n\n".formatted(i + 1, chunks.get(i).content()));
        }

        String prompt = """
                Answer the question using only the numbered sources below. \
                Cite sources inline using their bracket number. \
                If the sources don't contain the answer, say you don't know.

                Sources:
                %s
                Question: %s
                """.formatted(context, question);

        Message message = Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(prompt))
                .build();

        ConverseStreamRequest request = ConverseStreamRequest.builder()
                .modelId(modelId)
                .messages(message)
                .build();

        ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                        .onContentBlockDelta(delta -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("delta")
                                        .data(Map.of("text", delta.delta().text()), MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .build())
                .onComplete(emitter::complete)
                .onError(emitter::completeWithError)
                .build();

        asyncClient.converseStream(request, handler);
    }
}
