package com.example.rag.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * App-facing data access for documents and chunks over the tenant-restricted rag_app connection.
 * Every method runs in a transaction and first sets the row-level-security tenant variable, so queries only see that tenant's rows.
 * It supports upload deduplication, status lookups, and the vector and full-text searches behind hybrid retrieval.
 */
@Repository
public class VectorStoreRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the repository on the app (RLS-scoped) connection.
     *
     * @param jdbcTemplate the app JdbcTemplate
     */
    public VectorStoreRepository(@Qualifier("appJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Looks up a document by content hash so ingestion can skip a file this tenant has already processed.
     *
     * @param tenantId the tenant
     * @param fileHash the SHA-256 hash of the file text
     * @return the existing document's ID, if any
     */
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
     * Saves a new document under a freshly generated ID.
     *
     * @param tenantId the owning tenant
     * @param filename the original file name
     * @param fileHash the SHA-256 hash of the text
     * @param rawText  the full document text
     * @return the new document ID, for chunks to reference
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

    /**
     * Returns a document's ingestion status.
     *
     * @param tenantId   the tenant
     * @param documentId the document
     * @return the status, such as PENDING, PROCESSING, READY, or FAILED
     */
    @Transactional
    public String getStatus(UUID tenantId, UUID documentId) {
        applyTenant(tenantId);
        return jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId
        );
    }

    /**
     * Finds the chunks closest to a query embedding by pgvector cosine distance ({@code <=>}).
     *
     * @param tenantId       the tenant
     * @param queryEmbedding the embedded query
     * @param topK           the maximum number of results
     * @return up to topK chunks, closest (lowest distance) first
     */
    @Transactional
    public List<ChunkResult> vectorSearch(UUID tenantId, float[] queryEmbedding, int topK) {
        applyTenant(tenantId);
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

    /**
     * Runs a keyword search over the generated content_tsv column, for hybrid search alongside {@link #vectorSearch}.
     *
     * @param tenantId the tenant
     * @param query    the raw query text
     * @param topK     the maximum number of results
     * @return up to topK matching chunks, highest ts_rank first
     */
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

    /**
     * Sets the RLS tenant variable {@code app.current_tenant_id} for the current transaction.
     * <p>
     * The {@code true} (is_local) argument clears it on commit or rollback, so it never survives
     * to the pooled connection's next borrower.
     *
     * @param tenantId the tenant to scope queries to
     */
    private void applyTenant(UUID tenantId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String.class, tenantId.toString()
        );
    }
}
