package com.example.rag.storage;

/**
 * Formats Java float arrays as pgvector string literals.
 * PostgreSQL's pgvector extension accepts vector input as text like "[0.12,-0.04,...]".
 * Repositories bind these literals and cast them with {@code ?::vector} in SQL.
 */
public class VectorLiterals {

    /**
     * Converts an embedding into a pgvector-compatible literal.
     *
     * @param embedding the embedding values
     * @return the values formatted as "[v1,v2,...]"
     */
    public static String toLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
