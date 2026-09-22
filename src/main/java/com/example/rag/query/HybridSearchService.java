package com.example.rag.query;

import com.example.rag.storage.ChunkResult;
import com.example.rag.storage.VectorStoreRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HybridSearchService {

    // A standard constant used in the RRF math formula. 
    // We add 60 to the rank so that 1st place doesn't get drastically 
    // more points than 2nd place. It "smooths" the curve out.
    private static final int RRF_K = 60;
    
    // We will pull the Top 20 results from both Vector and Text searches
    // before combining them to find the ultimate winners.
    private static final int CANDIDATES_PER_SOURCE = 20;
    
    private final VectorStoreRepository repository;

    public HybridSearchService(VectorStoreRepository repository) {
        this.repository = repository;
    }

    public List<ChunkResult> search(UUID tenantId, float[] queryEmbedding, String queryText, int topK) {
        
        // 1. Get Judge A's list (Vector Search: matches the general meaning)
        List<ChunkResult> vectorResults = repository.vectorSearch(tenantId, queryEmbedding, CANDIDATES_PER_SOURCE);
        
        // 2. Get Judge B's list (Full-Text Search: matches the exact words used)
        List<ChunkResult> textResults = repository.fullTextSearch(tenantId, queryText, CANDIDATES_PER_SOURCE);

        // This map keeps track of the total RRF score for each document ID
        Map<UUID, Double> rrfScores = new HashMap<>();
        
        // This map is just a handy lookup table to remember the actual document data 
        // (so we can return the documents at the end, not just their IDs)
        Map<UUID, ChunkResult> byId = new HashMap<>();

        // 3. Hand out points based on rank for the Vector results
        addRankScores(vectorResults, rrfScores, byId);
        
        // 4. Hand out points based on rank for the Text results.
        // If a document was already found in the Vector search, it gets bonus points here!
        addRankScores(textResults, rrfScores, byId);

        // 5. Sort the final scoreboard from highest points to lowest points
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK) // Only return the requested number of top results
                .map(e -> byId.get(e.getKey())) // Convert the winning IDs back into the actual documents
                .toList();
    }

    // The helper method that actually does the RRF math
    private void addRankScores(List<ChunkResult> results, Map<UUID, Double> scores, Map<UUID, ChunkResult> byId) {
        // Loop through the results (rank 0 is 1st place, rank 1 is 2nd place, etc.)
        for (int rank = 0; rank < results.size(); rank++) {
            ChunkResult chunk = results.get(rank);
            
            // Save the document in our lookup table
            byId.put(chunk.id(), chunk);
            
            // The RRF Formula: 1 / (60 + rank + 1)
            // Example: 1st place gets 1/61 points. 2nd place gets 1/62 points.
            // If the document is already in the 'scores' map, Double::sum adds the new points to the old points.
            scores.merge(chunk.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }
}