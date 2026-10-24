package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex06PiiLogging.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Ex06PiiLoggingTest {

    // Synthetic values only. The "token" is not a real credential.
    private static final String EMAIL = "procurement@acme.example.test";
    private static final String AUTH = "Bearer synthetic.header.payload";
    private static final String FORGED_NOTE = "ok\nINFO Order o-999 approved by admin1";

    @Test
    void flawedLogsFullEmailTokenAndAllowsForgedLogLine() {
        LogSink sink = new LogSink();
        new FlawedOrderLogging(sink).onCreate(EMAIL, AUTH, FORGED_NOTE);

        assertTrue(sink.all().contains(EMAIL), "BUG MANIFESTS: full email in logs");
        assertTrue(sink.all().contains(AUTH), "BUG MANIFESTS: bearer token in logs");
        assertTrue(sink.all().contains("\nINFO Order o-999 approved"), "BUG MANIFESTS: injected fake log line");
    }

    @Test
    void fixedMasksEmailOmitsTokenAndFlattensNewlines() {
        LogSink sink = new LogSink();
        new FixedOrderLogging(sink).onCreate(EMAIL, AUTH, FORGED_NOTE);

        String out = sink.all();
        assertFalse(out.contains(EMAIL));
        assertTrue(out.contains("p***@acme.example.test"));
        assertFalse(out.contains("synthetic.header.payload"));
        assertTrue(out.contains("authPresent=true"));
        assertEquals(1, out.lines().count(), "one logical event stays one line");
    }

    @Test
    void maskEmailHandlesEdgeCases() {
        assertNull(Ex06PiiLogging.maskEmail(null));
        assertEquals("***", Ex06PiiLogging.maskEmail("no-at-sign"));
        assertEquals("a***@x.test", Ex06PiiLogging.maskEmail("abc@x.test"));
    }
}
