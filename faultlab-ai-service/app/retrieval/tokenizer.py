import re
from typing import Any

TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]*|[\u4e00-\u9fff]")
CAMEL_CASE_PATTERN = re.compile(r"[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)|\d+")
SEPARATOR_PATTERN = re.compile(r"[._-]+")


def tokenize_text(value: str | None) -> list[str]:
    if not value:
        return []

    tokens: list[str] = []
    for raw_token in TOKEN_PATTERN.findall(str(value)):
        if _is_cjk(raw_token):
            tokens.append(raw_token)
            continue

        token = raw_token.lower()
        if not token:
            continue

        tokens.append(token)
        for part in SEPARATOR_PATTERN.split(raw_token):
            tokens.extend(subtoken for subtoken in _normalized_subtokens(part) if subtoken != token)

    return tokens


def tokenize_any(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, dict):
        tokens: list[str] = []
        for key, item in value.items():
            tokens.extend(tokenize_any(key))
            tokens.extend(tokenize_any(item))
        return tokens
    if isinstance(value, (list, tuple, set)):
        return [token for item in value for token in tokenize_any(item)]
    return tokenize_text(str(value))


def token_set(value: Any) -> set[str]:
    return set(tokenize_any(value))


def _normalized_subtokens(value: str) -> list[str]:
    if not value:
        return []

    lowered = value.lower()
    tokens = [lowered]
    camel_parts = [part.lower() for part in CAMEL_CASE_PATTERN.findall(value)]
    if len(camel_parts) > 1:
        tokens.extend(camel_parts)
    return [token for token in tokens if token]


def _is_cjk(value: str) -> bool:
    return len(value) == 1 and "\u4e00" <= value <= "\u9fff"
