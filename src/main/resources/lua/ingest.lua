-- Atualiza as fatias de minuto e hora de uma conta, atomicamente.
-- KEYS[1] = fs:{conta}
-- ARGV[1] = epoch minuto, ARGV[2] = epoch hora, ARGV[3] = valor em centavos,
-- ARGV[4] = menor minuto retido (M-59), ARGV[5] = menor hora retida (H-71)
local key, amt = KEYS[1], tonumber(ARGV[3])

local function merge(field)
  local cur = redis.call('HGET', key, field)
  if not cur then
    redis.call('HSET', key, field, '1|' .. ARGV[3] .. '|' .. ARGV[3] .. '|' .. ARGV[3])
    return true
  end
  local c, s, mn, mx = string.match(cur, '^(%d+)|(%d+)|(%d+)|(%d+)$')
  redis.call('HSET', key, field, string.format('%d|%d|%d|%d',
    tonumber(c) + 1, tonumber(s) + amt, math.min(tonumber(mn), amt), math.max(tonumber(mx), amt)))
  return false
end

local newMinute = merge('m:' .. ARGV[1])
merge('h:' .. ARGV[2])

if newMinute then
  local minM, minH = tonumber(ARGV[4]), tonumber(ARGV[5])
  for _, f in ipairs(redis.call('HKEYS', key)) do
    local kind, idx = string.sub(f, 1, 1), tonumber(string.sub(f, 3))
    if (kind == 'm' and idx < minM) or (kind == 'h' and idx < minH) then
      redis.call('HDEL', key, f)
    end
  end
end
return 0
