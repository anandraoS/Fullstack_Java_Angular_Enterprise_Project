package com.enterprise.oms.common.web;

import com.enterprise.oms.common.error.ErrorCode;
import com.enterprise.oms.common.error.PayloadTooLargeException;
import com.enterprise.oms.common.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects bodies larger than {@code oms.web.max-body-bytes} (default 64 KB) with 413 PAYLOAD_TOO_LARGE.
 * Requests with a Content-Length above the limit are rejected up front; chunked/unknown-length bodies are counted
 * while read and fail with {@link PayloadTooLargeException} (mapped to 413 by the exception handler).
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ProblemWriter problemWriter;

    public RequestSizeLimitFilter(long maxBytes, ProblemWriter problemWriter) {
        this.maxBytes = maxBytes;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            problemWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Request body exceeds the maximum of " + maxBytes + " bytes");
            return;
        }
        chain.doFilter(declared >= 0 ? request : new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0 && ++count > max) {
                        throw new PayloadTooLargeException(max);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = delegate.read(buf, off, len);
                    if (n > 0) {
                        count += n;
                        if (count > max) {
                            throw new PayloadTooLargeException(max);
                        }
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
