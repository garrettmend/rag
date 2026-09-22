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
 * REST Controller responsible for managing document uploads, deduplication checks,
 * and asynchronous background ingestion triggering.
 */
@RestController
@RequestMapping("/documents")
public class IngestionController {

    private final VectorStoreRepository repository;
    private final IngestionQueue queue;

    public IngestionController(VectorStoreRepository repository, IngestionQueue queue) {
        this.repository = repository;
        this.queue = queue;
    }

    /**
     * Handles multipart file uploads. 
     * Endpoint: POST /documents
     */
    @PostMapping
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file) throws IOException {
        
        // 1. ISOLATE BY TENANT
        // Extract the current tenant ID bound to this thread via ThreadLocal
        UUID tenantId = TenantContext.get();
        
        // 2. EXTRACT FILE CONTENTS
        // Convert the uploaded binary file stream into a clean UTF-8 string
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        
        // 3. GENERATE CRYPTOGRAPHIC HASH FOR DEDUPLICATION
        // Compute a SHA-256 fingerprint of the file text. 
        // If a user uploads the exact same document twice, the hash will match.
        String fileHash = Hashing.sha256(text);

        // 4. CHECK FOR EXISTING UPLOADS
        // Query the database to see if this tenant already processed this exact file content.
        Optional<UUID> existing = repository.findDocumentByHash(tenantId, fileHash);
        if (existing.isPresent()) {
            // Short-circuit: Skip embedding costs and return the existing document ID immediately.
            return Map.of("documentId", existing.get(), "status", "ALREADY_INGESTED");
        }

        // 5. PERSIST RAW TEXT
        // Save the raw document into the database, marking it as newly created.
        UUID documentId = repository.insertDocument(tenantId, file.getOriginalFilename(), fileHash, text);
        
        // 6. OFFLOAD TO ASYNCHRONOUS QUEUE
        // Push the document ID onto the SQS queue. A background worker thread pool 
        // will pick this up to handle chunking and embedding without blocking the HTTP request.
        queue.enqueue(documentId);

        // 7. IMMEDIATE RESPONSE
        // Return a 200 OK response instantly with a PENDING status so the client UI doesn't hang.
        return Map.of("documentId", documentId, "status", "PENDING");
    }

    /**
     * Allows clients to poll the processing progress of an asynchronous document ingestion.
     * Endpoint: GET /documents/{id}
     */
    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable UUID id) {
        // Retrieve the current tenant from context to ensure cross-tenant security
        UUID tenantId = TenantContext.get();
        
        // Fetch the current text status (e.g., PENDING, PROCESSING, READY, FAILED) from the database
        return Map.of("documentId", id, "status", repository.getStatus(tenantId, id));
    }
}