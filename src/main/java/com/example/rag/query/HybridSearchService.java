package com.example.rag.query;

import com.example.rag.storage.ChunkResult;
import com.example.rag.storage.VectorStoreRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Combines vector similarity search and full-text keyword search into one ranked result list.
 * It pulls the top 20 candidates from each search and merges them with Reciprocal Rank Fusion (RRF).
 * Chunks that rank well in both searches accumulate the highest combined scores and are returned first.
 */
@Service
public class HybridSearchService {

    private static final int RRF_K = 60;

    private static final int CANDIDATES_PER_SOURCE = 20;

    private final VectorStoreRepository repository;

    /**
     * Creates the service.
     *
     * @param repository tenant-scoped chunk storage providing both search types
     */
    public HybridSearchService(VectorStoreRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs vector and full-text search for a tenant and returns the top chunks by fused RRF score.
     *
     * @param tenantId       the tenant whose chunks to search
     * @param queryEmbedding the embedded query, for semantic matching
     * @param queryText      the raw query, for keyword matching
     * @param topK           the maximum number of chunks to return
     * @return the highest-scoring chunks, best first
     */
    public List<ChunkResult> search(UUID tenantId, float[] queryEmbedding, String queryText, int topK) {
        List<ChunkResult> vectorResults = repository.vectorSearch(tenantId, queryEmbedding, CANDIDATES_PER_SOURCE);
        List<ChunkResult> textResults = repository.fullTextSearch(tenantId, queryText, CANDIDATES_PER_SOURCE);

        Map<UUID, Double> rrfScores = new HashMap<>();
        Map<UUID, ChunkResult> byId = new HashMap<>();

        addRankScores(vectorResults, rrfScores, byId);
        addRankScores(textResults, rrfScores, byId);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    /**
     * Adds each result's RRF contribution, {@code 1 / (RRF_K + rank + 1)}, to its running score.
     * <p>
     * The constant {@code RRF_K = 60} flattens the curve so first place is not weighted drastically above second.
     *
     * @param results one search's results, best first
     * @param scores  running RRF score per chunk ID, updated in place
     * @param byId    lookup from chunk ID to chunk, updated in place
     */
    private void addRankScores(List<ChunkResult> results, Map<UUID, Double> scores, Map<UUID, ChunkResult> byId) {
        for (int rank = 0; rank < results.size(); rank++) {
            ChunkResult chunk = results.get(rank);
            byId.put(chunk.id(), chunk);
            scores.merge(chunk.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }
}
