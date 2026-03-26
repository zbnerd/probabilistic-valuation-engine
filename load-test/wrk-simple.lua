-- Simple wrk Lua script without URL encoding issues
-- Uses English test endpoint to avoid encoding problems

counter = 0

request = function()
    counter = counter + 1

    -- Use a simple test endpoint with English characters
    local path = "/api/v4/characters/test/expectation"

    -- Log every 1000 requests
    if counter % 1000 == 0 then
        print(string.format("[Request #%d]", counter))
    end

    return wrk.format("GET", path)
end

response = function(status, headers, body)
    -- Track error responses
    if status >= 400 then
        responses = responses or {}
        responses[status] = (responses[status] or 0) + 1
    end
end

done = function(summary, latency, requests)
    print("\n" .. string.rep("=", 70))
    print("📊 LOAD TEST SUMMARY")
    print(string.rep("=", 70))
    print(string.format("Total Requests: %d", summary.requests))
    print(string.format("Duration: %.2f seconds", summary.duration / 1000000))
    print(string.format("Actual RPS: %.2f", summary.requests / (summary.duration / 1000000)))

    -- Latency statistics
    print("\nLatency Distribution:")
    print(string.format("  Mean: %.2fms", latency.mean / 1000))
    print(string.format("  Stdev: %.2fms", latency.stdev / 1000))
    print(string.format("  Max: %.2fms", latency.max / 1000))

    -- Percentiles
    if latency.percentiles then
        print("\nPercentiles:")
        for _, p in ipairs({50, 75, 90, 99, 99.9, 99.99, 100}) do
            local value = latency.percentiles[p]
            if value then
                print(string.format("  p%.2f: %.2fms", p, value / 1000))
            end
        end
    end

    if responses then
        print("\nError Breakdown:")
        for status, count in pairs(responses) do
            print(string.format("  HTTP %s: %d", status, count))
        end
    end

    print(string.rep("=", 70))
end
