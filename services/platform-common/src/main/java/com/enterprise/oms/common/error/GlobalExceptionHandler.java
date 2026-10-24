package com.enterprise.oms.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps every failure to the OMS problem+json shape (contract section 5). Never leaks stack traces, SQL or
 * exception class names; unexpected errors are logged with their correlation id and returned as INTERNAL_ERROR.
 * Extends {@link ResponseEntityExceptionHandler} so all standard Spring MVC exceptions (405, 415, 404, missing
 * params, type mismatch, ...) are covered and enriched with code/correlationId/timestamp/errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OmsException.class)
    public ResponseEntity<Object> handleOms(OmsException ex, WebRequest request) {
        ProblemDetail pd = ProblemFactory.create(ex.code(), ex.getMessage(), path(request), ex.errors());
        return respond(ex, pd, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage())).toList();
        return respond(ex, ProblemFactory.create(ErrorCode.VALIDATION_FAILED, "Validation failed", path(request),
                errors), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(ex, ProblemFactory.create(ErrorCode.FORBIDDEN, "You are not allowed to perform this action",
                path(request), null), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return respond(ex, ProblemFactory.create(ErrorCode.UNAUTHENTICATED, "Authentication required",
                path(request), null), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, WebRequest request) {
        return respond(ex, ProblemFactory.create(ErrorCode.CONFLICT,
                "The resource was modified concurrently; reload and retry", path(request), null), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return respond(ex, ProblemFactory.create(ErrorCode.CONFLICT,
                "The request conflicts with existing data", path(request), null), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception for {}", path(request), ex);
        return respond(ex, ProblemFactory.create(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred; quote the correlationId when reporting it", path(request), null),
                request);
    }

    // ---- standard MVC exceptions ------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> errors.add(new FieldError(f.getField(), f.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(g -> errors.add(new FieldError(g.getObjectName(), g.getDefaultMessage())));
        return handleExceptionInternal(ex, ProblemFactory.create(ErrorCode.VALIDATION_FAILED, status,
                "Validation failed", path(request), errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e -> errors.add(
                new FieldError(r.getMethodParameter().getParameterName(), e.getDefaultMessage()))));
        return handleExceptionInternal(ex, ProblemFactory.create(ErrorCode.VALIDATION_FAILED, status,
                "Validation failed", path(request), errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PayloadTooLargeException p) {
                return handleExceptionInternal(ex, ProblemFactory.create(ErrorCode.PAYLOAD_TOO_LARGE,
                        p.getMessage(), path(request), null), headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
            }
        }
        return handleExceptionInternal(ex, ProblemFactory.create(ErrorCode.VALIDATION_FAILED, status,
                "Malformed or unreadable request body", path(request), null), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            ProblemFactory.enrich(pd, path(request));
        }
        return response;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private ResponseEntity<Object> respond(Exception ex, ProblemDetail pd, WebRequest request) {
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatusCode.valueOf(pd.getStatus()), request);
    }

    private static String path(WebRequest request) {
        if (request instanceof ServletWebRequest swr) {
            HttpServletRequest r = swr.getRequest();
            return r.getRequestURI();
        }
        return null;
    }

    private static String lastNode(String propertyPath) {
        int i = propertyPath.indexOf('.');
        return i >= 0 ? propertyPath.substring(i + 1) : propertyPath;
    }

}
