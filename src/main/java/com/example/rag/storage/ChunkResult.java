package com.example.rag.storage;

import java.util.UUID;

/**
 * A stored chunk returned from a search, together with its relevance score.
 * For vector search the score is cosine distance, so lower is closer; for full-text search it is ts_rank, so higher is better.
 * Instances are also serialized to clients as the citations for an answer.
 *
 * @param id         the chunk ID
 * @param documentId the ID of the document the chunk came from
 * @param content    the chunk text
 * @param score      the search-specific relevance score
 */
public record ChunkResult(UUID id, UUID documentId, String content, double score) {}
