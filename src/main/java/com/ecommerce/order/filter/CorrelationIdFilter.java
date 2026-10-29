package com.ecommerce.order.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    /** An incoming id is accepted only in this shape; anything else is replaced and never echoed or logged. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = req.getHeader(CORRELATION_ID_HEADER);
        if (cid == null || !VALID.matcher(cid).matches()) cid = UUID.randomUUID().toString();
        MDC.put("correlationId", cid);
        res.addHeader(CORRELATION_ID_HEADER, cid);
        try { chain.doFilter(req, res); } finally { MDC.remove("correlationId"); }
    }
}
