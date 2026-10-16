"""ChobYar-Trader hub: local preflight + sanitized append-only Git event records.
Public coordination only. No credentials, orders, exchange APIs or mutation of VPS.
Python stdlib. Requires authenticated Git only for explicit --publish.
"""
from __future__ import annotations
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BRANCH = "coordination/trader-hub-20261016"
EVENT_DIR = HERE / "events"
ACTORS = ("ChatGPT", "Work", "Codex", "Human", "Audit")

def validate_public(text: str) -> str:
    text = text.strip()
    if not text or len(text) > 1800:
        raise ValueError("non-empty public handoff field required (max 1800 characters)")
    # Never auto-publish secrets, personal email addresses, IPs or key-style values.
    patterns = [
        r"\b(?:api[_ -]?key|client[_ -]?secret|password|bearer|authorization|private[_ -]?key|ssh[_ -]?key)\s*[:=]\s*\S+",
        r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b",
        r"\b(?:\d{1,3}\.){3}\d{1,3}\b",
        r"(?i)(?:sk-proj|ghp_|github_pat_|xoxb-|AKIA)[A-Za-z0-9_\-]+",
        r"(?i)\.env\s*[:=]\s*\S+",
    ]
    if any(re.search(p, text, flags=re.I) for p in patterns):
        raise ValueError("REFUSE_PUBLICATION: possible secret, email or IP in handoff")
    return text

def git(*args: str, timeout: int = 45, check: bool = True) -> subprocess.CompletedProcess:
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    result = subprocess.run(["git", "-C", str(ROOT), *args],
        text=True, capture_output=True, timeout=timeout, env=env, encoding="utf-8",
        errors="replace")
    if check and result.returncode:
        raise RuntimeError("git command failed (details suppressed to avoid credential leaks); exit=" + str(result.returncode))
    return result

def preflight() -> dict:
    branch = git("branch", "--show-current").stdout.strip()
    if branch != BRANCH:
        raise RuntimeError("REFUSE: expected isolated hub branch, found " + branch)
    head = git("rev-parse", "HEAD").stdout.strip()
    remote = git("ls-remote", "origin", "refs/heads/" + BRANCH, timeout=50, check=False)
    remote_head = remote.stdout.split()[0] if remote.returncode == 0 and remote.stdout.strip() else None
    return {"branch": branch, "local_head": head, "remote_head": remote_head,
            "sync": ("REMOTE_ABSENT" if not remote_head else "IN_SYNC" if head==remote_head else "DIVERGED"),
            "remote_reachable": remote.returncode == 0}

def render_event(actor: str, task: str, summary: str, evidence: str, tests: str, next_step: str, stamp: str) -> str:
    if actor not in ACTORS or not re.fullmatch(r"TR-[A-Z0-9-]{3,48}", task):
        raise ValueError("actor or task ID invalid")
    summary, evidence, tests, next_step = [validate_public(s) for s in (summary,evidence,tests,next_step)]
    return (f"# Trader handoff {task}\n\n"
            f"- Time UTC: {stamp}\n- Actor: {actor}\n- Task: {task}\n"
            f"- Summary: {summary}\n- Evidence: {evidence}\n- Tests: {tests}\n"
            f"- Next: {next_step}\n- Trading authority: PAPER ONLY; no live execution\n")

def handoff(args: argparse.Namespace) -> int:
    status = preflight()
    if not status["remote_reachable"]:
        print("UNSYNCED: GitHub unavailable; refusing automatic publication"); return 2
    if status["sync"] == "DIVERGED":
        print("UNSYNCED: remote changed; read latest version and reconcile before publishing"); return 3
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    body = render_event(args.actor,args.task,args.summary,args.evidence,args.tests,args.next,stamp)
    EVENT_DIR.mkdir(exist_ok=True)
    path = EVENT_DIR / (stamp.replace("-","").replace(":","").replace("T","T").replace("Z","Z") + "-" + args.actor.lower() + "-" + uuid.uuid4().hex[:10] + ".md")
    # Exclusive creation avoids overwriting another session's event.
    with path.open("x", encoding="utf-8") as f: f.write(body)
    relative = path.relative_to(ROOT).as_posix()
    git("add", "--", relative)
    git("commit", "-m", "Trader hub: " + args.task + " handoff", "--", relative)
    print("LOCAL_HANDOFF_COMMITTED:", relative)
    if not args.publish:
        print("UNPUBLISHED: no --publish flag; other sessions do not have this event")
        return 0
    pushed = git("push", "origin", f"HEAD:refs/heads/{BRANCH}", timeout=90, check=False)
    if pushed.returncode:
        print("UNSYNCED: GitHub push failed; event remains committed locally")
        return 4
    print("PUBLISHED:", f"https://github.com/mortezaneghabi61-cloud/ChobYarSketch0/tree/{BRANCH}/ops/chobyar-trader/control-plane/events")
    return 0

def main() -> int:
    parser=argparse.ArgumentParser(description="ChobYar-Trader public coordination hub; no exchange operations")
    sub=parser.add_subparsers(dest="action", required=True)
    sub.add_parser("preflight")
    sub.add_parser("status")
    p=sub.add_parser("handoff")
    p.add_argument("--actor", choices=ACTORS, required=True)
    p.add_argument("--task", required=True)
    for k in ("summary","evidence","tests","next"):p.add_argument("--"+k,required=True)
    p.add_argument("--publish", action="store_true")
    a=parser.parse_args()
    if a.action in ("preflight","status"):
        result=preflight()
        if a.action=="status":result["snapshot"]=json.loads((HERE/"STATE.json").read_text(encoding="utf-8"))
        print(json.dumps(result,indent=2,ensure_ascii=False))
        return 0 if result["sync"]=="IN_SYNC" else 1
    return handoff(a)

if __name__=="__main__":
    try:sys.exit(main())
    except (RuntimeError,ValueError,subprocess.TimeoutExpired) as e:
        print("STOP:",str(e),file=sys.stderr);sys.exit(5)
