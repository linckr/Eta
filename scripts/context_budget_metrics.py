#!/usr/bin/env python3
"""Compute P1 request-budget metrics from Eta logcat text.

Usage:
    python scripts/context_budget_metrics.py <logcat.txt> [more.txt ...]

The app emits one `context_request_budget` line per outgoing request round and one
`context_budget_decision` line per pre-request pressure decision (read-only telemetry).
Only `purpose=chat` is aggregated here.

Reported metrics:
  chat_rounds                         denominator for the per-100-round metrics
  non_essential_attempts              compactions triggered by byte-only soft pressure
  non_essential_per_100_rounds        the number P1 must drive down
  non_essential_wait_ms               wall-clock the user waited before a chat request
  non_essential_wait_ms_per_100_rounds
  essential_*                         token/capacity pressure for comparison

A successful compaction logs both compact_started (attempt) and compact_done
(duration); a failed/no-op attempt logs one line with its own duration.
"""

from __future__ import annotations

import re
import sys
from collections import Counter

FIELD = re.compile(r"(?P<key>[A-Za-z_]+)=(?P<value>\S+)")
ATTEMPT_ACTIONS = {"compact_started", "fallback", "soft_noop", "hard_failure", "deferred"}
WAIT_ACTIONS = {"compact_done", "fallback", "soft_noop", "hard_failure"}


def parse(lines: list[str]) -> tuple[int, list[dict[str, str]]]:
    rounds = 0
    decisions: list[dict[str, str]] = []
    for line in lines:
        if "context_request_budget purpose=" in line and "reason=" in line:
            rounds += 1
        if "context_budget_decision purpose=" not in line:
            continue
        fields = {m.group("key"): m.group("value") for m in FIELD.finditer(line)}
        if fields.get("purpose") == "chat":
            decisions.append(fields)
    return rounds, decisions


def duration_ms(decision: dict[str, str]) -> int:
    if decision.get("action") not in WAIT_ACTIONS:
        return 0
    try:
        return max(0, int(decision.get("duration_ms", "0")))
    except ValueError:
        return 0


def per_100(value: float, rounds: int) -> float:
    return round(value * 100.0 / rounds, 2) if rounds > 0 else 0.0


def summarize(lines: list[str]) -> dict[str, object]:
    rounds, decisions = parse(lines)
    essential = [d for d in decisions if d.get("essential") == "true"]
    non_essential = [d for d in decisions if d.get("essential") == "false"]
    attempts = [d for d in non_essential if d.get("action") in ATTEMPT_ACTIONS]
    wait_ms = sum(duration_ms(d) for d in non_essential)
    essential_wait_ms = sum(duration_ms(d) for d in essential)
    return {
        "chat_rounds": rounds,
        "decision_lines": len(decisions),
        "non_essential_attempts": len(attempts),
        "non_essential_per_100_rounds": per_100(len(attempts), rounds),
        "non_essential_wait_ms": wait_ms,
        "non_essential_wait_ms_per_100_rounds": per_100(wait_ms, rounds),
        "non_essential_actions": dict(Counter(d.get("action", "?") for d in non_essential)),
        "non_essential_codes": dict(Counter(d["code"] for d in non_essential if "code" in d)),
        "essential_attempts": sum(1 for d in essential if d.get("action") in ATTEMPT_ACTIONS),
        "essential_wait_ms": essential_wait_ms,
        "essential_codes": dict(Counter(d["code"] for d in essential if "code" in d)),
        "triggers": dict(Counter(d.get("trigger", "?") for d in decisions)),
    }


def main(paths: list[str]) -> int:
    lines: list[str] = []
    for path in paths:
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                lines.extend(handle.read().splitlines())
        except OSError as error:
            print(f"skip {path}: {error}", file=sys.stderr)
    if not lines:
        print("no log lines; pass at least one logcat text file", file=sys.stderr)
        return 2
    summary = summarize(lines)
    for key, value in summary.items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
