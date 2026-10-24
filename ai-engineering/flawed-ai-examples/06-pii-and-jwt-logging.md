# 06 Logging PII and JWTs

Code: `java/.../Ex06PiiLogging.java`
Test: `Ex06PiiLoggingTest` (3 tests)

## Prompt that produces it
"Add logging so we can debug order creation problems."

## Flawed code
```java
log.info("Creating order for email={} auth={} note={}", customer.email(), request.getHeader("Authorization"), note);
log.debug("Request body: {}", objectMapper.writeValueAsString(body));
```

## Why it is risky here
* A bearer token in a log is a credential: anyone with log access (Loki, support, a screenshot) can replay it until
  `exp`. Contract section 10: never log tokens or passwords.
* Full email addresses are personal data that then live in the log pipeline with different retention and access rules.
* A user-controlled string containing CR/LF can forge a fake log line (log injection) and confuse an investigation.
* `debug` request-body logging is how shipping addresses end up in logs the moment someone turns the level up.

## Corrected code
```java
log.info("Creating order for email={} authPresent={} note={}",
         LogMasking.maskEmail(email), header != null, LogMasking.stripNewlines(note));
```
Real helper: `com.enterprise.oms.common.observability.LogMasking` (`maskEmail` -> `a***@domain`, `maskToken`, `sanitize`,
`stripNewlines`). Prefer logging identifiers (orderId, customerId, correlationId) over attributes. JSON logging with
`correlationId`/`traceId` fields makes ids sufficient for debugging.

## Regression test
Flawed: output contains the full email, the bearer string, and a second injected `INFO` line. Fixed: masked email
(`p***@acme.example.test`), no token text, `authPresent=true`, exactly one line. The test fixtures use synthetic values.

Real-component test: `LogMaskingTest` in `services/platform-common/src/test/java/com/enterprise/oms/common/observability/`.
Related service test: `OrderTransitionsLoggingTest` (order-service). Recommended CI addition (still open, not implemented): a log-capture test per
service asserting no `Bearer ` or `@` unmasked patterns after a create-order call. The live correlation trace
(`docs/validation/correlation-trace-sample.md`) shows e-mail masked in real log lines.

## Review prompt to catch it
"Which fields in this log line are personal data or credentials, and are they necessary to find the bug?"
