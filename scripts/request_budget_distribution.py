#!/usr/bin/env python3
"""Offline distribution and shadow-trigger analysis for request budgets.

Usage:
    python scripts/request_budget_distribution.py <logcat.txt> [more.txt ...]

Only `purpose=chat` preflight budget checks are analyzed. No summary is triggered;
`shadow_512k_triggers` answers "how many requests would the 512 KiB soft policy have
started a compaction for, given the observed request bytes and estimated token pressure".

Inputs:
  context_request_budget phase=pre purpose=chat request_bytes=... estimated_tokens=...
      input_budget=...|unknown byte_budget=... reason=...
  model_http_trace ... purpose=chat ... event=http_call_completed ... status=...
      request_bytes=... written_bytes=... write_completed=true|false

Definitions:
  token_pressure   estimated_tokens >= 0.85 * input_budget (when input budget known)
  over_512k        request_bytes > 524288
  shadow_512k_trigger = over_512k and token pressure is known false
  unknown_window   input_budget is missing/unknown (shadow classification unknown)
  upload_failed    a completed chat trace explicitly reporting write_completed=false
  http_non_success a completed chat trace with a non-2xx status (not proof of upload failure)
"""

from __future__ import annotations

import re
import sys
from collections import Counter

FIELD = re.compile(r"(?P<key>[A-Za-z_]+)=(?P<value>\S+)")
TRIGGER_RATIO = 0.85
BYTES_512K = 524288
BYTES_1MIB = 1048576


def fields(line: str) -> dict[str, str]:
    return {match.group("key"): match.group("value") for match in FIELD.finditer(line)}


def parse(lines: list[str]) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    preflights: list[dict[str, str]] = []
    traces: list[dict[str, str]] = []
    for line in lines:
        if "context_request_budget " in line and "reason=" in line:
            entry = fields(line)
            if entry.get("purpose") == "chat" and entry.get("phase") == "pre":
                preflights.append(entry)
        if "model_http_trace " in line and "purpose=chat" in line and "event=http_call_completed" in line:
            traces.append(fields(line))
    return preflights, traces


def int_field(entry: dict[str, str], key: str) -> int | None:
    try:
        return int(entry[key])
    except (KeyError, ValueError):
        return None


def percentile(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def summarize(lines: list[str]) -> dict[str, object]:
    preflights, traces = parse(lines)
    def unique(entries: list[dict[str, str]], key: str) -> tuple[list[dict[str, str]], int]:
        seen: set[str] = set()
        kept: list[dict[str, str]] = []
        duplicates = 0
        for entry in entries:
            identity = entry.get(key)
            if identity and identity in seen:
                duplicates += 1
                continue
            if identity:
                seen.add(identity)
            kept.append(entry)
        return kept, duplicates

    preflights, duplicate_preflights = unique(preflights, "decision_id")
    traces, duplicate_traces = unique(traces, "trace_id")
    bytes_values = [value for entry in preflights if (value := int_field(entry, "request_bytes")) is not None]
    token_pressures: list[bool | None] = []
    over_512k = 0
    over_1mib = 0
    shadow_512k = 0
    shadow_capacity = 0
    shadow_unknown = 0
    for entry in preflights:
        request_bytes = int_field(entry, "request_bytes")
        estimated = int_field(entry, "estimated_tokens")
        budget = int_field(entry, "input_budget")
        pressure: bool | None = None
        if estimated is not None and budget is not None and budget > 0:
            pressure = estimated >= budget * TRIGGER_RATIO
        # The runtime also observes provider usage, which can exceed the estimate.
        if entry.get("reason") in {"token", "both"}:
            pressure = True
        token_pressures.append(pressure)
        if request_bytes is None:
            continue
        if request_bytes > BYTES_512K:
            over_512k += 1
            if pressure is False:
                shadow_512k += 1
            elif pressure is None:
                shadow_unknown += 1
        if request_bytes > BYTES_1MIB:
            over_1mib += 1
        derived_budget = BYTES_1MIB if budget is not None and budget >= 256_000 else BYTES_512K
        if pressure is False and request_bytes > derived_budget:
            shadow_capacity += 1

    statuses: Counter[str] = Counter()
    upload_failed = 0
    http_non_success = 0
    for trace in traces:
        status = trace.get("status", "unknown")
        statuses[status] += 1
        write_completed = trace.get("write_completed", "").lower()
        if write_completed == "false":
            upload_failed += 1
        if status.isdigit() and not 200 <= int(status) < 300:
            http_non_success += 1

    requests = len(preflights)
    return {
        "scope": "chat_preflight",
        "requests": requests,
        "duplicate_preflights_ignored": duplicate_preflights,
        "preflights_without_decision_id": sum(1 for entry in preflights if not entry.get("decision_id")),
        "duplicate_http_traces_ignored": duplicate_traces,
        "requests_with_bytes": len(bytes_values),
        "request_bytes_p50": percentile(bytes_values, 0.50),
        "request_bytes_p95": percentile(bytes_values, 0.95),
        "request_bytes_p99": percentile(bytes_values, 0.99),
        "request_bytes_max": max(bytes_values) if bytes_values else None,
        "over_512k": over_512k,
        "over_512k_ratio": round(over_512k / requests, 4) if requests else 0.0,
        "over_1mib": over_1mib,
        "over_1mib_ratio": round(over_1mib / requests, 4) if requests else 0.0,
        "shadow_512k_triggers": shadow_512k,
        "shadow_512k_trigger_ratio": round(shadow_512k / requests, 4) if requests else 0.0,
        "shadow_capacity_default_triggers": shadow_capacity,
        "shadow_unknown_window": shadow_unknown,
        "token_pressure": sum(1 for pressure in token_pressures if pressure is True),
        "token_pressure_ratio_unknown": sum(1 for pressure in token_pressures if pressure is None),
        "http_completed": len(traces),
        "http_upload_failures": upload_failed,
        "http_non_success": http_non_success,
        "http_write_state_unknown": sum(1 for trace in traces if trace.get("write_completed") not in {"true", "false"}),
        "http_statuses": dict(statuses),
        "by_existing_byte_budget": dict(Counter(entry.get("byte_budget", "unknown") for entry in preflights)),
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
    for key, value in summarize(lines).items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
