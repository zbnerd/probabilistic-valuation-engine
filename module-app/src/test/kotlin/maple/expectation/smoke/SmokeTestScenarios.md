# P0 Smoke Test Scenarios

## Overview

This document describes all P0 (Priority 0) smoke test scenarios that verify the critical paths of the MapleExpectation application. These tests ensure the core functionality is working and the application is healthy.

## Current Status

**⚠️ Infrastructure Issue**: The smoke tests are currently **disabled** due to database schema issues. The Hibernate DDL generation is using MySQL-specific types (like `longblob`) instead of PostgreSQL-compatible types, causing application context startup failures.

### Required Fixes

1. **Database Schema DDL**: Fix Hibernate entity mappings to use PostgreSQL-compatible types
2. **Entity Annotations**: Review `@Lob` and `@Column` annotations across all JPA entities
3. **Flyway/Liquibase**: Consider adding explicit migration scripts for better control

## Test Infrastructure

- **Base Class**: `SmokeTestBase.kt`
- **Test Profile**: `test` (configured in `application-test.yml`)
- **Web Environment**: `RANDOM_PORT` (actual HTTP server)
- **Response Time Limit**: 500ms for all P0 paths

## Running Smoke Tests

```bash
# Run all smoke tests (currently disabled)
./gradlew :module-app:test --tests "*SmokeTest*" --continue

# Run specific smoke test class
./gradlew :module-app:test --tests "*.P0HealthSmokeTest" --continue

# Enable tests after fixing infrastructure issues
# Remove @Disabled annotations from test classes
```

## P0 Critical Paths

| Scenario ID | Path | Description | Priority | Status |
|-------------|------|-------------|----------|--------|
| S001 | /actuator/health | Health check endpoint | P0 | ⚠️ Disabled |
| S002 | /actuator/health/liveness | Liveness probe | P0 | ⚠️ Disabled |
| S003 | /actuator/health/readiness | Readiness probe | P0 | ⚠️ Disabled |
| S004 | /api/v1/characters/{userIgn} | Character lookup by IGN | P0 | ⚠️ Disabled |
| S005 | /api/v4/characters/{userIgn}/expectation | Expectation calculation | P0 | ⚠️ Disabled |
| S006 | /api/v4/characters/{userIgn}/expectation/recalculate | Force recalculation | P0 | ⚠️ Disabled |

## Test Scenarios

### S001: Health Check

**Test Class**: `P0HealthSmokeTest.healthEndpointReturnsUP()`

**Description**: Verify the main health endpoint returns UP status.

**Given**: Application is running
**When**: GET /actuator/health
**Then**:
- HTTP 200 status code
- Response body contains "UP"
- Response time < 500ms

---

### S002: Liveness Probe

**Test Class**: `P0HealthSmokeTest.livenessProbeReturnsOK()`

**Description**: Verify the liveness probe returns OK status.

**Given**: Application is running
**When**: GET /actuator/health/liveness
**Then**:
- HTTP 200 status code
- Response body contains "UP"
- Response time < 500ms

---

### S003: Readiness Probe

**Test Class**: `P0HealthSmokeTest.readinessProbeReturnsOK()`

**Description**: Verify the readiness probe returns OK status.

**Given**: Application is running
**When**: GET /actuator/health/readiness
**Then**:
- HTTP 200 status code
- Response body contains "UP"
- Response time < 500ms

---

### S004: Character OCID Lookup

**Test Class**: `P0CharacterSmokeTest.characterLookupByIgnReturnsValidResponse()`

**Description**: Verify character lookup by IGN returns valid response with all required fields.

**Given**: Test character exists in database
**When**: GET /api/v1/characters/{userIgn} (with USER role)
**Then**:
- HTTP 200 status code
- Response contains required fields: userIgn, ocid, worldName, characterClass
- OCID is properly formatted (hex string)
- Response time < 500ms

---

### S005: Expectation Calculation

**Test Class**: `P0ExpectationSmokeTest.expectationEndpointRespondsWithinP0TimeLimit()`

**Description**: Verify expectation calculation endpoint responds within P0 time limit.

**Given**: Test character exists in database
**When**: GET /api/v4/characters/{userIgn}/expectation (with USER role)
**Then**:
- Response time < 500ms
- HTTP status is 200, 404, or 500 (external API may fail)
- Endpoint is accessible and doesn't timeout

---

### S006: Expectation Force Recalculation

**Test Class**: `P0ExpectationSmokeTest.expectationForceRecalculateEndpointIsAccessible()`

**Description**: Verify force recalculation endpoint is accessible to admins.

**Given**: Test character exists in database
**When**: POST /api/v4/characters/{userIgn}/expectation/recalculate (with ADMIN role)
**Then**:
- Response time < 500ms
- HTTP status is 200, 404, or 500 (external API may fail)
- Endpoint is accessible to admin users

---

## Authentication Tests

### Unauthenticated Access

**Description**: Verify protected endpoints return 401/403 without authentication.

**Tests**:
- Character lookup without auth → 401/403
- Expectation calculation without auth → 401/403

---

## Response Time Requirements

All P0 paths must respond within **500ms**. This includes:
- Network latency
- Database queries
- External API calls (with timeout handling)

---

## Expected Behaviors

### Success Cases
- Health endpoints always return 200 with "UP" status
- Character lookup returns 200 with valid data
- Expectation calculation returns 200 with data or appropriate error

### Error Cases
- 401: Unauthorized (missing or invalid authentication)
- 403: Forbidden (insufficient permissions)
- 404: Not found (character doesn't exist in Nexon API)
- 500: Internal server error (external API failure)

---

## Continuous Integration

Smoke tests should run:
1. **On every PR**: Quick feedback loop
2. **Before deployment**: Verify production readiness
3. **In production**: As a synthetic monitor

---

## Troubleshooting

### Infrastructure Issues

**Database Schema DDL Errors**:
- **Symptom**: `ERROR: type "longblob" does not exist`
- **Root Cause**: Hibernate entities using MySQL-specific types
- **Solution**:
  1. Review all `@Lob` annotations in JPA entities
  2. Replace with PostgreSQL-compatible types using `@Column(columnDefinition = "bytea")`
  3. Add explicit Flyway migrations for schema control

**Missing Bean Dependencies**:
- **Symptom**: `NoSuchBeanDefinitionException` for cache managers or query services
- **Root Cause**: Conditional configuration not activating in test profile
- **Solution**: Add required properties to `application-test.yml`:
  ```yaml
  cache:
    l2:
      enabled: "true"
      impl: "postgres"
  ```

### Test Failures

1. **Health Check Failing**:
   - Check if application started successfully
   - Verify actuator dependencies are included
   - Check application logs for startup errors

2. **Character Lookup Failing**:
   - Verify test data setup completed
   - Check database connection
   - Verify authentication is working

3. **Expectation Calculation Timeout**:
   - Check external API availability (Nexon API)
   - Verify timeout configurations
   - Check circuit breaker status

4. **Authentication Errors**:
   - Verify JWT secret is configured
   - Check security configuration
   - Verify test user roles

---

## Future Enhancements

- [ ] Fix database schema DDL generation for PostgreSQL
- [ ] Add performance regression detection
- [ ] Add synthetic monitoring in production
- [ ] Add rate limiting smoke tests
- [ ] Add cache hit rate monitoring
- [ ] Add external API dependency health checks

---

## Related Documentation

- [Testing Guide](../../docs/03_Technical_Guides/testing-guide.md)
- [Test Template Documentation](../../docs/03_Technical_Guides/test-templates.md)
- [Infrastructure Configuration](../../docs/03_Technical_Guides/infrastructure.md)

