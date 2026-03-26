counter = 0
user_igns = {}

function load_user_igns()
    local file = io.open("/home/maple/probabilistic-valuation-engine/valid-users-100-en.txt", "r")
    if not file then
        print("Error: Could not open valid-users-100-en.txt")
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
    local path = "/api/v4/expectation/" .. user_ign
    return wrk.format("GET", path)
end
