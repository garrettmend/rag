package com.example.rag.storage;

/**
 * Utility class for formatting Java float arrays into PostgreSQL pgvector string literals.
 * 
 * WHY WE NEED THIS:
 * PostgreSQL's pgvector extension requires vector inputs in string format, like "[0.12,-0.04,...]",
 * so it can properly cast them into the native vector database type using SQL casts (e.g., ?::vector).
 */
public class VectorLiterals {

    /**
     * Converts a Java float array into a pgvector-compatible string literal.
     * 
     * @param embedding The float array representing text vector dimensions (e.g., 1024 floats)
     * @return A string formatted as "[val1,val2,val3,...]" for PostgreSQL input
     */
    public static String toLiteral(float[] embedding) {
        // 1. Initialize StringBuilder with an opening square bracket expected by pgvector.
        StringBuilder sb = new StringBuilder("[");

        // 2. Iterate through each floating-point dimension in the embedding.
        for (int i = 0; i < embedding.length; i++) {
            // Append a comma before every element except the very first one.
            if (i > 0) {
                sb.append(",");
            }
            // Append the actual float value.
            sb.append(embedding[i]);
        }

        // 3. Append the closing square bracket and return the complete string literal.
        return sb.append("]").toString();
    }
}