# ADR-337: JWT Algorithm Confusion Attack Prevention

## Status
**Accepted** - 2026-03-08

## Context

JWT (JSON Web Token) algorithm confusion attacks are a critical security vulnerability where an attacker can manipulate the `alg` header field to bypass signature verification or force the use of weaker algorithms.

### Attack Vectors

1. **"none" Algorithm Attack**: Attacker sets `alg: "none"` to bypass signature verification entirely
2. **Algorithm Switching**: Attacker switches from asymmetric (RS256) to symmetric (HS256) to forge tokens using public key
3. **Downgrade Attack**: Attacker forces weaker algorithm (HS256 instead of HS512, HS512 instead of RS256)

### Current Implementation Analysis

The existing `JwtTokenProvider` uses JJWT 0.12.6 with:
- `Jwts.parser().verifyWith(secretKey)` for HMAC signature verification
- Post-parse algorithm validation: `require(headerAlgorithm == "HS256")`

**Vulnerabilities**:
- Algorithm validation occurs AFTER parsing, which is too late for "none" algorithm attacks
- No explicit algorithm whitelist configuration
- No case-insensitive "none" rejection

## Decision

Implement explicit algorithm whitelist with early validation to prevent algorithm confusion attacks.

### Implementation

1. **Algorithm Whitelist**: Define `ALLOWED_ALGORITHMS = setOf("HS256")`
2. **Forbidden Algorithms**: Explicitly reject "none" variants (case-insensitive)
3. **Early Validation**: Extract algorithm from header BEFORE signature verification
4. **Defense-in-Depth**: Keep post-parse validation as secondary check

### Code Changes

```kotlin
companion object {
    // Only HS256 (HMAC-SHA256) is allowed
    private val ALLOWED_ALGORITHMS = setOf("HS256")

    // Explicit "none" rejection (case-insensitive)
    private val FORBIDDEN_ALGORITHMS = setOf("none", "nOnE", "NONE", "None")
    private const val EXPECTED_ALGORITHM = "HS256"
}

private fun parseTokenInternal(token: String?): Optional<JwtPayload> {
    // 1. Pre-parse header validation
    val headerAlgorithm = extractAlgorithmFromHeader(token)

    // 2. Explicit "none" rejection
    require(headerAlgorithm.lowercase() !in FORBIDDEN_ALGORITHMS.map { it.lowercase() }) {
        "JWT algorithm 'none' is forbidden. Algorithm confusion attack detected."
    }

    // 3. Algorithm whitelist enforcement
    require(headerAlgorithm in ALLOWED_ALGORITHMS) {
        "JWT algorithm not in whitelist. Allowed: $ALLOWED_ALGORITHMS, Received: '$headerAlgorithm'"
    }

    // 4. Parse with signature verification
    val jws = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)

    // 5. Defense-in-depth: verify parsed algorithm
    require(jws.header.algorithm == EXPECTED_ALGORITHM) { ... }

    return Optional.of(payload)
}
```

## Alternatives Considered

### 1. Rely on JJWT Defaults
- **Rejected**: JJWT's `verifyWith(SecretKey)` enforces HMAC but doesn't explicitly prevent "none" attempts
- **Risk**: Early rejection prevents unnecessary processing of malicious tokens

### 2. Spring Security OAuth2 Resource Server
- **Rejected**: Overkill for simple JWT use case; requires additional dependencies
- **Complexity**: Our use case is simple HMAC signing, not OAuth2/OIDC

### 3. Post-Parse Validation Only
- **Rejected**: Current implementation; validation too late
- **Risk**: "none" algorithm may bypass signature verification in some JJWT versions

### 4. Allow Multiple Algorithms
- **Rejected**: Increases attack surface
- **Reason**: We only use HS256; no need for RS256/ES256

## Consequences

### Positive
- **Security**: Explicit algorithm whitelist prevents algorithm confusion attacks
- **Early Rejection**: Invalid algorithms rejected before signature verification
- **Compliance**: Aligns with [RFC 8725](https://datatracker.ietf.org/doc/html/rfc8725) JWT Best Practices
- **Defense-in-Depth**: Multiple validation layers (header check + signature verification)

### Negative
- **Complexity**: Additional header extraction logic
- **Maintenance**: Must update whitelist if algorithm changes (unlikely)

### Neutral
- **Performance**: Minimal impact from Base64URL decoding and regex extraction
- **Dependencies**: No new dependencies required

## References

- [RFC 8725: JSON Web Token Best Practices](https://datatracker.ietf.org/doc/html/rfc8725)
- [JJWT 0.12.x Documentation](https://github.com/jwtk/jjwt)
- [OWASP JWT Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)
- [Spring Security 7: JWT Algorithm Configuration](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/jwt.html)
- [NIST SP 800-63B: Digital Identity Guidelines](https://pages.nist.gov/800-63-3/sp800-63b.html)

## Test Coverage

Added comprehensive unit tests in `JwtTokenProviderTest.AlgorithmConfusionAttackTest`:
- `none`, `NONE`, `nOnE` algorithm rejection (case variations)
- HS512, RS256 algorithm rejection (not in whitelist)
- Missing algorithm field rejection
- Valid HS256 acceptance

## Migration Guide

No migration required. Existing HS256 tokens continue to work; only invalid algorithms are rejected.

## Related Issues

- Security Unit 1: JWT Algorithm Confusion Attack Prevention
- [CLAUDE.md Section 11: Exception Handling Strategy](../../CLAUDE.md#11-exception-handling-strategy-ai-mentor-recommendation)
- [CLAUDE.md Section 19: Implementation Workflow](../../CLAUDE.md#19-implementation-workflow-필수)
