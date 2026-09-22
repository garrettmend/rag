package com.example.rag.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * App-facing storage access, using the rag_app (RLS-scoped) connection. Every
 * method sets the row-level-security tenant variable before it queries.
 */
@Repository
public class VectorStoreRepository {

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreRepository(@Qualifier("appJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Lets ingestion skip re-embedding a file this tenant has already processed. */
    @Transactional
    public Optional<UUID> findDocumentByHash(UUID tenantId, String fileHash) {
        applyTenant(tenantId);
        return jdbcTemplate.query(
                "SELECT id FROM documents WHERE tenant_id = ? AND file_hash = ?",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                tenantId, fileHash
        ).stream().findFirst();
    }

    /**
     * Creates a UUID, saves a document, and returns the new document ID so
     * chunks can refer to it.
     */
    @Transactional
    public UUID insertDocument(UUID tenantId, String filename, String fileHash, String rawText) {
        applyTenant(tenantId);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO documents (id, tenant_id, filename, file_hash, raw_text) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, filename, fileHash, rawText
        );
        return id;
    }

    @Transactional
    public String getStatus(UUID tenantId, UUID documentId) {
        applyTenant(tenantId);
        return jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId
        );
    }

    /**
     * Finds the closest stored chunks to a query embedding using pgvector cosine
     * distance and returns at most topK results ordered from closest to farthest.
     */
    @Transactional
    public List<ChunkResult> vectorSearch(UUID tenantId, float[] queryEmbedding, int topK) {
        applyTenant(tenantId);
        // The pgvector <=> operator returns cosine distance; lower values are more similar.
        return jdbcTemplate.query(
                """
                SELECT id, document_id, content, embedding <=> ?::vector AS score
                FROM chunks
                ORDER BY score
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkResult(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("content"),
                        rs.getDouble("score")
                ),
                VectorLiterals.toLiteral(queryEmbedding), topK
        );
    }

    /** Keyword search over the generated content_tsv column, for hybrid search alongside vectorSearch. */
    @Transactional
    public List<ChunkResult> fullTextSearch(UUID tenantId, String query, int topK) {
        applyTenant(tenantId);
        return jdbcTemplate.query(
                """
                SELECT id, document_id, content, ts_rank(content_tsv, plainto_tsquery('english', ?)) AS score
                FROM chunks
                WHERE content_tsv @@ plainto_tsquery('english', ?)
                ORDER BY score DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkResult(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("content"),
                        rs.getDouble("score")
                ),
                query, query, topK
        );
    }

    // The `true` (is_local) argument clears this on commit/rollback, so it never survives to the connection's next borrower.
    private void applyTenant(UUID tenantId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String.class, tenantId.toString()
        );
    }
}
