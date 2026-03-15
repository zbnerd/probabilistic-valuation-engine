# Controller Contract Test Template

## Overview

`ControllerContractTestTemplate` is a base class for REST API contract testing. It uses `TestRestTemplate` with a real HTTP server (`RANDOM_PORT`) to test API contracts from the HTTP layer down to the database.

## When to Use

- **REST API Contract Testing**: Verify HTTP status codes, response schemas, headers
- **Integration Testing**: Test full request flow from HTTP → Controller → Service → Repository → DB
- **Authentication/Authorization**: Test security rules with actual JWT tokens
- **API Documentation Compliance**: Verify responses match OpenAPI specifications

## When NOT to Use

- **Service Logic Tests**: Use `ServiceTestTemplate` for service layer testing
- **External API Calls**: Mock external dependencies
- **Performance Testing**: Use dedicated load testing tools

## Directory Structure

```
module-web/src/test/kotlin/maple/expectation/
├── test/
│   ├── ControllerContractTestTemplate.kt    # Base template
│   ├── README.md                             # This file
│   └── ControllerContractTestTemplateExample.kt  # Example test
└── maple/expectation/web/controller/
    └── **/*ControllerTest.kt                 # Your controller tests
```

## HTTP Request Helpers

### GET Request

```kotlin
val response = get<Unit, CharacterResponse>(
    path = "/api/v1/characters/test-user",
    responseType = CharacterResponse::class.java,
    headers = jsonHeaders()
)
```

### POST Request

```kotlin
val response = post(
    path = "/api/v1/characters",
    body = CreateCharacterRequest("name", "job"),
    responseType = CharacterResponse::class.java,
    headers = withBearerToken(token)
)
```

### PUT Request

```kotlin
val response = put(
    path = "/api/v1/characters/{id}",
    body = UpdateCharacterRequest("new-name"),
    responseType = CharacterResponse::class.java
)
```

### DELETE Request

```kotlin
val response = delete<Unit, CharacterResponse>(
    path = "/api/v1/characters/{id}",
    responseType = CharacterResponse::class.java
)
```

## Assertion Helpers

### Status Code Assertions

```kotlin
assertOk(response)                    // 200 OK
assertCreated(response)               // 201 CREATED
assertBadRequest(response)            // 400 BAD REQUEST
assertUnauthorized(response)          // 401 UNAUTHORIZED
assertNotFound(response)              // 404 NOT FOUND
assertResponseStatus(response, HttpStatus.INTERNAL_SERVER_ERROR)  // Custom
```

### Body Assertions

```kotlin
assertResponseBodyNotNull(response)

// Additional assertions
assertThat(response.body.name).isEqualTo("expected-name")
assertThat(response.body.level).isGreaterThan(0)
```

### Performance Assertions

```kotlin
val startTime = System.currentTimeMillis()
val response = get<Unit, CharacterResponse>(...)
assertResponseTime(startTime, 500)  // Must complete in 500ms
```

## Header Helpers

```kotlin
// Bearer token for authenticated requests
val headers = withBearerToken("jwt-token")

// JSON content type
val headers = jsonHeaders()

// Custom headers
val headers = HttpHeaders().apply {
    contentType = MediaType.APPLICATION_JSON
    set("X-Custom-Header", "value")
}
```

## Example Test

See `ControllerContractTestTemplateExample.kt` for a complete example testing `GameCharacterControllerV1`.

## Best Practices

1. **Test Isolation**: Each test should be independent. Use `@BeforeEach` to set up test data.
2. **No Thread.sleep()**: Use `Awaitility` for async operations.
3. **Descriptive Assertions**: Combine multiple assertions for clarity.
4. **Test Realistic Scenarios**: Test both happy path and error cases.
5. **Response Time Checks**: Validate API performance expectations.
6. **No @MockBean**: Use real beans for true integration testing.

## Anti-patterns to Avoid

- ❌ Testing service logic directly in controller tests
- ❌ Mocking external dependencies (use testcontainers instead)
- ❌ Thread.sleep() for waiting
- ❌ Testing multiple endpoints in one test method
- ❌ Not cleaning up database between tests

## Configuration

The template uses:
- `@SpringBootTest(webEnvironment = RANDOM_PORT)`: Real HTTP server
- `@ActiveProfiles("test")`: Test-specific configuration
- `@Tag("integration")`: JUnit tag for filtering tests

## References

- [IntegrationTestBase.kt](../../../../../module-app/src/test/kotlin/maple/expectation/test/IntegrationTestBase.kt)
- [Testing Guide](../../../../../../docs/03_Technical_Guides/testing-guide.md)
- [ADR-005: Hexagonal Architecture](../../../../../../docs/adr/005-hexagonal-architecture.md)
