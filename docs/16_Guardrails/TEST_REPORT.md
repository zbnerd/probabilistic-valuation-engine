# Guardrail Test Report

**Generated:** $(date)
**Total Patterns:** 39

## Test Results

| ID | Description | Violation Blocked | Compliant Passed | Result |
|----|-------------|-------------------|------------------|--------|
| GR-001 | try-catch prohibited | YES | YES | PASS |
| GR-002 | RuntimeException prohibited | YES | YES | PASS |
| GR-003 | AOP self-invocation prohibited | AI | AI | AI_JUDGMENT |
| GR-004 | Lambda hell prohibited | AI | AI | AI_JUDGMENT |
| GR-005 | Optional chaining required | AI | AI | AI_JUDGMENT |
| GR-RESILIENCE-001 | Circuit Breaker required | AI | AI | AI_JUDGMENT |
| GR-RESILIENCE-002 | Marker interface required | AI | AI | AI_JUDGMENT |
| GR-006 | Deprecated API prohibited | YES | YES | PASS |
| GR-TEST-001 | Thread.sleep prohibited | YES | YES | PASS |
| GR-TEST-002 | awaitTermination required | YES | YES | PASS |
| GR-TEST-003 | @DirtiesContext overuse | YES | YES | PASS |
| GR-TEST-004 | Testcontainers required | YES | YES | PASS |
| GR-TEST-005 | Clock injection required | YES | YES | PASS |
| GR-TEST-006 | Random ID injection required | YES | YES | PASS |
| GR-TEST-007 | @Transactional multithread | YES | YES | PASS |
| GR-ARCH-003 | Stateless violation | YES | YES | PASS |
| GR-ARCH-003-2 | Static mutable prohibited | YES | YES | PASS |
| GR-ARCH-007 | JPA IDENTITY batch disable | YES | YES | PASS |
| GR-ARCH-010 | V4 to V2 call prohibited | YES | YES | PASS |
| GR-ARCH-015 | Synchronous drain prohibited | YES | YES | PASS |
| GR-ARCH-005 | fixedRate scheduler prohibited | YES | YES | PASS |
| GR-ARCH-001 | TieredCache required | YES | YES | PASS |
| GR-ARCH-002 | SingleFlight required | YES | YES | PASS |
| GR-STYLE-001 | FQCN discouraged | YES | YES | PASS |
| GR-CACHE-001 | TieredCache SingleFlight | AI | AI | AI_JUDGMENT |
| GR-CACHE-002 | Follower timeout isolation | AI | AI | AI_JUDGMENT |
| GR-CACHE-003 | Cache configuration | AI | AI | AI_JUDGMENT |
| GR-CACHE-004 | Graceful degradation | AI | AI | AI_JUDGMENT |
| GR-CACHE-005 | Redis hash tag | AI | AI | AI_JUDGMENT |
| GR-LOGIC-001 | LogicExecutor patterns | AI | AI | AI_JUDGMENT |
| GR-LOGIC-002 | Lambda 3-line rule | AI | AI | AI_JUDGMENT |
| GR-AOP-001 | Facade pattern | AI | AI | AI_JUDGMENT |
| GR-AOP-002 | Filter bean registration | AI | AI | AI_JUDGMENT |
| GR-AOP-003 | SecurityContext thread-safe | AI | AI | AI_JUDGMENT |
| GR-AOP-004 | Sensitive data masking | AI | AI | AI_JUDGMENT |
| GR-AOP-005 | API Key JWT prohibition | AI | AI | AI_JUDGMENT |
| GR-AOP-006 | Security headers | AI | AI | AI_JUDGMENT |
| GR-AOP-007 | Swagger permitAll | AI | AI | AI_JUDGMENT |
| GR-AOP-008 | Logging level separation | AI | AI | AI_JUDGMENT |

## Summary

| Category | Count |
|----------|-------|
| **Total Patterns** | 39 |
| Layer 1 PASS (regex works) | 19 |
| Layer 1 FAIL (regex miss) | 0 |
| AI Judgment Required | 20 |
| False Positive | 0 |


## Classification

### Layer 1 (Regex Detection) - Working
Patterns where regex correctly detects violations and allows compliant code.

### Layer 2 (AI Judgment Required)
Patterns requiring semantic analysis - regex insufficient.

### False Positives
Patterns that incorrectly flag compliant code.
