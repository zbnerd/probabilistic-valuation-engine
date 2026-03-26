-- Fixed wrk Lua script - no string.format issues

-- Fixed user IGN list (first 10 for testing admission control)
user_igns = {
    "강은호", "아델", "뉴비렌붕잉", "쯔단", "고딩", "곰자몽", "테스트", "샘플", "예제", "캐릭터"
}

total_users = #user_igns

-- Simple URL encoding
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

counter = 0

request = function()
    counter = counter + 1
    local index = (counter - 1) % total_users + 1
    local user_ign = user_igns[index]

    if counter % 1000 == 0 then
        print("[Request #" .. counter .. "] User: " .. user_ign .. " (Index " .. index .. "/" .. total_users .. ")")
    end

    local encoded = encode(user_ign)
    local path = "/api/v4/characters/" .. encoded .. "/expectation"

    return wrk.format("GET", path)
end

response = function(status, headers, body)
    if status >= 400 then
        responses = responses or {}
        responses[status] = (responses[status] or 0) + 1
    end
end

done = function(summary, latency, requests)
    print("\n============================================================")
    print("Total Requests: " .. summary.requests)
    print("Duration: " .. (summary.duration / 1000000) .. "s")
    print("RPS: " .. (summary.requests / (summary.duration / 1000000)))

    if responses then
        print("\nErrors:")
        for status, count in pairs(responses) do
            print("  HTTP " .. status .. ": " .. count)
        end
    end
    print("============================================================")
end
