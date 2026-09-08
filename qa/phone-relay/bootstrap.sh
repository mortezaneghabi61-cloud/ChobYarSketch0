#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
REPO='mortezaneghabi61-cloud/ChobYarSketch0'
BRANCH_Q='qa%2Fphone-relay-20260908'
ROOT="$HOME/.chobyar-phone-relay"

pkg install -y gh git >/dev/null
if ! gh auth status >/dev/null 2>&1; then
  echo '[ChobYar QA] GitHub authorization is required once on this phone.'
  gh auth login --hostname github.com --git-protocol https --web
fi
mkdir -p "$ROOT"
gh api "repos/$REPO/contents/qa/phone-relay/relay.sh?ref=$BRANCH_Q" --jq .content | tr -d '\n' | base64 -d > "$ROOT/relay.sh"
chmod 700 "$ROOT/relay.sh"
if [ -f "$ROOT/relay.pid" ] && kill -0 "$(cat "$ROOT/relay.pid")" 2>/dev/null; then
  echo "[ChobYar QA] Relay already running pid=$(cat "$ROOT/relay.pid")"
  exit 0
fi
nohup "$ROOT/relay.sh" >> "$ROOT/relay.log" 2>&1 &
echo $! > "$ROOT/relay.pid"
sleep 1
if kill -0 "$(cat "$ROOT/relay.pid")" 2>/dev/null; then
  echo "[ChobYar QA] Relay running pid=$(cat "$ROOT/relay.pid")"
  echo '[ChobYar QA] You may return to ChatGPT.'
else
  echo '[FAIL-CLOSED] Relay failed to stay running.' >&2
  tail -50 "$ROOT/relay.log" >&2 || true
  exit 3
fi
