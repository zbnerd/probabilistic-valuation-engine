-- wrk Lua script for expectation API test
-- Usage: wrk -t 10 -c 300 -d 2m -s load-test.lua http://localhost:8080/api/v4/characters/%EC%8A%A4%ED%83%80%EC%9D%BC%EB%A7%81/expectation

-- 1000 sample Korean user IGNs
user_igns = {
    "스타일링", "후임", "느님", "zneo", "기러기푸드득",
    "녹부", "Cremorne", "숙코", "늑때눔", "노문",
    "히어로띠띠윤", "난전설", "대박응가", "이로당", "사토미야렌",
    "꼬민아", "농소리바이", "쫀얜", "작업ab12", "아리는고양이",
    -- Add more IGNS here...
}

request = function()
    -- Pick random user IGN
    local user_ign = user_igns[math.random(#user_igns)]

    -- Create request path
    local path = "/api/v4/characters/" .. wrk.encode[user_ign or "스타일링"] .. "/expectation"

    -- Return request object
    return wrk.format("GET", path)
end

response = function(status, headers, body)
    -- Track responses
    if status >= 400 then
        responses = responses or {}
        responses[status] = (responses[status] or 0) + 1
    end
end

done = function(summary, latency, requests)
    -- Print additional statistics
    print("\nError breakdown:")
    if responses then
        for status, count in pairs(responses) do
            print(string.format("  HTTP %s: %d (%.2f%%)", status, count, count / summary.requests * 100))
        end
    end
end
