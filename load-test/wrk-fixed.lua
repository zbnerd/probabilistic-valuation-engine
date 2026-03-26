-- wrk Lua script: Cycle through 300k user IGNS without wrk.encode
-- Fixed: Manual URL encoding for Korean characters

-- Global counter for request number
counter = 0
total_igns = 0

-- Initialize user IGNs array (will be loaded from file)
user_igns = {}

-- Simple URL encoding function (handles Korean UTF-8)
function urlencode(str)
    -- Convert string to hex representation
    local encoded = ""
    for i = 1, #str do
        local byte = str:byte(i)
        -- Encode characters that need encoding
        if byte >= 128 or byte == 32 or byte == 37 or byte == 38 or byte == 61 or byte == 63 then
            encoded = encoded .. string.format("%%%02X", byte)
        else
            encoded = encoded .. string.char(byte)
        end
    end
    return encoded
end

-- Load user IGNS from CSV file
function load_user_igns()
    local file = io.open("/home/maple/probabilistic-valuation-engine/module-app/src/main/resources/data/userIgn_List.csv", "r")
    if not file then
        print("ERROR: Could not open user IGN file")
        return 0
    end

    local count = 0
    for line in file:lines() do
        -- Skip empty lines and header
        local ign = line:match('^"?([^,"]+)"?')
        if ign and ign ~= "" and ign ~= "userIgn" then
            table.insert(user_igns, ign)
            count = count + 1
        end
    end

    file:close()
    print(string.format("Loaded %d user IGNS from CSV", count))
    return count
end

-- Initialize on first call
local initialized = false

init = function()
    if not initialized then
        total_igns = load_user_igns()
        initialized = true

        if total_igns == 0 then
            print("ERROR: No user IGNS loaded!")
        else
            print(string.format("Initialized: %d user IGNS", total_igns))
        end
    end
end

request = function()
    -- Initialize on first request
    init()

    -- Get next user IGN in sequence
    counter = counter + 1
    local index = (counter - 1) % total_igns + 1
    local user_ign = user_igns[index]

    -- URL encode the user IGN (Korean characters)
    local encoded_ign = urlencode(user_ign)

    -- Log every 10000th request to show progress
    if counter % 10000 == 0 then
        local cycle = math.floor((counter - 1) / total_igns) + 1
        local position_in_cycle = ((counter - 1) % total_igns) + 1
        print(string.format("[Request #%d] Cycle: %d, Position: %d/%d",
            counter, cycle, position_in_cycle, total_igns))
    end

    -- Return the request
    local path = "/api/v4/characters/" .. encoded_ign .. "/expectation"
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

    local unique_users = math.min(summary.requests, total_igns)
    local cycles = math.floor(summary.requests / total_igns)
    local remaining = summary.requests % total_igns

    print(string.format("Total Requests: %d", summary.requests))
    print(string.format("Duration: %.2f seconds", summary.duration / 1000000))
    print(string.format("Actual RPS: %.2f", summary.requests / (summary.duration / 1000000)))
    print(string.format("Target RPS: 300"))
    print(string.format("Unique Users Hit: %d / %d (%.2f%%)",
        unique_users, total_igns, unique_users / total_igns * 100))
    print(string.format("Complete Cycles: %d", cycles))
    print(string.format("Partial Cycle: %d / %d", remaining, total_igns))

    if responses then
        print("\nError Breakdown:")
        for status, count in pairs(responses) do
            print(string.format("  HTTP %s: %d (%.2f%%)", status, count, count / summary.requests * 100))
        end
    end

    print(string.rep("=", 70))
end
