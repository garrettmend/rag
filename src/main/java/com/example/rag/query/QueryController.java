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
 * REST controller that answers user questions from the current tenant's documents.
 * Each question is embedded and run through hybrid search to retrieve the five most relevant chunks.
 * The chunks are then passed to either the blocking chat service or the streaming chat service over Server-Sent Events.
 */
@RestController
@RequestMapping("/query")
public class QueryController {

    private final EmbeddingService embeddingService;
    private final HybridSearchService hybridSearchService;
    private final ChatService chatService;
    private final StreamingChatService streamingChatService;

    /**
     * Creates the controller.
     *
     * @param embeddingService     embeds the question
     * @param hybridSearchService  retrieves relevant chunks
     * @param chatService          generates blocking answers
     * @param streamingChatService generates streamed answers
     */
    public QueryController(EmbeddingService embeddingService,
                            HybridSearchService hybridSearchService,
                            ChatService chatService,
                            StreamingChatService streamingChatService) {
        this.embeddingService = embeddingService;
        this.hybridSearchService = hybridSearchService;
        this.chatService = chatService;
        this.streamingChatService = streamingChatService;
    }

    /**
     * JSON body for a blocking query.
     *
     * @param question the user's question
     */
    public record QueryRequest(String question) {}

    /**
     * Answers a question at {@code POST /query} and returns the full answer with its citations.
     *
     * @param request the question
     * @return the answer and the chunks it cites
     */
    @PostMapping
    public ChatService.AnsweredQuery query(@RequestBody QueryRequest request) {
        UUID tenantId = TenantContext.get();
        float[] queryEmbedding = embeddingService.embed(request.question());
        List<ChunkResult> chunks = hybridSearchService.search(tenantId, queryEmbedding, request.question(), 5);
        return chatService.answer(request.question(), chunks);
    }

    /**
     * Streams an answer at {@code GET /query/stream?question=...} over Server-Sent Events with a 60-second timeout.
     * <p>
     * A "citations" event carrying the source chunks is sent before any text, so the client can resolve
     * bracketed references like [1] as soon as they appear; "delta" events with answer text follow.
     *
     * @param question the user's question
     * @return the open SSE emitter
     * @throws IOException if the citations event cannot be sent
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuery(@RequestParam String question) throws IOException {
        UUID tenantId = TenantContext.get();
        SseEmitter emitter = new SseEmitter(60_000L);

        float[] queryEmbedding = embeddingService.embed(question);
        List<ChunkResult> chunks = hybridSearchService.search(tenantId, queryEmbedding, question, 5);

        emitter.send(SseEmitter.event().name("citations").data(chunks, MediaType.APPLICATION_JSON));
        streamingChatService.streamAnswer(question, chunks, emitter);

        return emitter;
    }
}
