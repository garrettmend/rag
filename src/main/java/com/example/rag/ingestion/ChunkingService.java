package com.example.rag.ingestion;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document's plain text into chunks before embedding and storage.
 * Chunks are fixed-size, non-overlapping windows of at most 1,000 characters.
 * Each chunk is later embedded and stored independently for retrieval.
 */
@Service
public class ChunkingService {

    private static final int CHUNK_SIZE = 1000;

    /**
     * Splits text into non-overlapping chunks of at most 1,000 characters.
     *
     * @param text the document text
     * @return the chunks in document order
     */
    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHUNK_SIZE) {
            chunks.add(text.substring(start, Math.min(start + CHUNK_SIZE, text.length())));
        }
        return chunks;
    }
}
