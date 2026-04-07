-- wrk Lua script: V5 Expectation API Load Test
-- Cache hit / fan-out benchmark with 300K IGN cycling

local TEST_ENDPOINT = "/api/v5/characters"
local IGN_FILE = "/home/maple/probabilistic-valuation-engine/module-app/src/main/resources/data/userIgn_List.csv"

-- ============================================
-- URL encoding (wrk 4.1.0 doesn't have wrk.encode)
-- ============================================
local function url_encode(str)
    if str then
        str = string.gsub(str, "\n", "\r\n")
        str = string.gsub(str, "([^%w %-%_%.%~])", function(c)
            return string.format("%%%02X", string.byte(c))
        end)
        str = string.gsub(str, " ", "+")
    end
    return str
end

-- ============================================
-- Load IGNs
-- ============================================
local user_igns = {}
local total_igns = 0

local function load_igns()
    local file = io.open(IGN_FILE, "r")
    if not file then
        print("ERROR: Could not open " .. IGN_FILE)
        return 0
    end
    for line in file:lines() do
        local ign = line:match('^"?([^,"]+)"?')
        if ign and ign ~= "" and ign ~= "userIgn" then
            table.insert(user_igns, ign)
        end
    end
    file:close()
    return #user_igns
end

-- ============================================
-- Thread-local state
-- ============================================
local thread_counter = 0

function setup(thread)
    thread:set("id", thread_counter)
    thread_counter = thread_counter + 1
end

function init(args)
    if total_igns == 0 then
        total_igns = load_igns()
        print(string.format("Loaded %d IGNs", total_igns))
    end

    requests = 0
    status_200 = 0   -- Cache HIT
    status_202 = 0   -- Cache MISS (queued)
    status_4xx = 0
    status_5xx = 0
    errors = 0
    total_bytes = 0
    local_idx = 0
end

function request()
    local_idx = local_idx + 1
    local index = ((local_idx - 1) % total_igns) + 1
    local ign = user_igns[index]
    local encoded = url_encode(ign)
    local path = TEST_ENDPOINT .. "/" .. encoded .. "/expectation"

    return wrk.format("GET", path, {
        ["Accept"] = "application/json",
    })
end

function response(status, headers, body)
    requests = requests + 1
    total_bytes = total_bytes + #body

    if status == 200 then
        status_200 = status_200 + 1
    elseif status == 202 then
        status_202 = status_202 + 1
    elseif status >= 400 and status < 500 then
        status_4xx = status_4xx + 1
        errors = errors + 1
    elseif status >= 500 then
        status_5xx = status_5xx + 1
        errors = errors + 1
    end
end

function done(summary, latency, requests)
    local duration_sec = summary.duration / 1000000
    local rps = summary.requests / duration_sec
    local p50 = latency:percentile(50) / 1000
    local p75 = latency:percentile(75) / 1000
    local p90 = latency:percentile(90) / 1000
    local p95 = latency:percentile(95) / 1000
    local p99 = latency:percentile(99) / 1000
    local p_max = latency.max / 1000

    io.write("\n")
    io.write("========================================\n")
    io.write("  V5 Expectation API Load Test Results  \n")
    io.write("========================================\n")
    io.write(string.format("  Duration:       %.2f s\n", duration_sec))
    io.write(string.format("  Total Requests: %d\n", summary.requests))
    io.write(string.format("  RPS:            %.2f\n", rps))
    io.write(string.format("  Throughput:     %.2f KB/s\n", (summary.bytes / duration_sec) / 1024))
    io.write("\n")
    io.write("--- Latency ---\n")
    io.write(string.format("  p50:  %.2f ms\n", p50))
    io.write(string.format("  p75:  %.2f ms\n", p75))
    io.write(string.format("  p90:  %.2f ms\n", p90))
    io.write(string.format("  p95:  %.2f ms\n", p95))
    io.write(string.format("  p99:  %.2f ms\n", p99))
    io.write(string.format("  max:  %.2f ms\n", p_max))
    io.write("\n")
    io.write(string.format("  Non-2xx/3xx: %d\n", summary.errors.status))
    io.write("========================================\n")
end
