package com.enterprise.oms.common.security;

import com.enterprise.oms.common.error.ErrorCode;
import com.enterprise.oms.common.error.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** 401 / 403 as problem+json (UNAUTHENTICATED / FORBIDDEN). Details never reveal why a token was rejected. */
public final class ProblemSecurityHandlers {

    private ProblemSecurityHandlers() {
    }

    public static class EntryPoint implements AuthenticationEntryPoint {
        private final ProblemWriter writer;

        public EntryPoint(ProblemWriter writer) {
            this.writer = writer;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException ex) throws IOException {
            response.setHeader("WWW-Authenticate", "Bearer");
            writer.write(request, response, ErrorCode.UNAUTHENTICATED, "Missing, invalid or expired access token");
        }
    }

    public static class Denied implements AccessDeniedHandler {
        private final ProblemWriter writer;

        public Denied(ProblemWriter writer) {
            this.writer = writer;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException ex) throws IOException {
            writer.write(request, response, ErrorCode.FORBIDDEN, "You are not allowed to access this resource");
        }
    }
}
