package com.enterprise.oms.common.error;

import com.enterprise.oms.common.web.CorrelationIds;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/** Creates RFC 7807 {@link ProblemDetail}s in the OMS shape (contract section 5). */
public final class ProblemFactory {

    private ProblemFactory() {
    }

    public static ProblemDetail create(ErrorCode code, String detail, String instance, List<FieldError> errors) {
        return create(code, code.status(), detail, instance, errors);
    }

    public static ProblemDetail create(ErrorCode code, HttpStatusCode status, String detail, String instance,
                                       List<FieldError> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());
        setInstance(pd, instance);
        pd.setProperty("code", code.name());
        pd.setProperty("correlationId", CorrelationIds.current());
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("errors", errors == null ? List.of() : errors);
        return pd;
    }

    /** Adds OMS properties that a framework-created ProblemDetail lacks (keeps existing ones). */
    public static ProblemDetail enrich(ProblemDetail pd, String instance) {
        ErrorCode code = ErrorCode.forStatus(pd.getStatus());
        if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
            pd.setType(URI.create(code.typeUri()));
            pd.setProperty("code", code.name());
        }
        if (pd.getInstance() == null) {
            setInstance(pd, instance);
        }
        if (pd.getProperties() == null || !pd.getProperties().containsKey("correlationId")) {
            pd.setProperty("correlationId", CorrelationIds.current());
        }
        if (!pd.getProperties().containsKey("timestamp")) {
            pd.setProperty("timestamp", Instant.now().toString());
        }
        if (!pd.getProperties().containsKey("errors")) {
            pd.setProperty("errors", List.of());
        }
        return pd;
    }

    private static void setInstance(ProblemDetail pd, String instance) {
        if (instance == null) {
            return;
        }
        try {
            pd.setInstance(URI.create(instance));
        } catch (IllegalArgumentException ignored) {
            // unparsable path: omit instance
        }
    }
}
