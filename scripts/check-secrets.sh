#!/usr/bin/env bash
# Secrets check (NFR-005, I7): fails when tracked files or any commit of the history contain a private key, a known
# token format or a key store. Patterns are high-signal only; review a hit and rotate the secret, do not allowlist it.
#   scripts/check-secrets.sh            tracked files and the full history (CI checks out with fetch-depth 0)
#   scripts/check-secrets.sh --tracked  tracked files only
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

content='-----BEGIN ([A-Z]+ )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{60,}|AKIA[0-9A-Z]{16}|xox[abpors]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9]{32,}|-----BEGIN PGP PRIVATE KEY BLOCK-----'
files='\.(p12|pfx|jks|keystore|pem|key|mobileprovision|p8)$|(^|/)(google-services\.json|GoogleService-Info\.plist|keystore\.properties|local\.properties|\.env)$'
self='scripts/check-secrets.sh'
status=0

if tracked=$(git ls-files | grep -Ev "^$self$" | grep -E "$files"); then
  echo "Key stores or credential files are tracked:"; echo "$tracked"; status=1
fi
# git grep exits 1 for no match; anything above is an error and must not pass as clean.
hits=$(git grep -nIE -e "$content" -- . ":(exclude)$self") && found=0 || found=$?
if [[ $found -gt 1 ]]; then echo "git grep failed"; exit 2; fi
if [[ $found -eq 0 ]]; then
  # Only the location: the log of a public CI run must not repeat the secret.
  echo "Secret-like content in tracked files:"; echo "$hits" | cut -d: -f1,2; status=1
fi

if [[ "${1:-}" != "--tracked" ]]; then
  if [[ "$(git rev-parse --is-shallow-repository)" == "true" ]]; then
    echo "The history scan needs the full history (actions/checkout with fetch-depth: 0)"; exit 2
  fi
  if old=$(git log --all --format= --name-only | sort -u | grep -Ev "^$self$" | grep -E "$files"); then
    echo "Key stores or credential files in the history:"; echo "$old"; status=1
  fi
  if old=$(git log --all -E -G "$content" --format='commit %h' --name-only -- . ":(exclude)$self" | grep .); then
    echo "Secret-like content added or removed in the history:"; echo "$old"; status=1
  fi
fi

if [[ $status -eq 0 ]]; then
  echo "No secrets found in $(git ls-files | wc -l | tr -d ' ') tracked files$([[ "${1:-}" != "--tracked" ]] && echo " and $(git rev-list --all | wc -l | tr -d ' ') commits")."
fi
exit $status
