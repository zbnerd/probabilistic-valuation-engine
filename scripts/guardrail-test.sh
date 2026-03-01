#!/bin/bash
# Guardrail Automated Test Script
# Tests all guardrail patterns from INDEX.json

set -e

PROJECT_ROOT="/home/maple/MapleExpectation"
TEST_DIR="$PROJECT_ROOT/src/main/kotlin/test/guardrail"
REPORT_FILE="$PROJECT_ROOT/docs/guardrails/TEST_REPORT.md"
INDEX_FILE="$PROJECT_ROOT/docs/guardrails/INDEX.json"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Counters
TOTAL=0
LAYER1_PASS=0
LAYER1_FAIL=0
AI_JUDGMENT=0
FALSE_POSITIVE=0

echo "=========================================="
echo "Guardrail Automated Test Suite"
echo "=========================================="

# Create test directory
mkdir -p "$TEST_DIR"

# Initialize report
cat > "$REPORT_FILE" << 'EOF'
# Guardrail Test Report

**Generated:** $(date)
**Total Patterns:** 39

## Test Results

| ID | Description | Violation Blocked | Compliant Passed | Result |
|----|-------------|-------------------|------------------|--------|
EOF

# Test cases array (violation code, compliant code, regex pattern)
declare -A TEST_CASES

# GR-001: try-catch
TEST_CASES["GR-001_violation"]='fun badCode() { try { doSomething() } catch (e: Exception) { log.error(e) } }'
TEST_CASES["GR-001_compliant"]='fun goodCode() = executor.execute({ doSomething() }, TaskContext.of("Domain", "Action", "id"))'
TEST_CASES["GR-001_regex"]='\btry\s*\{'

# GR-002: RuntimeException
TEST_CASES["GR-002_violation"]='fun badCode() { throw RuntimeException("Error") }'
TEST_CASES["GR-002_compliant"]='fun goodCode() { throw ClientBaseException(ErrorCode.INVALID_INPUT) }'
TEST_CASES["GR-002_regex"]='throw\s+(?:new\s+)?RuntimeException\s*\('

# GR-003: AOP self-invocation (AI judgment)
TEST_CASES["GR-003_violation"]='@Service class BadService { fun outer() { inner() } fun inner() { ... } }'
TEST_CASES["GR-003_compliant"]='@Service class GoodService(private val facade: DomainFacade) { fun outer() = facade.execute() }'
TEST_CASES["GR-003_regex"]='AI_JUDGMENT'

# GR-004: Lambda hell (AI judgment)
TEST_CASES["GR-004_violation"]='list.map { x -> if (x > 0) { process(x); if (x > 10) { handle(x) } else { skip() } } }'
TEST_CASES["GR-004_compliant"]='list.map { x -> processItem(x) }'
TEST_CASES["GR-004_regex"]='AI_JUDGMENT'

# GR-005: Optional chaining (AI judgment)
TEST_CASES["GR-005_violation"]='val value = map.get(key); if (value != null) { return value } else { return default }'
TEST_CASES["GR-005_compliant"]='return Optional.ofNullable(map[key]).orElse(default)'
TEST_CASES["GR-005_regex"]='AI_JUDGMENT'

# GR-RESILIENCE-001: Circuit Breaker (CHANGED: AI judgment - cannot distinguish good/bad with regex)
TEST_CASES["GR-RESILIENCE-001_violation"]='@CircuitBreaker(name = "api") fun callApi() = client.get() // missing fallback'
TEST_CASES["GR-RESILIENCE-001_compliant"]='@CircuitBreaker(name = "nexonApi", fallbackMethod = "fallback") fun callApi() = client.get()'
TEST_CASES["GR-RESILIENCE-001_regex"]='AI_JUDGMENT'

# GR-RESILIENCE-002: Marker interface (AI judgment)
TEST_CASES["GR-RESILIENCE-002_violation"]='class CustomException(message: String) : RuntimeException(message)'
TEST_CASES["GR-RESILIENCE-002_compliant"]='class CustomException(message: String) : ClientBaseException(ErrorCode.CUSTOM_ERROR)'
TEST_CASES["GR-RESILIENCE-002_regex"]='AI_JUDGMENT'

# GR-006: Deprecated API
TEST_CASES["GR-006_violation"]='@Deprecated("Use newMethod") fun oldMethod() = ...'
TEST_CASES["GR-006_compliant"]='fun newMethod() = restClient.get()'
TEST_CASES["GR-006_regex"]='@Deprecated|@deprecated'

# GR-TEST-001: Thread.sleep
TEST_CASES["GR-TEST-001_violation"]='Thread.sleep(1000)'
TEST_CASES["GR-TEST-001_compliant"]='await().atMost(1, SECONDS).until { condition() }'
TEST_CASES["GR-TEST-001_regex"]='Thread\.sleep\s*\('

# GR-TEST-002: awaitTermination
TEST_CASES["GR-TEST-002_violation"]='executorService.shutdown()'
TEST_CASES["GR-TEST-002_compliant"]='executorService.shutdown(); executorService.awaitTermination(10, SECONDS)'
TEST_CASES["GR-TEST-002_regex"]='executorService\.shutdown\(\)(?!.*awaitTermination)'

# GR-TEST-003: @DirtiesContext
TEST_CASES["GR-TEST-003_violation"]='@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD) fun test() = ...'
TEST_CASES["GR-TEST-003_compliant"]='@BeforeEach fun setUp() { repository.deleteAll() }'
TEST_CASES["GR-TEST-003_regex"]='@DirtiesContext\s*\('

# GR-TEST-004: Testcontainers
TEST_CASES["GR-TEST-004_violation"]='@Test fun testRedis() { redisTemplate.opsForValue().set("key", "value") }'
TEST_CASES["GR-TEST-004_compliant"]='@Testcontainers class RedisTest { @Container val redis = ... }'
TEST_CASES["GR-TEST-004_regex"]='@Test.*redisTemplate\.|@Test.*restTemplate\.|@Test.*@Redis\.'

# GR-TEST-005: Clock injection
TEST_CASES["GR-TEST-005_violation"]='val now = LocalDateTime.now()'
TEST_CASES["GR-TEST-005_compliant"]='val now = LocalDateTime.now(clock)'
TEST_CASES["GR-TEST-005_regex"]='LocalDate\.now\(\)|LocalDateTime\.now\(\)|Clock\.system'

# GR-TEST-006: Random ID
TEST_CASES["GR-TEST-006_violation"]='val id = UUID.randomUUID()'
TEST_CASES["GR-TEST-006_compliant"]='val id = idSupplier.get()'
TEST_CASES["GR-TEST-006_regex"]='UUID\.randomUUID\(\)|Math\.random\(\)|Random\(\)'

# GR-TEST-007: @Transactional multithread
TEST_CASES["GR-TEST-007_violation"]='@Transactional fun test() = executorService.submit { ... }'
TEST_CASES["GR-TEST-007_compliant"]='fun test() { // non-transactional executorService.submit { ... } }'
TEST_CASES["GR-TEST-007_regex"]='@Transactional.*(?:executorService|ExecutorService|parallelStream|CompletableFuture)'

# GR-ARCH-003: Stateless
TEST_CASES["GR-ARCH-003_violation"]='@Autowired lateinit var session: HttpSession'
TEST_CASES["GR-ARCH-003_compliant"]='@Autowired lateinit var redisTemplate: RedisTemplate<String, Any>'
TEST_CASES["GR-ARCH-003_regex"]='HttpSession|@SessionScope|@SessionAttributes'

# GR-ARCH-003-2: Static mutable (FIXED: Kotlin companion object)
TEST_CASES["GR-ARCH-003-2_violation"]='companion object { val cache = mutableMapOf<String, Any>() }'
TEST_CASES["GR-ARCH-003-2_compliant"]='@Autowired lateinit var distributedCache: RedisCache'
TEST_CASES["GR-ARCH-003-2_regex"]='(?:companion\s+object\s*\{[^}]*val\s+\w+\s*=\s*(?:mutableMapOf|mutableListOf|mutableSetOf|ConcurrentHashMap)|static\s+(?:val|var|final\s+)?(?:Map|Set|List|Collection|ConcurrentHashMap|mutableMap|mutableSet|mutableListOf))'

# GR-ARCH-007: JPA IDENTITY
TEST_CASES["GR-ARCH-007_violation"]='@Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long'
TEST_CASES["GR-ARCH-007_compliant"]='@Id val id: Long = idGenerator.next() // JDBC Batch compatible'
TEST_CASES["GR-ARCH-007_regex"]='@GeneratedValue\s*\(\s*strategy\s*=\s*GenerationType\.IDENTITY'

# GR-ARCH-010: V4 to V2 call
TEST_CASES["GR-ARCH-010_violation"]='private val v2Service: V2CalculatorService'
TEST_CASES["GR-ARCH-010_compliant"]='private val calculator: V4Calculator'
TEST_CASES["GR-ARCH-010_regex"]='(?:private|val|var)\s+\w+\s+v2Service|v2Service\.'

# GR-ARCH-015: Synchronous drain
TEST_CASES["GR-ARCH-015_violation"]='buffer.drain()'
TEST_CASES["GR-ARCH-015_compliant"]='// Use @Scheduled async drain instead'
TEST_CASES["GR-ARCH-015_regex"]='\.drain\s*\('

# GR-ARCH-005: fixedRate scheduler
TEST_CASES["GR-ARCH-005_violation"]='@Scheduled(fixedRate = 1000) fun scheduled() = ...'
TEST_CASES["GR-ARCH-005_compliant"]='@Scheduled(fixedDelay = 1000) fun scheduled() = ...'
TEST_CASES["GR-ARCH-005_regex"]='@Scheduled\s*\(\s*fixedRate\s*='

# GR-ARCH-001: TieredCache (FIXED: include shorthand form)
TEST_CASES["GR-ARCH-001_violation"]='@Cacheable("users") fun getUser(id: Long) = ...'
TEST_CASES["GR-ARCH-001_compliant"]='fun getUser(id: Long) = tieredCache.get(id) { repository.findById(id) }'
TEST_CASES["GR-ARCH-001_regex"]='@Cacheable\s*\(|@CacheEvict|@CachePut'

# GR-ARCH-002: Single-flight
TEST_CASES["GR-ARCH-002_violation"]='cache.computeIfAbsent(key) { expensiveOperation() }'
TEST_CASES["GR-ARCH-002_compliant"]='singleFlightExecutor.execute(key) { expensiveOperation() }'
TEST_CASES["GR-ARCH-002_regex"]='computeIfAbsent\s*\(|getOrDefault\s*\('

# GR-STYLE-001: FQCN (FIXED: Kotlin FQCN without 'new')
TEST_CASES["GR-STYLE-001_violation"]='val list = java.util.ArrayList<String>()'
TEST_CASES["GR-STYLE-001_compliant"]='import java.util.ArrayList; val list = ArrayList<String>()'
TEST_CASES["GR-STYLE-001_regex"]='(?:java|javax|org|kotlin)\.[a-zA-Z]+\.[A-Z][a-zA-Z0-9]*(?:<[^>]+>)?\s*\(\s*\)'

# GR-CACHE-001: TieredCache SingleFlight (AI judgment - no regex)
TEST_CASES["GR-CACHE-001_violation"]='fun get(key: String) = l1Cache.get(key) ?: l2Cache.get(key)'
TEST_CASES["GR-CACHE-001_compliant"]='fun get(key: String) = singleFlight.execute(key) { tieredCache.get(key) { load() } }'
TEST_CASES["GR-CACHE-001_regex"]='AI_JUDGMENT'

# GR-CACHE-002: Follower timeout (AI judgment)
TEST_CASES["GR-CACHE-002_violation"]='future.get() // no timeout'
TEST_CASES["GR-CACHE-002_compliant"]='future.orTimeout(5, SECONDS)'
TEST_CASES["GR-CACHE-002_regex"]='AI_JUDGMENT'

# GR-CACHE-003: Cache configuration (AI judgment)
TEST_CASES["GR-CACHE-003_violation"]='Caffeine.newBuilder().build() // no limits'
TEST_CASES["GR-CACHE-003_compliant"]='Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(10, MINUTES).build()'
TEST_CASES["GR-CACHE-003_regex"]='AI_JUDGMENT'

# GR-CACHE-004: Graceful degradation (AI judgment)
TEST_CASES["GR-CACHE-004_violation"]='redis.get(key) // throws on failure'
TEST_CASES["GR-CACHE-004_compliant"]='try { redis.get(key) } catch (e: Exception) { fallbackToDb(key) }'
TEST_CASES["GR-CACHE-004_regex"]='AI_JUDGMENT'

# GR-CACHE-005: Redis hash tag (AI judgment)
TEST_CASES["GR-CACHE-005_violation"]='EVALSHA script with keys [key1, key2] // CROSSSLOT risk'
TEST_CASES["GR-CACHE-005_compliant"]='EVALSHA script with keys [{user:1}key1, {user:1}key2]'
TEST_CASES["GR-CACHE-005_regex"]='AI_JUDGMENT'

# GR-LOGIC-001: LogicExecutor patterns (AI judgment)
TEST_CASES["GR-LOGIC-001_violation"]='try { repository.save(entity) } catch (e: Exception) { log.error(e) }'
TEST_CASES["GR-LOGIC-001_compliant"]='executor.execute({ repository.save(entity) }, TaskContext.of("Domain", "Save", id))'
TEST_CASES["GR-LOGIC-001_regex"]='AI_JUDGMENT'

# GR-LOGIC-002: Lambda 3-line rule (AI judgment)
TEST_CASES["GR-LOGIC-002_violation"]='list.filter { x -> if (x.isValid) { process(x); log.info("done") } else { log.warn("skip") } }'
TEST_CASES["GR-LOGIC-002_compliant"]='list.filter { x -> processIfValid(x) }'
TEST_CASES["GR-LOGIC-002_regex"]='AI_JUDGMENT'

# GR-AOP-001: Facade pattern (AI judgment)
TEST_CASES["GR-AOP-001_violation"]='@Service class Service { @Transactional fun outer() { inner() } fun inner() = ... }'
TEST_CASES["GR-AOP-001_compliant"]='@Service class Facade(private val service: Service) { @Transactional fun execute() = service.inner() }'
TEST_CASES["GR-AOP-001_regex"]='AI_JUDGMENT'

# GR-AOP-002: Filter bean registration (AI judgment)
TEST_CASES["GR-AOP-002_violation"]='@Component class MyFilter : OncePerRequestFilter() { ... }'
TEST_CASES["GR-AOP-002_compliant"]='@Bean fun myFilter() = MyFilter(); @Bean fun myFilterRegistration() = FilterRegistrationBean<MyFilter>().apply { enabled = false }'
TEST_CASES["GR-AOP-002_regex"]='AI_JUDGMENT'

# GR-AOP-003: SecurityContext thread-safe (AI judgment)
TEST_CASES["GR-AOP-003_violation"]='val context = SecurityContextHolder.getContext() // reused'
TEST_CASES["GR-AOP-003_compliant"]='val context = SecurityContextHolder.createEmptyContext()'
TEST_CASES["GR-AOP-003_regex"]='AI_JUDGMENT'

# GR-AOP-004: Sensitive data masking (AI judgment)
TEST_CASES["GR-AOP-004_violation"]='data class ApiKey(val key: String)'
TEST_CASES["GR-AOP-004_compliant"]='data class ApiKey(val key: String) { override fun toString() = "ApiKey(****)" }'
TEST_CASES["GR-AOP-004_regex"]='AI_JUDGMENT'

# GR-AOP-005: API Key JWT (AI judgment)
TEST_CASES["GR-AOP-005_violation"]='jwt.claims["apiKey"] = apiKey'
TEST_CASES["GR-AOP-005_compliant"]='redisTemplate.opsForValue().set("session:$id", apiKey)'
TEST_CASES["GR-AOP-005_regex"]='AI_JUDGMENT'

# GR-AOP-006: Security headers (AI judgment)
TEST_CASES["GR-AOP-006_violation"]='http.headers().frameOptions().deny()'
TEST_CASES["GR-AOP-006_compliant"]='http.headers { headers -> headers.frameOptions { it.deny() } }'
TEST_CASES["GR-AOP-006_regex"]='AI_JUDGMENT'

# GR-AOP-007: Swagger permitAll (AI judgment)
TEST_CASES["GR-AOP-007_violation"]='authorizeHttpRequests { it.anyRequest().authenticated() }'
TEST_CASES["GR-AOP-007_compliant"]='authorizeHttpRequests { it.requestMatchers("/swagger-ui/**").permitAll() }'
TEST_CASES["GR-AOP-007_regex"]='AI_JUDGMENT'

# GR-AOP-008: Logging level (AI judgment)
TEST_CASES["GR-AOP-008_violation"]='log.error("User not found: $id") // should be warn'
TEST_CASES["GR-AOP-008_compliant"]='log.warn("User not found: $id")'
TEST_CASES["GR-AOP-008_regex"]='AI_JUDGMENT'

# Test function
test_pattern() {
    local id="$1"
    local violation="${TEST_CASES[${id}_violation]}"
    local compliant="${TEST_CASES[${id}_compliant]}"
    local regex="${TEST_CASES[${id}_regex]}"
    local description="$2"

    TOTAL=$((TOTAL + 1))

    local violation_blocked="N/A"
    local compliant_passed="N/A"
    local result=""

    if [[ "$regex" == "AI_JUDGMENT" ]]; then
        # AI judgment required - mark for manual review
        AI_JUDGMENT=$((AI_JUDGMENT + 1))
        violation_blocked="AI"
        compliant_passed="AI"
        result="AI_JUDGMENT"
        echo -e "${YELLOW}[AI]${NC} $id - Requires AI judgment"
    else
        # Test regex against violation code
        if echo "$violation" | grep -qP "$regex" 2>/dev/null; then
            violation_blocked="YES"
        else
            violation_blocked="NO"
            LAYER1_FAIL=$((LAYER1_FAIL + 1))
        fi

        # Test regex against compliant code
        if echo "$compliant" | grep -qP "$regex" 2>/dev/null; then
            compliant_passed="NO"
            FALSE_POSITIVE=$((FALSE_POSITIVE + 1))
        else
            compliant_passed="YES"
            if [[ "$violation_blocked" == "YES" ]]; then
                LAYER1_PASS=$((LAYER1_PASS + 1))
            fi
        fi

        if [[ "$violation_blocked" == "YES" && "$compliant_passed" == "YES" ]]; then
            result="PASS"
            echo -e "${GREEN}[PASS]${NC} $id - $description"
        elif [[ "$violation_blocked" == "NO" ]]; then
            result="FAIL"
            echo -e "${RED}[FAIL]${NC} $id - Violation not detected"
        elif [[ "$compliant_passed" == "NO" ]]; then
            result="FALSE_POSITIVE"
            echo -e "${YELLOW}[FP]${NC} $id - False positive detected"
        fi
    fi

    # Append to report
    echo "| $id | $description | $violation_blocked | $compliant_passed | $result |" >> "$REPORT_FILE"
}

echo ""
echo "Running tests..."
echo ""

# Run all tests
test_pattern "GR-001" "try-catch prohibited"
test_pattern "GR-002" "RuntimeException prohibited"
test_pattern "GR-003" "AOP self-invocation prohibited"
test_pattern "GR-004" "Lambda hell prohibited"
test_pattern "GR-005" "Optional chaining required"
test_pattern "GR-RESILIENCE-001" "Circuit Breaker required"
test_pattern "GR-RESILIENCE-002" "Marker interface required"
test_pattern "GR-006" "Deprecated API prohibited"
test_pattern "GR-TEST-001" "Thread.sleep prohibited"
test_pattern "GR-TEST-002" "awaitTermination required"
test_pattern "GR-TEST-003" "@DirtiesContext overuse"
test_pattern "GR-TEST-004" "Testcontainers required"
test_pattern "GR-TEST-005" "Clock injection required"
test_pattern "GR-TEST-006" "Random ID injection required"
test_pattern "GR-TEST-007" "@Transactional multithread"
test_pattern "GR-ARCH-003" "Stateless violation"
test_pattern "GR-ARCH-003-2" "Static mutable prohibited"
test_pattern "GR-ARCH-007" "JPA IDENTITY batch disable"
test_pattern "GR-ARCH-010" "V4 to V2 call prohibited"
test_pattern "GR-ARCH-015" "Synchronous drain prohibited"
test_pattern "GR-ARCH-005" "fixedRate scheduler prohibited"
test_pattern "GR-ARCH-001" "TieredCache required"
test_pattern "GR-ARCH-002" "Single-flight required"
test_pattern "GR-STYLE-001" "FQCN discouraged"
test_pattern "GR-CACHE-001" "TieredCache SingleFlight"
test_pattern "GR-CACHE-002" "Follower timeout isolation"
test_pattern "GR-CACHE-003" "Cache configuration"
test_pattern "GR-CACHE-004" "Graceful degradation"
test_pattern "GR-CACHE-005" "Redis hash tag"
test_pattern "GR-LOGIC-001" "LogicExecutor patterns"
test_pattern "GR-LOGIC-002" "Lambda 3-line rule"
test_pattern "GR-AOP-001" "Facade pattern"
test_pattern "GR-AOP-002" "Filter bean registration"
test_pattern "GR-AOP-003" "SecurityContext thread-safe"
test_pattern "GR-AOP-004" "Sensitive data masking"
test_pattern "GR-AOP-005" "API Key JWT prohibition"
test_pattern "GR-AOP-006" "Security headers"
test_pattern "GR-AOP-007" "Swagger permitAll"
test_pattern "GR-AOP-008" "Logging level separation"

# Generate summary
echo "" >> "$REPORT_FILE"
echo "## Summary" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "| Category | Count |" >> "$REPORT_FILE"
echo "|----------|-------|" >> "$REPORT_FILE"
echo "| **Total Patterns** | $TOTAL |" >> "$REPORT_FILE"
echo "| Layer 1 PASS (regex works) | $LAYER1_PASS |" >> "$REPORT_FILE"
echo "| Layer 1 FAIL (regex miss) | $LAYER1_FAIL |" >> "$REPORT_FILE"
echo "| AI Judgment Required | $AI_JUDGMENT |" >> "$REPORT_FILE"
echo "| False Positive | $FALSE_POSITIVE |" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

echo "" >> "$REPORT_FILE"
echo "## Classification" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "### Layer 1 (Regex Detection) - Working" >> "$REPORT_FILE"
echo "Patterns where regex correctly detects violations and allows compliant code." >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "### Layer 2 (AI Judgment Required)" >> "$REPORT_FILE"
echo "Patterns requiring semantic analysis - regex insufficient." >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "### False Positives" >> "$REPORT_FILE"
echo "Patterns that incorrectly flag compliant code." >> "$REPORT_FILE"

# Cleanup test directory
rm -rf "$TEST_DIR"

echo ""
echo "=========================================="
echo "Test Complete"
echo "=========================================="
echo ""
echo "Summary:"
echo "  Total: $TOTAL"
echo "  Layer 1 PASS: $LAYER1_PASS"
echo "  Layer 1 FAIL: $LAYER1_FAIL"
echo "  AI Judgment: $AI_JUDGMENT"
echo "  False Positive: $FALSE_POSITIVE"
echo ""
echo "Report saved to: $REPORT_FILE"
