package com.example.rag.query;

import com.example.rag.chat.ChatService;
import com.example.rag.chat.StreamingChatService;
import com.example.rag.embedding.EmbeddingService;
import com.example.rag.storage.ChunkResult;
import com.example.rag.tenant.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller responsible for processing user questions, executing hybrid searches,
 * and dispatching requests to blocking or streaming AI generation services.
 */
@RestController
@RequestMapping("/query")
public class QueryController {

    private final EmbeddingService embeddingService;
    private final HybridSearchService hybridSearchService;
    private final ChatService chatService;
    private final StreamingChatService streamingChatService;

    public QueryController(EmbeddingService embeddingService,
                            HybridSearchService hybridSearchService,
                            ChatService chatService,
                            StreamingChatService streamingChatService) {
        this.embeddingService = embeddingService;
        this.hybridSearchService = hybridSearchService;
        this.chatService = chatService;
        this.streamingChatService = streamingChatService;
    }

    /** Simple data payload object for incoming JSON queries. */
    public record QueryRequest(String question) {}

    /**
     * Standard blocking query endpoint.
     * Endpoint: POST /query
     */
    @PostMapping
    public ChatService.AnsweredQuery query(@RequestBody QueryRequest request) {
        // 1. ISOLATE BY TENANT
        UUID tenantId = TenantContext.get();
        
        // 2. EMBED THE QUESTION
        // Convert the user's natural language question into a high-dimensional vector array.
        float[] queryEmbedding = embeddingService.embed(request.question());
        
        // 3. RETRIEVE RELEVANT CONTEXT (HYBRID SEARCH)
        // Combine vector similarity and full-text keyword search to pull the top 5 relevant document chunks.
        List<ChunkResult> chunks = hybridSearchService.search(tenantId, queryEmbedding, request.question(), 5);
        
        // 4. GENERATE & RETURN ANSWER
        // Send the question and retrieved chunks to the LLM and return the complete AnsweredQuery package.
        return chatService.answer(request.question(), chunks);
    }

    /**
     * Real-time streaming query endpoint using Server-Sent Events (SSE).
     * Endpoint: GET /query/stream?question=...
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuery(@RequestParam String question) throws IOException {
        // 1. ISOLATE BY TENANT
        UUID tenantId = TenantContext.get();
        
        // 2. INITIALIZE SSE EMITTER
        // Open a persistent connection to the client browser with a 60-second timeout.
        SseEmitter emitter = new SseEmitter(60_000L);
        
        // 3. EMBED & SEARCH
        float[] queryEmbedding = embeddingService.embed(question);
        List<ChunkResult> chunks = hybridSearchService.search(tenantId, queryEmbedding, question, 5);

        // 4. TRANSMIT CITATIONS UPFRONT
        // Send the source chunks as a dedicated named SSE event ("citations") BEFORE the text streams.
        // This lets the client application map brackets like [1] immediately without waiting for generation to finish.
        emitter.send(SseEmitter.event().name("citations").data(chunks, MediaType.APPLICATION_JSON));

        // 5. STREAM THE AI RESPONSE TOKENS
        // Hand the emitter over to the asynchronous chat service to stream text chunks word-by-word.
        streamingChatService.streamAnswer(question, chunks, emitter);
        
        // Return the open connection handle back to Spring.
        return emitter;
    }
}