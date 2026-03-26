-- wrk Lua script for V4 Expectation API Load Test
-- Issue #562: Load Testing + Optimization
-- Enhanced with response metrics: body size, content-type, cache headers

-- ============================================
-- Test Configuration
-- ============================================
local TEST_ENDPOINT = "/api/v4/characters"
local TEST_METHOD = "GET"

-- 테스트용 캐릭터 IGN 목록 (URL 인코딩된 형태)
local igns = {
    "%EC%95%84%EB%8D%B8",           -- 아델
    "%EA%B0%95%EC%9D%80%ED%98%B8",  -- 강은호
    "%EC%A7%84%EA%B2%A9%EC%BA%90%EB%84%8C"  -- 진격캐넌
}

-- ============================================
-- Thread-local counters (wrk multi-threaded)
-- ============================================
local counter = 0

function setup(thread)
    thread:set("id", counter)
    counter = counter + 1
end

function init(args)
    requests = 0
    responses = 0
    errors = 0
    total_bytes = 0
    status_200 = 0
    status_4xx = 0
    status_5xx = 0
    cache_hits = 0
    cache_misses = 0

    local thread_id = wrk.thread:get("id")
    local ign_index = (thread_id % #igns) + 1
    current_ign = igns[ign_index]
end

function request()
    requests = requests + 1
    local path = TEST_ENDPOINT .. "/" .. current_ign .. "/expectation"
    return wrk.format(TEST_METHOD, path, {
        ["Accept"] = "application/json",
        ["Accept-Encoding"] = "gzip"
    })
end

function response(status, headers, body)
    responses = responses + 1

    -- Response body size
    total_bytes = total_bytes + #body

    -- Status code distribution
    if status == 200 then
        status_200 = status_200 + 1
    elseif status >= 400 and status < 500 then
        status_4xx = status_4xx + 1
        errors = errors + 1
    elseif status >= 500 then
        status_5xx = status_5xx + 1
        errors = errors + 1
    end

    -- Cache hit detection (X-Cache header)
    -- Note: If server returns X-Cache header, use it
    -- Otherwise, infer from response time (< 10ms likely cache hit)
    local cache_header = headers["X-Cache"] or headers["x-cache"]
    if cache_header then
        if string.lower(cache_header) == "hit" then
            cache_hits = cache_hits + 1
        else
            cache_misses = cache_misses + 1
        end
    end
end

-- ============================================
-- Aggregate results from all threads
-- ============================================
local function aggregate_results(summary)
    -- wrk doesn't support thread data aggregation natively
    -- Using summary.bytes for total response size
    return {
        total_bytes = summary.bytes,
        requests = summary.requests
    }
end

function done(summary, latency, requests)
    -- Calculate metrics
    local duration_sec = summary.duration / 1000000
    local rps = summary.requests / duration_sec
    local avg_response_size = summary.bytes / summary.requests
    local throughput_kbps = (summary.bytes / duration_sec) / 1024

    -- Calculate cache hit rate (if headers were available)
    local total_cache_ops = cache_hits + cache_misses
    local cache_hit_rate = 0
    if total_cache_ops > 0 then
        cache_hit_rate = (cache_hits / total_cache_ops) * 100
    end

    -- Output report
    io.write("\n")
    io.write("╔══════════════════════════════════════════════════════════════╗\n")
    io.write("║       V4 Expectation API Load Test Results                   ║\n")
    io.write("║       Issue #562: Load Testing + Optimization                ║\n")
    io.write("╚══════════════════════════════════════════════════════════════╝\n")
    io.write("\n")

    -- Test Target
    io.write("┌─────────────────────────────────────────────────────────────┐\n")
    io.write("│ TEST TARGET                                                  │\n")
    io.write("├─────────────────────────────────────────────────────────────┤\n")
    io.write(string.format("│ Method:          %-42s │\n", TEST_METHOD))
    io.write(string.format("│ Endpoint:        %-42s │\n", TEST_ENDPOINT .. "/{userIgn}/expectation"))
    io.write(string.format("│ Content-Type:    %-42s │\n", "application/json"))
    io.write("└─────────────────────────────────────────────────────────────┘\n")
    io.write("\n")

    -- Throughput
    io.write("┌─────────────────────────────────────────────────────────────┐\n")
    io.write("│ THROUGHPUT                                                   │\n")
    io.write("├─────────────────────────────────────────────────────────────┤\n")
    io.write(string.format("│ Duration:        %-42s │\n", string.format("%.2f s", duration_sec)))
    io.write(string.format("│ Total Requests:  %-42s │\n", string.format("%d", summary.requests)))
    io.write(string.format("│ Requests/sec:    %-42s │\n", string.format("%.2f", rps)))
    io.write(string.format("│ Throughput:      %-42s │\n", string.format("%.2f KB/s", throughput_kbps)))
    io.write("└─────────────────────────────────────────────────────────────┘\n")
    io.write("\n")

    -- Response Size
    io.write("┌─────────────────────────────────────────────────────────────┐\n")
    io.write("│ RESPONSE SIZE                                                │\n")
    io.write("├─────────────────────────────────────────────────────────────┤\n")
    io.write(string.format("│ Total Bytes:     %-42s │\n", string.format("%.2f MB (%d bytes)", summary.bytes / 1024 / 1024, summary.bytes)))
    io.write(string.format("│ Avg Size:        %-42s │\n", string.format("%.2f bytes (%.2f KB)", avg_response_size, avg_response_size / 1024)))
    io.write("└─────────────────────────────────────────────────────────────┘\n")
    io.write("\n")

    -- Latency Distribution
    io.write("┌─────────────────────────────────────────────────────────────┐\n")
    io.write("│ LATENCY DISTRIBUTION                                         │\n")
    io.write("├─────────────────────────────────────────────────────────────┤\n")
    io.write(string.format("│ 50%% (Median):   %-42s │\n", string.format("%.2f ms", latency:percentile(50) / 1000)))
    io.write(string.format("│ 75%%:            %-42s │\n", string.format("%.2f ms", latency:percentile(75) / 1000)))
    io.write(string.format("│ 90%%:            %-42s │\n", string.format("%.2f ms", latency:percentile(90) / 1000)))
    io.write(string.format("│ 95%%:            %-42s │\n", string.format("%.2f ms", latency:percentile(95) / 1000)))
    io.write(string.format("│ 99%%:            %-42s │\n", string.format("%.2f ms", latency:percentile(99) / 1000)))
    io.write(string.format("│ Max:            %-42s │\n", string.format("%.2f ms", latency.max / 1000)))
    io.write("└─────────────────────────────────────────────────────────────┘\n")
    io.write("\n")

    -- Status Codes
    io.write("┌─────────────────────────────────────────────────────────────┐\n")
    io.write("│ STATUS CODES                                                 │\n")
    io.write("├─────────────────────────────────────────────────────────────┤\n")
    io.write(string.format("│ 200 OK:         %-42s │\n", string.format("%d (%.2f%%)", status_200, (status_200 / responses) * 100)))
    if status_4xx > 0 then
        io.write(string.format("│ 4xx Errors:     %-42s │\n", string.format("%d", status_4xx)))
    end
    if status_5xx > 0 then
        io.write(string.format("│ 5xx Errors:     %-42s │\n", string.format("%d", status_5xx)))
    end
    io.write("└─────────────────────────────────────────────────────────────┘\n")
    io.write("\n")

    -- Errors
    local total_errors = summary.errors.connect + summary.errors.read +
                         summary.errors.write + summary.errors.timeout + summary.errors.status
    if total_errors > 0 then
        io.write("┌─────────────────────────────────────────────────────────────┐\n")
        io.write("│ ERRORS                                                       │\n")
        io.write("├─────────────────────────────────────────────────────────────┤\n")
        io.write(string.format("│ Connect:        %-42s │\n", summary.errors.connect))
        io.write(string.format("│ Read:           %-42s │\n", summary.errors.read))
        io.write(string.format("│ Write:          %-42s │\n", summary.errors.write))
        io.write(string.format("│ Timeout:        %-42s │\n", summary.errors.timeout))
        io.write(string.format("│ Status:         %-42s │\n", summary.errors.status))
        io.write(string.format("│ Error Rate:     %-42s │\n", string.format("%.2f%%", (total_errors / summary.requests) * 100)))
        io.write("└─────────────────────────────────────────────────────────────┘\n")
        io.write("\n")
    end

    -- Cache Stats (if available)
    if total_cache_ops > 0 then
        io.write("┌─────────────────────────────────────────────────────────────┐\n")
        io.write("│ CACHE STATISTICS                                             │\n")
        io.write("├─────────────────────────────────────────────────────────────┤\n")
        io.write(string.format("│ Cache Hits:     %-42s │\n", string.format("%d", cache_hits)))
        io.write(string.format("│ Cache Misses:   %-42s │\n", string.format("%d", cache_misses)))
        io.write(string.format("│ Hit Rate:       %-42s │\n", string.format("%.2f%%", cache_hit_rate)))
        io.write("└─────────────────────────────────────────────────────────────┘\n")
        io.write("\n")
    end

    -- Acceptance Criteria Check
    local p99_ms = latency:percentile(99) / 1000
    local error_rate = (total_errors / summary.requests) * 100

    io.write("┌─────────────────────────────────────────────────────────────┐\n")
    io.write("│ ACCEPTANCE CRITERIA (Issue #562)                             │\n")
    io.write("├─────────────────────────────────────────────────────────────┤\n")
    io.write(string.format("│ p99 < 200ms:    %-42s │\n", p99_ms < 200 and "✅ PASS" or "❌ FAIL"))
    io.write(string.format("│ Error < 1%%:     %-42s │\n", error_rate < 1 and "✅ PASS" or "❌ FAIL"))
    io.write("└─────────────────────────────────────────────────────────────┘\n")
    io.write("\n")
end
