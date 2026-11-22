package com.publicdomainplatform.email;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class DatabaseSubmissionFilter extends OncePerRequestFilter {
    private static final long WINDOW_MS = 60_000;
    private static final int LIMIT = 20;
    private static final int MAX_CLIENTS = 10_000;
    private final Map<String, Window> clients = new HashMap<>();
    private final CorsConfiguration cors;

    DatabaseSubmissionFilter(CorsConfiguration cors) {
        this.cors = cors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath() + "/api/grievances")
                && !request.getRequestURI().equals(request.getContextPath() + "/api/applications");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (cors.allowsDatabaseOrigin(origin) && !"OPTIONS".equals(request.getMethod())) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Vary", "Origin");
        }
        if (!request.isSecure() && !(cors.allowsLocalDatabaseOrigin(origin) && isLoopback(request.getRemoteAddr()))) {
            error(response, 403, "HTTPS is required");
            return;
        }
        if (!"OPTIONS".equals(request.getMethod()) && !allow(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "60");
            error(response, 429, "Rate limit exceeded");
            return;
        }
        long length = request.getContentLengthLong();
        long max = request.getRequestURI().endsWith("/grievances") ? 16_384 : 11L * 1024 * 1024;
        if (length > max) {
            error(response, 413, "Request is too large");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow(String address) {
        long now = System.currentTimeMillis();
        Window window = clients.get(address);
        if (window == null || now - window.start >= WINDOW_MS || now < window.start) {
            if (clients.size() >= MAX_CLIENTS && window == null) {
                Iterator<Window> iterator = clients.values().iterator();
                while (iterator.hasNext()) {
                    if (now - iterator.next().start >= WINDOW_MS) iterator.remove();
                }
                if (clients.size() >= MAX_CLIENTS) return false;
            }
            clients.put(address, new Window(now, 1));
            return true;
        }
        if (window.count >= LIMIT) return false;
        window.count++;
        return true;
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static class Window {
        private final long start;
        private int count;

        Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
