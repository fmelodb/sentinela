import oracledb

# NUMBER volta como Decimal: dinheiro sem erro de ponto flutuante.
oracledb.defaults.fetch_decimals = True
