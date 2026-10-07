package com.alak.neuralgateway.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Filter for logging HTTP request/response details with correlation IDs.
 * Provides complete request tracing from ingress to egress.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestResponseLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "x-api-key", "cookie", "x-forwarded-for");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Generate or extract transaction ID
        

        // Extract requester
        String requester = httpRequest.getHeader("X-Requester");
        if (requester == null || requester.isBlank()) {
            requester = "Anonymous";
        }

        // Set MDC context for this request
        
        MDC.put("requester", requester);

        long startTime = System.currentTimeMillis();
        String method = httpRequest.getMethod();
        String uri = httpRequest.getRequestURI();
        String queryString = httpRequest.getQueryString();
        String fullUri = queryString != null ? uri + "?" + queryString : uri;

        try {
            log.info("HTTP_REQUEST_START: method={} uri={} remoteAddr={}", method, fullUri, httpRequest.getRemoteAddr());
            chain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            int status = httpResponse.getStatus();
            log.info("HTTP_REQUEST_END: method={} uri={} status={} durationMs={}", method, fullUri, status, durationMs);
            MDC.clear();
        }
    }
}
