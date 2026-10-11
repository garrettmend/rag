package com.example.rag.ingestion;

import com.example.rag.storage.VectorStoreRepository;
import com.example.rag.tenant.TenantContext;
import com.example.rag.util.Hashing;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for uploading documents and checking their ingestion status.
 * Uploads are deduplicated per tenant by a SHA-256 hash of their text, so repeat files skip embedding entirely.
 * New documents are saved and queued for the background worker, and the endpoint returns a PENDING status immediately.
 */
@RestController
@RequestMapping("/documents")
public class IngestionController {

    private final VectorStoreRepository repository;
    private final IngestionQueue queue;

    /**
     * Creates the controller.
     *
     * @param repository tenant-scoped document storage
     * @param queue      the queue that hands documents to the ingestion worker
     */
    public IngestionController(VectorStoreRepository repository, IngestionQueue queue) {
        this.repository = repository;
        this.queue = queue;
    }

    /**
     * Accepts a multipart file upload at {@code POST /documents} for the current tenant.
     *
     * @param file the uploaded file, read as UTF-8 text
     * @return the document ID and a status of ALREADY_INGESTED or PENDING
     * @throws IOException if the upload cannot be read
     */
    @PostMapping
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file) throws IOException {
        UUID tenantId = TenantContext.get();
        String text = new String(file.getBytes(), StandardCharsets.UTF_8); //hash
        String fileHash = Hashing.sha256(text);

        Optional<UUID> existing = repository.findDocumentByHash(tenantId, fileHash); //check existing
        if (existing.isPresent()) {
            return Map.of("documentId", existing.get(), "status", "ALREADY_INGESTED");
        }

        UUID documentId = repository.insertDocument(tenantId, file.getOriginalFilename(), fileHash, text);
        queue.enqueue(documentId); //queue

        return Map.of("documentId", documentId, "status", "PENDING"); //turns to json
    }

    /**
     * Returns a document's ingestion status at {@code GET /documents/{id}} so clients can poll progress.
     *
     * @param id the document ID
     * @return the document ID and its status (PENDING, PROCESSING, READY, or FAILED)
     */
    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable UUID id) {
        UUID tenantId = TenantContext.get();
        return Map.of("documentId", id, "status", repository.getStatus(tenantId, id));
    }
}
