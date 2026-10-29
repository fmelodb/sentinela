-- Grava (sobrescreve) a fatia de dia de uma conta e remove fatias de dia fora da retenção.
-- KEYS[1] = fs:{conta}; ARGV[1] = epoch dia, ARGV[2] = 'cnt|sum|min|max', ARGV[3] = menor dia retido (T-92)
redis.call('HSET', KEYS[1], 'd:' .. ARGV[1], ARGV[2])
local minD = tonumber(ARGV[3])
for _, f in ipairs(redis.call('HKEYS', KEYS[1])) do
  if string.sub(f, 1, 2) == 'd:' and tonumber(string.sub(f, 3)) < minD then
    redis.call('HDEL', KEYS[1], f)
  end
end
return 0
