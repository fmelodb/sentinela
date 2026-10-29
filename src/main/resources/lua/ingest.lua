-- Grava uma transação no sorted set da conta e descarta as que saíram da retenção.
-- KEYS[1] = fs:{conta}:tx
-- ARGV[1] = ts em µs, ARGV[2] = '<tx_id>|<centavos>', ARGV[3] = maior score descartável (ts − 73h, em µs)
-- NX mantém o primeiro ts de um tx_id repetido, como a PK do Oracle.
redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[3])
return 0
