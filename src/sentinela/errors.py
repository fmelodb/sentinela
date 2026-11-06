"""Erros com código de saída. Os programas da CLI saem com o código da exceção."""

OK = 0
VERIFICATION_FAILED = 1
USAGE = 2
STORE_UNAVAILABLE = 3
INVALID_RUN = 4
UNEXPECTED = 5


class SentinelaError(Exception):
    exit_code = UNEXPECTED


class UsageError(SentinelaError):
    """Argumento ou variável de ambiente inválida."""

    exit_code = USAGE


class StoreUnavailable(SentinelaError):
    """Oracle ou API inacessível."""

    exit_code = STORE_UNAVAILABLE


class InvalidRun(SentinelaError):
    """Rodada inválida: dataset esgotado ou plano de execução errado."""

    exit_code = INVALID_RUN
