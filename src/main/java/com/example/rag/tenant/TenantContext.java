package com.example.rag.tenant;

import java.util.UUID;

/**
 * Thread-isolated holder for the current HTTP request's tenant ID.
 * 
 * WHY WE NEED THIS:
 * Spring Boot uses a "one thread per request" model. ThreadLocal allows us to store 
 * the tenant ID on the current thread so any downstream layer (like VectorStoreRepository)
 * can access it via TenantContext.get() without cluttering method parameters.
 */
public class TenantContext {

    // ThreadLocal holds a separate value for each individual thread executing code.
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    /**
     * Stores the authenticated tenant's ID on the current thread.
     */
    public static void set(UUID tenantId) { 
        CURRENT.set(tenantId); 
    }

    /**
     * Retrieves the tenant ID associated with the current thread.
     */
    public static UUID get() { 
        return CURRENT.get(); 
    }

    /**
     * Removes the tenant ID from the current thread.
     * CRITICAL: Must be called when the request finishes to prevent memory leaks 
     * and thread pool cross-contamination.
     */
    public static void clear() { 
        CURRENT.remove(); 
    }
}