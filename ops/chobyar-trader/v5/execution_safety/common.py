from __future__ import annotations

import json
import math
import os
from dataclasses import asdict, is_dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SENSITIVE_MARKERS = ("secret", "token", "password", "passwd", "api_key", "apikey", "authorization", "cookie")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def finite(value: Any, default: float = 0.0) -> float:
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else default
    except (TypeError, ValueError):
        return default


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(asdict(value) if is_dataclass(value) else value, ensure_ascii=False, indent=2), encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(path)


class SecretFreeAudit:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._known_secrets = tuple(
            value for key, value in os.environ.items()
            if value and len(value) >= 8 and any(marker in key.lower() for marker in SENSITIVE_MARKERS)
        )

    def _clean(self, value: Any, key: str = "") -> Any:
        if any(marker in key.lower() for marker in SENSITIVE_MARKERS):
            return "[REDACTED]"
        if isinstance(value, dict):
            return {str(k): self._clean(v, str(k)) for k, v in value.items()}
        if isinstance(value, (list, tuple)):
            return [self._clean(v) for v in value]
        if isinstance(value, str):
            cleaned = value
            for secret in self._known_secrets:
                cleaned = cleaned.replace(secret, "[REDACTED]")
            return cleaned[:1000]
        return value

    def write(self, event: str, **fields: Any) -> None:
        record = self._clean({"ts": utc_now(), "event": event, **fields})
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
