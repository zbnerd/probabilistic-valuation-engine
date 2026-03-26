-- wrk script for testing /api/v4/expectation/{userIgn} endpoint
-- Uses valid-users-30000.txt for cache MISS testing

counter = 0
user_igns = {}

-- URL encode function
function url_encode(str)
    if str == nil then return "" end
    str = string.gsub(str, "\n", "\r\n")
    str = string.gsub(str, "([^%w%-%_%.%~])", function(c)
        return string.format("%%%02X", string.byte(c))
    end)
    return str
end

-- Load user IGNS from file
function load_user_igns()
    local file = io.open("/home/maple/probabilistic-valuation-engine/valid-users-30000.txt", "r")
    if not file then
        print("Error: Could not open valid-users-30000.txt")
        return
    end
    
    for line in file:lines() do
        local ign = string.match(line, "^%s*(.-)%s*$")
        if ign and ign ~= "" then
            table.insert(user_igns, ign)
        end
    end
    file:close()
    
    print("Loaded " .. #user_igns .. " user IGNS")
end

function init(args)
    load_user_igns()
end

function request()
    counter = counter + 1
    local idx = (counter - 1) % #user_igns + 1
    local user_ign = user_igns[idx]
    local encoded_ign = url_encode(user_ign)
    local path = "/api/v4/expectation/" .. encoded_ign
    return wrk.format("GET", path)
end

response = function(status, headers, body)
    if status ~= 200 then
    end
end
