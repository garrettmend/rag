package com.example.rag.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that requires and binds a tenant ID on every tenant-scoped API request.
 * Requests to /documents or /query without an X-Tenant-Id header are rejected with 400 before reaching any controller.
 * The parsed tenant is stored in {@link TenantContext} for the request and always cleared afterward, since Tomcat reuses threads.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    /**
     * Skips the filter for everything except the tenant-scoped APIs, since the static frontend has no tenant to isolate.
     *
     * @param request the incoming request
     * @return true if the path is outside /documents and /query
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/documents") || path.startsWith("/query"));
    }

    /**
     * Binds the X-Tenant-Id header to the thread for the rest of the chain, or rejects the request if it is missing.
     *
     * @param request  the incoming request
     * @param response the response
     * @param chain    the remaining filter chain
     * @throws ServletException if a downstream filter or servlet fails
     * @throws IOException      if writing the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String tenantHeader = request.getHeader("X-Tenant-Id");

        if (tenantHeader == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing X-Tenant-Id header");
            return;
        }

        try {
            TenantContext.set(UUID.fromString(tenantHeader));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
