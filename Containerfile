# Imagem única da POC: a API (sentinela api) e o simulador (sentinela carga, gerador, relatorio).
FROM docker.io/library/python:3.13-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TZ=UTC PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src src
RUN pip install --no-cache-dir .
COPY db db
ENV SENTINELA_SCHEMA=/app/db/01_schema.sql
WORKDIR /
CMD ["sentinela", "api"]

# Testes: scripts/test.sh constrói este alvo e roda o pytest na rede do compose.
FROM base AS test
WORKDIR /app
RUN pip install --no-cache-dir ".[test]"
COPY tests tests
CMD ["pytest", "-q"]
