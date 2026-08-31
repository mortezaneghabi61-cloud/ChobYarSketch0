#!/usr/bin/env python3
import argparse
import fnmatch
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API_VERSION = "2022-11-28"
PASS_STATUS = "completed"
PASS_CONCLUSION = "success"


def fail(message):
    print(f"PR_MERGE_GATE_FAIL {message}", file=sys.stderr, flush=True)
    raise SystemExit(1)


def load_policy(path):
    try:
        policy = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"policy-load-error={exc}")

    if policy.get("version") != 1:
        fail("unsupported-policy-version")
    self_context = policy.get("self_context")
    if not isinstance(self_context, str) or not self_context.strip():
        fail("missing-self-context")

    trusted = policy.get("trusted_app") or {}
    if trusted.get("slug") != "github-actions" or not isinstance(trusted.get("id"), int):
        fail("trusted-app-must-pin-github-actions-slug-and-id")

    always_on = policy.get("always_on")
    conditional = policy.get("conditional")
    if not isinstance(always_on, list) or not always_on:
        fail("always-on-contexts-empty")
    if not isinstance(conditional, dict) or not conditional:
        fail("conditional-contexts-empty")
    if any(not isinstance(x, str) or not x for x in always_on):
        fail("invalid-always-on-context")
    if len(always_on) != len(set(always_on)):
        fail("duplicate-always-on-context")

    conditional_names = set(conditional)
    if set(always_on) & conditional_names:
        fail("context-cannot-be-both-always-on-and-conditional")
    if self_context in set(always_on) | conditional_names:
        fail("self-context-must-not-be-aggregated")

    for name, rule in conditional.items():
        if not isinstance(name, str) or not name:
            fail("invalid-conditional-context")
        paths = rule.get("paths") if isinstance(rule, dict) else None
        if not isinstance(paths, list) or not paths:
            fail(f"conditional-context-without-paths={name}")
        if any(not isinstance(p, str) or not p for p in paths):
            fail(f"invalid-path-pattern={name}")
        if len(paths) != len(set(paths)):
            fail(f"duplicate-path-pattern={name}")
    return policy


def api_get(url, token):
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": API_VERSION,
        "User-Agent": "chobyar-pr-merge-gate",
    }
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        fail(f"github-api-http-{exc.code} url={url} body={body[:500]}")
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        fail(f"github-api-error url={url} error={exc}")


def fetch_changed_files(repository, pr_number, token):
    files = []
    page = 1
    while True:
        url = (
            f"https://api.github.com/repos/{repository}/pulls/{pr_number}/files"
            f"?per_page=100&page={page}"
        )
        payload = api_get(url, token)
        if not isinstance(payload, list):
            fail("unexpected-pull-files-response")
        files.extend(item.get("filename", "") for item in payload)
        if len(payload) < 100:
            break
        page += 1
    if any(not name for name in files):
        fail("pull-files-response-contained-empty-filename")
    return files


def fetch_check_runs(repository, head_sha, token):
    runs = []
    page = 1
    while True:
        query = urllib.parse.urlencode({"filter": "all", "per_page": 100, "page": page})
        url = f"https://api.github.com/repos/{repository}/commits/{head_sha}/check-runs?{query}"
        payload = api_get(url, token)
        batch = payload.get("check_runs") if isinstance(payload, dict) else None
        if not isinstance(batch, list):
            fail("unexpected-check-runs-response")
        runs.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    for run in runs:
        if run.get("head_sha") != head_sha:
            fail(
                f"check-run-head-mismatch name={run.get('name')} "
                f"expected={head_sha} actual={run.get('head_sha')}"
            )
    return runs


def path_matches(filename, patterns):
    return any(fnmatch.fnmatchcase(filename, pattern) for pattern in patterns)


def policy_required_contexts(policy, changed_files):
    required = set(policy["always_on"])
    conditional_hits = {}
    for context, rule in policy["conditional"].items():
        hits = sorted(
            filename
            for filename in changed_files
            if path_matches(filename, rule["paths"])
        )
        conditional_hits[context] = hits
        if hits:
            required.add(context)
    return required, conditional_hits


def trusted_runs_by_name(runs, policy):
    self_context = policy["self_context"]
    trusted = policy["trusted_app"]
    known = set(policy["always_on"]) | set(policy["conditional"])
    trusted_by_name = {}
    untrusted_known = []

    for run in runs:
        name = run.get("name")
        if name == self_context:
            continue
        if name not in known:
            continue
        app = run.get("app") or {}
        if app.get("slug") != trusted["slug"] or app.get("id") != trusted["id"]:
            untrusted_known.append(
                (name, app.get("slug"), app.get("id"), run.get("id"))
            )
            continue
        trusted_by_name.setdefault(name, []).append(run)

    for name, items in trusted_by_name.items():
        items.sort(key=lambda item: (item.get("started_at") or "", item.get("id") or 0))

    return trusted_by_name, untrusted_known


def latest_run(items):
    return items[-1]


def render_run(run):
    return (
        f"id={run.get('id')} status={run.get('status')} "
        f"conclusion={run.get('conclusion')} started_at={run.get('started_at')}"
    )


def aggregate(policy, repository, pr_number, head_sha, token, timeout_seconds, poll_seconds, settle_seconds):
    changed_files = fetch_changed_files(repository, pr_number, token)
    required_by_policy, conditional_hits = policy_required_contexts(policy, changed_files)
    known = set(policy["always_on"]) | set(policy["conditional"])

    print(f"PR_MERGE_GATE_HEAD sha={head_sha}", flush=True)
    print(f"PR_MERGE_GATE_CHANGED_FILES count={len(changed_files)}", flush=True)
    for filename in sorted(changed_files):
        print(f"CHANGED {filename}", flush=True)
    for context in sorted(policy["conditional"]):
        hits = conditional_hits[context]
        print(
            f"CONDITIONAL_SCOPE context={json.dumps(context)} required={bool(hits)} "
            f"hits={json.dumps(hits)}",
            flush=True,
        )
    print(
        "POLICY_REQUIRED " + json.dumps(sorted(required_by_policy)),
        flush=True,
    )

    deadline = time.monotonic() + timeout_seconds
    all_success_since = None
    previous_observed = None

    while True:
        runs = fetch_check_runs(repository, head_sha, token)
        trusted_by_name, untrusted_known = trusted_runs_by_name(runs, policy)
        for name, slug, app_id, run_id in untrusted_known:
            print(
                f"IGNORED_UNTRUSTED_CHECK context={json.dumps(name)} "
                f"app_slug={slug} app_id={app_id} run_id={run_id}",
                flush=True,
            )

        observed_known = set(trusted_by_name) & known
        dynamic_required = set(required_by_policy) | observed_known

        if observed_known != previous_observed:
            print("OBSERVED_KNOWN " + json.dumps(sorted(observed_known)), flush=True)
            previous_observed = set(observed_known)
            all_success_since = None

        waiting = []
        failures = []
        passed = []
        for context in sorted(dynamic_required):
            items = trusted_by_name.get(context)
            if not items:
                waiting.append(f"{context}:missing-trusted-check")
                continue
            run = latest_run(items)
            status = run.get("status")
            conclusion = run.get("conclusion")
            if status == PASS_STATUS:
                if conclusion == PASS_CONCLUSION:
                    passed.append(context)
                else:
                    failures.append(f"{context}:{render_run(run)}")
            else:
                waiting.append(f"{context}:{render_run(run)}")

        if failures:
            for item in failures:
                print(f"FAILED_REQUIRED {item}", file=sys.stderr, flush=True)
            fail("required-validation-failed")

        now = time.monotonic()
        if waiting:
            all_success_since = None
            print(
                f"WAITING required={len(dynamic_required)} passed={len(passed)} "
                f"pending={json.dumps(waiting)}",
                flush=True,
            )
        else:
            if all_success_since is None:
                all_success_since = now
                print(
                    f"ALL_REQUIRED_SUCCESS settle_seconds={settle_seconds} "
                    f"contexts={json.dumps(sorted(dynamic_required))}",
                    flush=True,
                )
            elif now - all_success_since >= settle_seconds:
                final_runs = fetch_check_runs(repository, head_sha, token)
                final_by_name, _ = trusted_runs_by_name(final_runs, policy)
                final_observed = set(final_by_name) & known
                if final_observed != observed_known:
                    print("LATE_KNOWN_CHECK_DISCOVERED retrying", flush=True)
                    all_success_since = None
                    previous_observed = None
                else:
                    print(
                        f"PR_MERGE_GATE_PASS head_sha={head_sha} "
                        f"contexts={json.dumps(sorted(dynamic_required))}",
                        flush=True,
                    )
                    return

        if now >= deadline:
            fail(
                f"timeout head_sha={head_sha} required={json.dumps(sorted(dynamic_required))} "
                f"waiting={json.dumps(waiting)}"
            )
        time.sleep(poll_seconds)


def main():
    parser = argparse.ArgumentParser(description="Fail-closed exact-head PR validation aggregator")
    parser.add_argument("--policy", default=".github/pr-merge-gate-policy.json")
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--pr-number", type=int, default=int(os.environ.get("PR_NUMBER", "0")))
    parser.add_argument("--head-sha", default=os.environ.get("EXPECTED_HEAD_SHA"))
    parser.add_argument("--timeout-seconds", type=int, default=6000)
    parser.add_argument("--poll-seconds", type=int, default=15)
    parser.add_argument("--settle-seconds", type=int, default=45)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()

    policy = load_policy(args.policy)
    known_count = len(policy["always_on"]) + len(policy["conditional"])
    print(
        f"PR_MERGE_GATE_POLICY_OK always_on={len(policy['always_on'])} "
        f"conditional={len(policy['conditional'])} known={known_count} "
        f"trusted_app={policy['trusted_app']['slug']}:{policy['trusted_app']['id']}",
        flush=True,
    )
    if args.validate_only:
        return

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        fail("missing-GITHUB_TOKEN")
    if not args.repository or "/" not in args.repository:
        fail("invalid-repository")
    if args.pr_number <= 0:
        fail("invalid-pr-number")
    if not args.head_sha or len(args.head_sha) != 40:
        fail("invalid-head-sha")
    if args.timeout_seconds <= 0 or args.poll_seconds <= 0 or args.settle_seconds < 0:
        fail("invalid-timing")

    aggregate(
        policy,
        args.repository,
        args.pr_number,
        args.head_sha,
        token,
        args.timeout_seconds,
        args.poll_seconds,
        args.settle_seconds,
    )


if __name__ == "__main__":
    main()
