package com.example.rag.ingestion;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits plain text into bounded chunks before embedding and storage.
 */
@Service
public class ChunkingService {

    private static final int CHUNK_SIZE = 1000;

    /**
     * Splits text into non-overlapping chunks of at most 1,000 characters.
     */
    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHUNK_SIZE) {
            chunks.add(text.substring(start, Math.min(start + CHUNK_SIZE, text.length())));
        }
        return chunks;
    }
}
