local translator = {}
local symbols = { h = "一", s = "丨", p = "丿", n = "丶", z = "乙", x = "*" }

function translator.init(env)
  env.strokes = {}
  local file = assert(io.open(rime_api.get_shared_data_dir() .. "/selfopt_stroke.tsv", "r"),
    "Stroke lookup data is missing")
  for line in file:lines() do
    local word, code = line:match("^([^\t]+)\t([hspnz]+)$")
    if word and code then
      env.strokes[#env.strokes + 1] = { word, code }
    end
  end
  file:close()
end

function translator.func(input, segment, env)
  local code = input:lower()
  if not code:find("x", 1, true) or code:find("[^hspnzx]") then return end
  local pattern = "^" .. code:gsub("x", "[hspnz]")
  local preedit = code:gsub(".", symbols)
  local count = 0
  for _, entry in ipairs(env.strokes) do
    if entry[2]:match(pattern) then
      local candidate = Candidate("stroke_wildcard", segment.start, segment._end, entry[1], "")
      candidate.preedit = preedit
      yield(candidate)
      count = count + 1
      if count >= 2000 then return end
    end
  end
end

return translator
