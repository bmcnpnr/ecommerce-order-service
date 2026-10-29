package com.ecommerce.order.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

/**
 * {@code /actuator/serviceregistry} can take the instance out of service, so it answers only to the
 * loopback address (the Kubernetes preStop hook calls it on localhost). Everything else gets 403.
 * Forwarding headers are deliberately not consulted.
 */
@Component
public class ActuatorLoopbackFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (targetsServiceRegistry(req.getRequestURI()) && !isLoopback(req.getRemoteAddr())) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /** Checks the raw and the percent-decoded path, case-insensitively, so an encoded spelling cannot slip past. */
    static boolean targetsServiceRegistry(String uri) {
        String raw = uri.toLowerCase(java.util.Locale.ROOT);
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(uri.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return raw.contains("serviceregistry"); // malformed encoding cannot address an endpoint
        }
        return raw.contains("serviceregistry") || decoded.contains("serviceregistry");
    }

    static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            // Only literal addresses are expected; refuse anything that would need a DNS lookup.
            if (!remoteAddr.matches("[0-9a-fA-F:.%]+")) {
                return false;
            }
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
