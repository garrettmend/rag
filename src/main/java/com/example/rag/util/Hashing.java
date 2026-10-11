package com.example.rag.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes SHA-256 fingerprints of text content.
 * The hashes back deduplication across the pipeline, as the file_hash on documents and the chunk_hash on chunks.
 * Identical text always produces the same hash, so repeat uploads and retried chunk inserts can be detected.
 */
public class Hashing {

    /**
     * Hashes text with SHA-256 over its UTF-8 bytes.
     *
     * @param text the text to hash
     * @return the 64-character lowercase hex digest
     * @throws RuntimeException if SHA-256 is unavailable, which a standard JVM guarantees never happens
     */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available in current JVM", e);
        }
    }
}
