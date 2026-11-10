-- 「中英混输」关闭时应用把选项 pure_chinese 置真，此时丢弃含英文字母的候选。
-- 未设置的选项在 librime 中为假，默认原样放行，保持中英混输。
local filter = {}

function filter.func(translation, env)
  local pure = env.engine.context:get_option("pure_chinese")
  for candidate in translation:iter() do
    if not pure or not candidate.text:find("[A-Za-z]") then
      yield(candidate)
    end
  end
end

return filter
