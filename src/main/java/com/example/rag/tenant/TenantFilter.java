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
 * HTTP Servlet Filter that enforces and extracts tenant identification on every API request.
 * 
 * WHY WE NEED THIS:
 * Acts as a security checkpoint at the edge of the web application. It prevents any 
 * unassigned request from executing database queries and binds the tenant ID to the thread.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    // Only the tenant-scoped APIs need a tenant; the static frontend has none to isolate.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/documents") || path.startsWith("/query"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain chain) throws ServletException, IOException {
        
        // 1. Extract the tenant ID header from the incoming HTTP request.
        String tenantHeader = request.getHeader("X-Tenant-Id");

        // 2. Reject the request immediately if the header is missing.
        if (tenantHeader == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing X-Tenant-Id header");
            return;
        }

        try {
            // 3. Parse the string into a UUID and bind it to the current execution thread.
            TenantContext.set(UUID.fromString(tenantHeader));

            // 4. Pass the request along down the filter chain to your Controllers.
            chain.doFilter(request, response);
            
        } finally {
            // 5. ALWAYS clear the thread memory after the request finishes (even if an error occurred).
            // Tomcat reuses threads; failing to clear this would leak Company A's context into Company B's next request.
            TenantContext.clear();
        }
    }
}