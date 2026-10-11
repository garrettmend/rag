package com.example.rag.tenant;

import java.util.UUID;

/**
 * Holds the current HTTP request's tenant ID in a ThreadLocal.
 * Because each request runs on its own thread, any downstream layer can read the tenant without it being passed through every method.
 * The tenant filter sets the value at the start of a request and must clear it at the end so pooled threads never leak it.
 */
public class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    /**
     * Binds a tenant ID to the current thread.
     *
     * @param tenantId the request's tenant
     */
    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    /**
     * Returns the tenant ID bound to the current thread.
     *
     * @return the tenant, or null if none is set
     */
    public static UUID get() {
        return CURRENT.get();
    }

    /**
     * Removes the tenant ID from the current thread.
     * Must be called when the request finishes to prevent leaks across pooled threads.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
