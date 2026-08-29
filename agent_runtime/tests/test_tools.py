from __future__ import annotations

from pathlib import Path

import pytest

from chobyar_agent import tools


def test_resolve_rejects_escape(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    with pytest.raises(ValueError):
        tools._resolve_repo_path("../outside.txt")


def test_resolve_rejects_secret_suffix(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    with pytest.raises(ValueError):
        tools._resolve_repo_path("release/signing-key.jks")


def test_env_variants_are_protected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    with pytest.raises(ValueError):
        tools._resolve_repo_path(".env.production")


@pytest.mark.parametrize(
    "path",
    [
        "AGENTS.md",
        "docs/SHAPR3D_PROFESSOR_AGENT.md",
        "docs/CAD_KERNEL_REWRITE_V2.md",
    ],
)
def test_policy_files_are_protected(path: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    with pytest.raises(ValueError):
        tools._resolve_repo_path(path)


def test_workflow_write_is_blocked(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    path = tmp_path / ".github" / "workflows" / "agent.yml"
    assert tools._write_allowed(path) is False


def test_source_write_type_is_allowed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    path = tmp_path / "app" / "src" / "main" / "java" / "Example.java"
    assert tools._write_allowed(path) is True


def test_build_gradle_needs_separate_gate(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    monkeypatch.delenv("CHOBYAR_AGENT_ALLOW_BUILD_CONFIG", raising=False)
    assert tools._write_allowed(tmp_path / "app" / "build.gradle") is False
    monkeypatch.setenv("CHOBYAR_AGENT_ALLOW_BUILD_CONFIG", "1")
    assert tools._write_allowed(tmp_path / "app" / "build.gradle") is True
