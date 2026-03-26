-- Diagnostic wrk script: Test users from different parts of the CSV
-- Samples: lines 1-20, 50000-50020, 150000-150020, 280000-280020

counter = 0
total_users = 0

-- Sample users from different ranges
sample_users = {}

-- Load specific ranges
function load_samples()
    local ranges = {
        {1, 20},
        {50000, 50020},
        {150000, 150020},
        {280000, 280020}
    }

    for _, range in ipairs(ranges) do
        local start_line = range[1]
        local end_line = range[2]

        local file = io.open("/home/maple/probabilistic-valuation-engine/module-app/src/main/resources/data/userIgn_List.csv", "r")
        if not file then
            print("ERROR: Cannot open CSV file")
            return 0
        end

        local line_num = 0
        for line in file:lines() do
            line_num = line_num + 1
            if line_num >= start_line and line_num <= end_line then
                -- Skip header
                if line_num > 1 then
                    local ign = line:match("^[\"]?([^,\"]*)")
                    if ign and ign ~= "" then
                        table.insert(sample_users, {ign = ign, line = line_num})
                    end
                end
            end
            if line_num > end_line then
                break
            end
        end

        file:close()
    end

    return #sample_users
end

-- URL encoding
function encode(str)
    local encoded = ""
    for i = 1, #str do
        local c = str:byte(i)
        if c >= 48 and c <= 57 or c >= 65 and c <= 90 or c >= 97 and c <= 122 then
            encoded = encoded .. string.char(c)
        else
            encoded = encoded .. string.format("%%%02X", c)
        end
    end
    return encoded
end

init = function()
    if total_users == 0 then
        total_users = load_samples()
        print("Loaded " .. total_users .. " sample users from 4 ranges")
    end
end

request = function()
    if total_users == 0 then
        init()
    end

    counter = counter + 1
    local index = (counter - 1) % total_users + 1
    local user_data = sample_users[index]
    local user_ign = user_data.ign
    local line_num = user_data.line

    if counter % 100 == 0 then
        print("[Request #" .. counter .. "] Line " .. line_num .. ": " .. user_ign)
    end

    local encoded = encode(user_ign)
    local path = "/api/v4/characters/" .. encoded .. "/expectation"

    return wrk.format("GET", path)
end

response = function(status, headers, body)
    if status >= 400 then
        responses = responses or {}
        responses[status] = (responses[status] or 0) + 1

        -- Log the failing request
        if counter % 10 == 0 then
            local index = (counter - 1) % total_users + 1
            local user_data = sample_users[index]
            print("[ERROR] Request #" .. counter .. " Line " .. user_data.line .. " (" .. user_data.ign .. ") -> HTTP " .. status)
        end
    end
end

done = function(summary, latency, requests)
    print("\n============================================================")
    print("DIAGNOSTIC RESULTS - Samples from 4 CSV Ranges")
    print("============================================================")
    print("Total Requests: " .. summary.requests)
    print("Duration: " .. (summary.duration / 1000000) .. "s")
    print("RPS: " .. (summary.requests / (summary.duration / 1000000)))

    if responses then
        print("\nError Breakdown:")
        local total_errors = 0
        for status, count in pairs(responses) do
            print("  HTTP " .. status .. ": " .. count)
            total_errors = total_errors + count
        end
        print("Total Errors: " .. total_errors)
        print("Error Rate: " .. (total_errors / summary.requests * 100) .. "%")
    end
    print("============================================================")
end
