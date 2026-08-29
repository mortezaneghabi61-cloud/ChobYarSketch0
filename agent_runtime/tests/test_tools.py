from __future__ import annotations

import os
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


def test_policy_files_cannot_be_read_or_written(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tools, "ROOT", tmp_path.resolve())
    with pytest.raises(ValueError):
        tools._resolve_repo_path("AGENTS.md")


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
