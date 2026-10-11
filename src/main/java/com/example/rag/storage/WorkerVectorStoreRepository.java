package com.example.rag.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Data access for background ingestion over the rag_worker connection.
 * That database role has an explicit RLS policy permitting it to process documents across every tenant.
 * It fetches a document's raw text, updates its status, and inserts chunks idempotently so retries are safe.
 */
@Repository
public class WorkerVectorStoreRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the repository on the worker connection.
     *
     * @param jdbcTemplate the worker JdbcTemplate
     */
    public WorkerVectorStoreRepository(@Qualifier("workerJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * The fields the worker needs to process a document.
     *
     * @param tenantId the owning tenant
     * @param rawText  the full document text
     */
    public record DocumentForProcessing(UUID tenantId, String rawText) {}

    /**
     * Loads a document's tenant and raw text.
     *
     * @param documentId the document
     * @return the document's tenant and text
     */
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

    /**
     * Sets a document's ingestion status.
     *
     * @param documentId the document
     * @param status     the new status, such as PROCESSING, READY, or FAILED
     */
    public void updateDocumentStatus(UUID documentId, String status) {
        jdbcTemplate.update("UPDATE documents SET status = ? WHERE id = ?", status, documentId);
    }

    /**
     * Inserts a chunk unless one with the same hash already exists for the document.
     *
     * @param tenantId   the owning tenant
     * @param documentId the source document
     * @param content    the chunk text
     * @param chunkHash  the SHA-256 hash of the chunk text
     * @param embedding  the chunk's embedding
     * @return 1 if the chunk was inserted, 0 if it already existed
     */
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
