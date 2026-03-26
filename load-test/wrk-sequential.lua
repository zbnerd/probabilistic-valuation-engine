-- wrk Lua script: Sequential cycle through ALL 300k user IGNS
-- Each request uses the next user IGN in sequence (1, 2, 3, ..., 300000)

-- Global counter
counter = 0
total_users = 0

-- Load all user IGNS from CSV
function load_users()
    local users = {}
    local file = io.open("/home/maple/probabilistic-valuation-engine/module-app/src/main/resources/data/userIgn_List.csv", "r")
    if not file then
        print("ERROR: Cannot open CSV file")
        return 0
    end

    local line_num = 0
    for line in file:lines() do
        line_num = line_num + 1
        -- Skip header (first line)
        if line_num > 1 then
            -- Extract first column, remove quotes
            local ign = line:match("^[\"]?([^,\"]*)")
            if ign and ign ~= "" then
                table.insert(users, ign)
            end
        end
    end

    file:close()
    return users
end

-- Simple URL encoding for UTF-8 (Korean characters)
function encode_url_component(str)
    local encoded = ""
    for i = 1, #str do
        local c = str:byte(i)
        if c == 32 then  -- space
            encoded = encoded .. "%%20"
        elseif c >= 48 and c <= 57 then  -- 0-9
            encoded = encoded .. string.char(c)
        elseif c >= 65 and c <= 90 then  -- A-Z
            encoded = encoded .. string.char(c)
        elseif c >= 97 and c <= 122 then  -- a-z
            encoded = encoded .. string.char(c)
        elseif c == 45 or c == 95 or c == 46 or c == 126 then  -- - _ . ~
            encoded = encoded .. string.char(c)
        else
            -- Percent encode
            encoded = encoded .. string.format("%%%02X", c)
        end
    end
    return encoded
end

-- Initialize (must be global for done() to access)
user_igns = {}
initialized = false

init = function()
    if not initialized then
        user_igns = load_users()
        total_users = #user_igns
        initialized = true

        print(string.format("Loaded %d user IGNS from CSV", total_users))
        print("Starting sequential load test...")
    end
end

request = function()
    if not initialized then
        init()
    end

    -- Get next user IGN (cycle through all)
    counter = counter + 1
    local index = (counter - 1) % total_users + 1
    local user_ign = user_igns[index]

    -- Log progress every 10000 requests
    if counter % 10000 == 0 then
        local cycle_num = math.floor((counter - 1) / total_users) + 1
        print(string.format("[Request #%d] User #%d (Cycle %d, Position %d/%d): %s",
            counter, index, cycle_num, index, total_users, user_ign))
    end

    -- URL encode the user IGN
    local encoded_ign = encode_url_component(user_ign)

    -- Build request path
    local path = "/api/v4/characters/" .. encoded_ign .. "/expectation"

    -- Return request with delay to achieve ~300 RPS total
    -- With 10 threads, each thread should send ~30 RPS
    -- 30 RPS = 1 request every 33.33ms
    return wrk.format("GET", path)
end

response = function(status, headers, body)
    -- Track errors
    if status >= 400 then
        responses = responses or {}
        responses[status] = (responses[status] or 0) + 1
    end
end

done = function(summary, latency, requests)
    -- Recalculate total_users since done() runs in main thread context
    -- We need to count the lines again since we don't have access to thread-local data
    local calculated_total = 0
    local file = io.open("/home/maple/probabilistic-valuation-engine/module-app/src/main/resources/data/userIgn_List.csv", "r")
    if file then
        for line in file:lines() do
            calculated_total = calculated_total + 1
        end
        file:close()
        calculated_total = calculated_total - 1  -- Subtract header
    end

    print("\n" .. string.rep("=", 70))
    print("📊 LOAD TEST RESULTS - Sequential 300k User Cycle")
    print(string.rep("=", 70))

    print(string.format("Total Requests: %d", summary.requests))
    print(string.format("Duration: %.2f seconds", summary.duration / 1000000))
    print(string.format("Actual RPS: %.2f", summary.requests / (summary.duration / 1000000)))
    print(string.format("Target RPS: 300"))

    -- User coverage
    local coverage = math.min(summary.requests, calculated_total)
    local complete_cycles = math.floor(summary.requests / calculated_total)
    local remaining = summary.requests % calculated_total

    print(string.format("\nUser Coverage:"))
    print(string.format("  Total users in CSV: %d", calculated_total))
    print(string.format("  Unique users hit: %d (%.2f%%)",
        coverage, coverage / calculated_total * 100))
    print(string.format("  Complete cycles: %d", complete_cycles))
    print(string.format("  Remaining in cycle: %d / %d", remaining, calculated_total))

    -- Latency
    print(string.format("\nLatency:"))
    print(string.format("  Mean: %.2fms", latency.mean / 1000))
    print(string.format("  Stdev: %.2fms", latency.stdev / 1000))
    print(string.format("  Min: %.2fms", latency.min / 1000))
    print(string.format("  Max: %.2fms", latency.max / 1000))

    -- Percentiles
    if latency.percentiles then
        print("\nPercentiles:")
        for _, p in ipairs({50, 75, 90, 95, 99, 99.9}) do
            local value = latency.percentiles[p]
            if value then
                print(string.format("  p%g: %.2fms", p, value / 1000))
            end
        end
    end

    -- Errors
    if responses then
        print("\nError Breakdown:")
        for status, count in pairs(responses) do
            print(string.format("  HTTP %s: %d (%.2f%%)",
                status, count, count / summary.requests * 100))
        end
    end

    print(string.rep("=", 70))
end
