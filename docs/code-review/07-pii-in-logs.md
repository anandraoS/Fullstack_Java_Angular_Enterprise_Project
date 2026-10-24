# 07 - PII and credentials in logs

Severity: **High** (privacy breach and credential exposure through a channel with broad access)

## PR title: "Improve diagnostics for order creation"

```diff
 @PostMapping("/orders")
 public ResponseEntity<OrderDto> create(@RequestBody CreateOrderRequest req,
                                        @RequestHeader("Authorization") String auth,
                                        @RequestHeader("Idempotency-Key") String key) {
+    log.info("Create order request: auth={} key={} body={}", auth, key, objectMapper.writeValueAsString(req));
     ...
 }

 // notification consumer
+    log.info("Sending email to {} subject={}", order.customerEmail(), subject);
 // global error path
+    log.error("Unhandled error for request " + request.getRequestURI() + "?" + request.getQueryString(), ex);
```

## Reasoning

* `auth` is the bearer JWT. Anyone with log access can replay it until `exp` (3600 s from the dev endpoint): credential leak.
* The body holds the shipping address (line1, city, postal code) and potentially the customer email: personal data. Logs are copied to aggregators (Loki/ELK), retained longer than the database, and read by more people. Right-to-erasure requests cannot reach them.
* `customerEmail` in full: contract section 10 requires masking `a***@domain`.
* The error path concatenates the query string: `q=` search terms can contain emails or names; string concatenation also defeats parameterised logging and, with user-controlled text, enables **log injection** (a value containing `\n2026-... INFO admin login ok` forges lines).
* The `Idempotency-Key` is not secret but is a correlation handle for a request body hash; fine to log, but sanitize its length/charset (already restricted to `[A-Za-z0-9_-]`, 8..64 by validation).
* Exceptions: `log.error(msg, ex)` prints the exception message, which for `DataIntegrityViolationException` may include the failing row values (email). Log exception class and ids, and keep the stack trace in a restricted logger if needed.

## Fix

```java
log.info("Create order customerId={} items={} idemKey={}", req.customerId(), req.items().size(), key);
// notification
log.info("Notification queued orderId={} template={} to={}", orderId, template, LogMasking.maskEmail(email));  // verified helper
// error path
log.error("Unhandled error uri={} correlationId={}", LogMasking.stripNewlines(request.getRequestURI()),
          CorrelationIds.current(), ex);     // no query string, no body
```

`LogMasking` (verified: `maskEmail`, `maskToken`, `sanitize`, `stripNewlines`) lives in `services/platform-common/.../observability/LogMasking.java` and is covered by `LogMaskingTest`. Rules of thumb: log ids and counts, never request/response bodies, never headers wholesale (`Authorization`, `Cookie`, `X-Api-Key`). Configure the HTTP client logging level so it never prints headers at INFO or below in shared environments.

Prevention beyond review: a Logback `MessageConverter`/turbo filter that applies `LogMasking.sanitize` to every message as a safety net (defence in depth, not a substitute for not logging), and a CI grep for `log\.(info|debug|warn|error).*(auth|token|password|email|body)` as a cheap smoke check.

## Regression tests

```java
// sketch: capture logs with Logback ListAppender
@Test
void createOrder_doesNotLogTokenOrFullEmailOrBody() throws Exception {
    var appender = attachAppender(OrderController.class);
    String token = customerToken(CUSTOMER_1);
    mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "Idem-Key-0007")
            .with(bearer(token)).contentType(APPLICATION_JSON)
            .content(orderJsonFor(CUSTOMER_1, "line1-synthetic-street")))
       .andExpect(status().isAccepted());

    String all = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(joining("\n"));
    assertThat(all).doesNotContain(token)
                   .doesNotContain("procurement@acme.example.test")
                   .doesNotContain("line1-synthetic-street");
}

@Test
void maskEmail_keepsDomainOnly() {
    // verified behaviour: keeps the first character, "alice@example.com" -> "a***@example.com"
    assertThat(LogMasking.maskEmail("procurement@acme.example.test")).isEqualTo("p***@acme.example.test");
}

@Test
void stripNewlines_preventsForgedLogLines() {
    assertThat(LogMasking.stripNewlines("a\nb\r\nc")).doesNotContain("\n").doesNotContain("\r");
}
```

(`stripNewlines` replaces CR/LF with `_`, per the verified implementation.)

## Reviewer checklist from this scenario

- [ ] No `Authorization`, cookies, bodies or full emails in any log call.
- [ ] Parameterised logging only; user text passes through `stripNewlines`.
- [ ] A test captures logs for the endpoint and asserts absence of sensitive strings.
- [ ] If there is an incident: rotate/expire exposed tokens, purge or restrict logs, and record it in the audit trail.
