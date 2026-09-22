package com.example.rag.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Background-processing data access for ingestion: fetches a document's raw text,
 * flips its status, and inserts chunks idempotently. Uses the rag_worker
 * connection. Its database role has an explicit worker RLS policy that permits
 * jobs to process documents across every tenant.
 */
@Repository
public class WorkerVectorStoreRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkerVectorStoreRepository(@Qualifier("workerJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record DocumentForProcessing(UUID tenantId, String rawText) {}

    public DocumentForProcessing getDocumentForProcessing(UUID documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT tenant_id, raw_text FROM documents WHERE id = ?",
                (rs, rowNum) -> new DocumentForProcessing(
                        UUID.fromString(rs.getString("tenant_id")),
                        rs.getString("raw_text")
                ),
                documentId
        );
    }

    public void updateDocumentStatus(UUID documentId, String status) {
        jdbcTemplate.update("UPDATE documents SET status = ? WHERE id = ?", status, documentId);
    }

    /** Returns 0 if the chunk already existed (conflict, no-op), 1 if it was inserted. */
    public int insertChunkIfAbsent(UUID tenantId, UUID documentId, String content, String chunkHash, float[] embedding) {
        return jdbcTemplate.update(
                """
                INSERT INTO chunks (id, tenant_id, document_id, chunk_hash, content, embedding)
                VALUES (?, ?, ?, ?, ?, ?::vector)
                ON CONFLICT (document_id, chunk_hash) DO NOTHING
                """,
                UUID.randomUUID(), tenantId, documentId, chunkHash, content, VectorLiterals.toLiteral(embedding)
        );
    }
}
