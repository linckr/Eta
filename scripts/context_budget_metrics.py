#!/usr/bin/env python3
"""Compute P1 request-budget metrics from Eta logcat text.

Usage:
    python scripts/context_budget_metrics.py <logcat.txt> [more.txt ...]

Protocol (emitted by AgentContextSession):
  context_request_budget phase=pre|post purpose=chat ... reason=...
      One pre line per chat request round; post only after a successful compaction.
  context_budget_decision purpose=chat trigger=bytes|token|both essential=...
      action=compact_done|fallback|soft_noop|hard_failure|deferred ...
      One terminal line per actual compaction attempt (with duration_ms), or one
      deferred line when the signed tool round blocks compaction, or when a byte-only
          summary already failed in the same user turn (not an attempt).
  context_request_budget_fallback ... (legacy P0 mirror; ignored for counting)

Metric definitions:
  chat_rounds                       purpose=chat preflight budget checks (one per pre line);
                                    this is NOT the number of successful provider HTTP requests
  byte_only_attempts                terminal attempts with trigger=bytes
  byte_only_per_100_rounds          byte_only_attempts / chat_rounds * 100
  byte_only_wait_ms                 sum of duration_ms over byte-only terminal attempts
  byte_only_wait_ms_per_100_rounds  byte_only_wait_ms / chat_rounds * 100
  deferred_rounds                   rounds deferred without an attempt (signed tool state or a
                                  byte-only summary already failed in this user turn)
  fallback_rounds                   attempts that fell back to sending the original request

decision_id deduplicates both pre-request rounds and terminal decisions across merged captures.
orphan_decisions counts terminal/deferred events without a matching pre line; trigger_mismatches
counts decisions whose trigger differs from the matching pre reason.

Groups are keyed by the configured byte budget so the 512 KiB default run and the
48 MiB control run can be compared from the same capture.
"""

from __future__ import annotations

import re
import sys
from collections import Counter

FIELD = re.compile(r"(?P<key>[A-Za-z_]+)=(?P<value>\S+)")
ATTEMPT_ACTIONS = {"compact_done", "fallback", "soft_noop", "hard_failure"}
DEFERRED_ACTION = "deferred"


def fields(line: str) -> dict[str, str]:
    return {match.group("key"): match.group("value") for match in FIELD.finditer(line)}


def parse(lines: list[str]) -> tuple[list[dict[str, str]], list[dict[str, str]], dict[str, object]]:
    rounds: list[dict[str, str]] = []
    raw_decisions: list[dict[str, str]] = []
    legacy_without_phase = 0
    duplicate_pre_lines = 0
    seen_pre_ids: set[str] = set()
    pre_reason_by_id: dict[str, str] = {}
    for line in lines:
        if "context_request_budget " in line and "reason=" in line:
            entry = fields(line)
            if entry.get("purpose") != "chat":
                continue
            phase = entry.get("phase")
            if phase is None:
                legacy_without_phase += 1
                rounds.append(entry)
            elif phase == "pre":
                decision_id = entry.get("decision_id")
                if decision_id:
                    if decision_id in seen_pre_ids:
                        duplicate_pre_lines += 1
                        continue
                    seen_pre_ids.add(decision_id)
                    pre_reason_by_id[decision_id] = entry.get("reason", "")
                rounds.append(entry)
        if "context_budget_decision purpose=" in line:
            entry = fields(line)
            if entry.get("purpose") == "chat":
                raw_decisions.append(entry)

    decisions: list[dict[str, str]] = []
    duplicate_terminal_decisions = 0
    decisions_without_id = 0
    seen_terminal_ids: set[str] = set()
    for decision in raw_decisions:
        decision_id = decision.get("decision_id")
        if decision_id:
            if decision_id in seen_terminal_ids:
                duplicate_terminal_decisions += 1
                continue
            seen_terminal_ids.add(decision_id)
        else:
            decisions_without_id += 1
        decisions.append(decision)

    orphan_decisions = [d for d in decisions
                        if d.get("decision_id") and d["decision_id"] not in pre_reason_by_id]
    trigger_mismatches = [d for d in decisions
                          if d.get("decision_id") in pre_reason_by_id
                          and d.get("trigger") != pre_reason_by_id[d["decision_id"]]]
    stats: dict[str, object] = {
        "legacy_budget_lines_without_phase": legacy_without_phase,
        "duplicate_pre_budget_lines_ignored": duplicate_pre_lines,
        "duplicate_terminal_decisions_ignored": duplicate_terminal_decisions,
        "decisions_without_decision_id": decisions_without_id,
        "orphan_decisions": len(orphan_decisions),
        "trigger_mismatches": len(trigger_mismatches),
    }
    return rounds, decisions, stats


def duration_ms(decision: dict[str, str]) -> int:
    try:
        return max(0, int(decision.get("duration_ms", "0")))
    except ValueError:
        return 0


def per_100(value: float, rounds: int) -> float:
    return round(value * 100.0 / rounds, 2) if rounds > 0 else 0.0


def summarize(lines: list[str]) -> dict[str, object]:
    rounds, decisions, stats = parse(lines)
    attempts = [d for d in decisions if d.get("action") in ATTEMPT_ACTIONS]
    deferred = [d for d in decisions if d.get("action") == DEFERRED_ACTION]
    byte_attempts = [d for d in attempts if d.get("trigger") == "bytes"]
    byte_wait = sum(duration_ms(d) for d in byte_attempts)
    essential_attempts = [d for d in attempts if d.get("essential") == "true"]

    groups: dict[str, dict[str, int]] = {}
    for entry in rounds:
        group = groups.setdefault(entry.get("byte_budget", "unknown"), {
            "chat_rounds": 0, "byte_only_attempts": 0, "byte_only_wait_ms": 0,
            "fallback_rounds": 0, "deferred_rounds": 0,
        })
        group["chat_rounds"] += 1
    for decision in attempts:
        group = groups.setdefault(decision.get("byte_budget", "unknown"), {
            "chat_rounds": 0, "byte_only_attempts": 0, "byte_only_wait_ms": 0,
            "fallback_rounds": 0, "deferred_rounds": 0,
        })
        if decision.get("trigger") == "bytes":
            group["byte_only_attempts"] += 1
            group["byte_only_wait_ms"] += duration_ms(decision)
        if decision.get("action") == "fallback":
            group["fallback_rounds"] += 1
    for decision in deferred:
        group = groups.setdefault(decision.get("byte_budget", "unknown"), {
            "chat_rounds": 0, "byte_only_attempts": 0, "byte_only_wait_ms": 0,
            "fallback_rounds": 0, "deferred_rounds": 0,
        })
        group["deferred_rounds"] += 1
    for group in groups.values():
        group["byte_only_per_100_rounds"] = per_100(group["byte_only_attempts"], group["chat_rounds"])
        group["byte_only_wait_ms_per_100_rounds"] = per_100(group["byte_only_wait_ms"], group["chat_rounds"])

    return {
        "scope": "preflight_only",
        "chat_rounds_definition": "preflight_budget_checks",
        "chat_rounds": len(rounds),
        **stats,
        "byte_only_attempts": len(byte_attempts),
        "byte_only_per_100_rounds": per_100(len(byte_attempts), len(rounds)),
        "byte_only_wait_ms": byte_wait,
        "byte_only_wait_ms_per_100_rounds": per_100(byte_wait, len(rounds)),
        "deferred_rounds": len(deferred),
        "fallback_rounds": sum(1 for d in attempts if d.get("action") == "fallback"),
        "essential_attempts": len(essential_attempts),
        "essential_wait_ms": sum(duration_ms(d) for d in essential_attempts),
        "attempts_by_trigger": dict(Counter(d.get("trigger", "?") for d in attempts)),
        "attempts_by_action": dict(Counter(d.get("action", "?") for d in attempts)),
        "attempts_by_code": dict(Counter(d["code"] for d in attempts if "code" in d)),
        "groups": groups,
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
        if key == "groups":
            continue
        print(f"{key}={value}")
    for budget, group in sorted(summary["groups"].items()):
        prefix = f"group byte_budget={budget}"
        print(f"{prefix} chat_rounds={group['chat_rounds']}")
        print(f"{prefix} byte_only_attempts={group['byte_only_attempts']}")
        print(f"{prefix} byte_only_per_100_rounds={per_100(group['byte_only_attempts'], group['chat_rounds'])}")
        print(f"{prefix} byte_only_wait_ms={group['byte_only_wait_ms']}")
        print(f"{prefix} byte_only_wait_ms_per_100_rounds={per_100(group['byte_only_wait_ms'], group['chat_rounds'])}")
        print(f"{prefix} fallback_rounds={group['fallback_rounds']}")
        print(f"{prefix} deferred_rounds={group['deferred_rounds']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
