package com.example.rag.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility class for computing cryptographic hashes of text content.
 * 
 * WHY WE NEED THIS:
 * Powers document and chunk deduplication across the RAG pipeline by creating
 * deterministic content fingerprints (file_hash and chunk_hash) before storing
 * or generating embeddings.
 */
public class Hashing {

    /**
     * Generates a 64-character lowercase SHA-256 hexadecimal string for the provided text.
     * 
     * @param text The raw string to hash (e.g., file content or a text chunk)
     * @return A unique 64-character hex string representing the content
     */
    public static String sha256(String text) {
        try {
            // 1. Get an instance of Java's standard SHA-256 message digest algorithm.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            // 2. Convert text to UTF-8 bytes, compute the binary hash digest, 
            //    and convert the raw byte array into a clean hex string.
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist in every standard Java Virtual Machine (JVM),
            // so this exception will never trigger under normal operating conditions.
            throw new RuntimeException("SHA-256 algorithm not available in current JVM", e);
        }
    }
}