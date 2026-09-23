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

@Service
public class StreamingChatService {

    // Notice we are using the AsyncClient now, not the standard client.
    // This allows our Spring server to keep processing other things while 
    // it waits for the stream of text to arrive from AWS.
    private final BedrockRuntimeAsyncClient asyncClient;

    @Value("${aws.bedrock.chat-model-id}")
    private String modelId;

    public StreamingChatService(@Value("${aws.region}") String region) {
        this.asyncClient = BedrockRuntimeAsyncClient.builder().region(Region.of(region)).build();
    }

    // SseEmitter (Server-Sent Events) is an open pipeline to the user's web browser.
    // We don't return a String here; instead, we push data down this emitter tube.
    public void streamAnswer(String question, List<ChunkResult> chunks, SseEmitter emitter) {
        
        // 1. BUILD THE "BIBLIOGRAPHY"
        // Just like the standard service, we number our retrieved documents 
        // so the AI can cite them using [1], [2], etc.
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            context.append("[%d] %s\n\n".formatted(i + 1, chunks.get(i).content()));
        }

        // 2. WRITE THE STRICT PROMPT
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

        // Notice this is a ConverseStreamRequest, signaling to AWS that 
        // we want the response chunked out as it generates.
        ConverseStreamRequest request = ConverseStreamRequest.builder()
                .modelId(modelId)
                .messages(message)
                .build();

        // 3. SET UP THE STREAMING HANDLER (The "Catch and Throw" mechanism)
        // This handler listens to the open connection with AWS Bedrock.
        ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                        
                        // Every time AWS generates a few new characters (a "delta"), this block triggers.
                        .onContentBlockDelta(delta -> {
                            try {
                                // We instantly push those new characters down the SseEmitter 
                                // to the user's browser, creating the "typing" effect.
                                // JSON-wrapped so leading/trailing spaces and newlines in
                                // the delta survive SSE framing, which strips them.
                                emitter.send(SseEmitter.event()
                                        .name("delta")
                                        .data(Map.of("text", delta.delta().text()), MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                // If the user closed their browser or lost internet, 
                                // we gracefully close the connection.
                                emitter.completeWithError(e);
                            }
                        })
                        .build())
                
                // When AWS says "I'm done generating", we tell the browser "Stream finished!"
                .onComplete(emitter::complete)
                
                // If AWS throws an error (e.g., rate limit, model overload), pass it to the client.
                .onError(emitter::completeWithError)
                .build();

        // 4. FIRE THE REQUEST
        // This method executes immediately in the background, relying on the handler 
        // above to deal with the incoming traffic.
        asyncClient.converseStream(request, handler);
    }
}