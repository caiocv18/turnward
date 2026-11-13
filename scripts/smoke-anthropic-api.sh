#!/usr/bin/env bash
# Smoke test for the production LLM key (ADR-0011).
#
# Sends one tiny Messages API request and prints only the HTTP status, the
# model, the stop reason and the token usage. The key is read from the
# git-ignored env file and handed to curl on stdin, so it never appears in
# the process list, in the output or in the shell history.
#
# Usage: scripts/smoke-anthropic-api.sh
#   ENV_FILE     env file to read (default: deploy/compose/.env)
#   SMOKE_MODEL  model id (default: claude-haiku-5-5)
#
# Exit codes: 0 ok, 1 API error, 2 local setup error,
#             3 credit balance too low (credits not linked yet, or used up).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${ENV_FILE:-$repo_root/deploy/compose/.env}"
model="${SMOKE_MODEL:-claude-haiku-5-5}"
api_url="https://api.anthropic.com/v1/messages"

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }
done

if [[ ! -r "$env_file" ]]; then
  echo "env file not found: $env_file (copy deploy/compose/.env.example and fill it in)" >&2
  exit 2
fi

# Parse instead of sourcing: the env file is data, never code.
key="$(sed -n 's/^ANTHROPIC_API_KEY=//p' "$env_file" | tail -n 1 | tr -d '\r[:space:]"')"
if [[ -z "$key" ]]; then
  echo "ANTHROPIC_API_KEY is empty in $env_file" >&2
  exit 2
fi

body_file="$(mktemp)"
resp_file="$(mktemp)"
trap 'rm -f "$body_file" "$resp_file"' EXIT

jq -n --arg model "$model" '{
  model: $model,
  max_tokens: 16,
  messages: [{role: "user", content: "Reply with the single word: pong"}]
}' >"$body_file"

# printf is a builtin, so the key only travels through the pipe.
status="$(printf 'x-api-key: %s\n' "$key" | curl -sS -o "$resp_file" -w '%{http_code}' \
  -H @- \
  -H 'anthropic-version: 2023-06-01' \
  -H 'content-type: application/json' \
  --data-binary @"$body_file" \
  "$api_url")"
unset key

echo "HTTP $status"
if [[ "$status" == "200" ]]; then
  jq -r '"model: \(.model)", "stop_reason: \(.stop_reason)", "usage: \(.usage | tojson)"' "$resp_file"
  exit 0
fi

err_type="$(jq -r '.error.type // "unknown"' "$resp_file" 2>/dev/null || echo unknown)"
err_msg="$(jq -r '.error.message // empty' "$resp_file" 2>/dev/null || true)"
echo "error: $err_type: $err_msg"
if grep -qi 'credit balance is too low' "$resp_file"; then
  echo "credits not linked yet, or used up: check claude.ai Settings > Billing > API credits" >&2
  exit 3
fi
exit 1
