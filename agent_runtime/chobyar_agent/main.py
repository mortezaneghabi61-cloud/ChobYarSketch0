from __future__ import annotations

import argparse
import asyncio
import os
from pathlib import Path

from agents import Runner

from .agent import build_agent


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the ChobYar CAD professor engineering agent")
    parser.add_argument("--task", required=True, help="Engineering/review task for the agent")
    parser.add_argument("--apply", action="store_true", help="Allow guarded source/test/doc writes")
    return parser


async def _run(task: str, apply: bool) -> str:
    if not os.getenv("OPENAI_API_KEY"):
        raise RuntimeError("OPENAI_API_KEY is required")
    os.environ["CHOBYAR_AGENT_ALLOW_WRITES"] = "1" if apply else "0"
    result = await Runner.run(build_agent(), task)
    return str(result.final_output)


def main() -> None:
    args = _parser().parse_args()
    report = asyncio.run(_run(args.task, args.apply))
    print(report)
    state_dir = Path(os.getenv("CHOBYAR_AGENT_STATE_DIR", ".agent-state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / "latest_report.md").write_text(report + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
