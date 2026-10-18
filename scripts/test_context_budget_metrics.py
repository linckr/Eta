#!/usr/bin/env python3
"""Fixed-sample regression tests for scripts/context_budget_metrics.py.

Run from the repository root:
    python -m unittest scripts/test_context_budget_metrics.py
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import context_budget_metrics as metrics  # noqa: E402


FIXED_SAMPLE = [
    # Round 1: no pressure, pre only.
    "10-19 I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=1000 input_budget=1000000 request_bytes=1000 byte_budget=524288 reason=none",
    # Round 2: byte-only success -> pre + post + one compact_done attempt.
    "10-19 I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=60000 input_budget=1000000 request_bytes=600000 byte_budget=524288 reason=bytes",
    "10-19 I Eta : context_request_budget phase=post purpose=chat estimated_tokens=30000 input_budget=1000000 request_bytes=300000 byte_budget=524288 reason=none",
    "10-19 I Eta : context_budget_decision purpose=chat trigger=bytes essential=false action=compact_done estimated_tokens=60000 input_budget=1000000 request_bytes=600000 byte_budget=524288 duration_ms=1500 tokens_after=30000 bytes_after=300000",
    # Round 3: byte-only fallback -> one attempt, no post.
    "10-19 I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=60000 input_budget=1000000 request_bytes=600000 byte_budget=524288 reason=bytes",
    "10-19 I Eta : context_budget_decision purpose=chat trigger=bytes essential=false action=fallback code=CONTEXT_SUMMARY_INPUT_BUDGET estimated_tokens=60000 input_budget=1000000 request_bytes=600000 byte_budget=524288 duration_ms=400",
    "10-19 I Eta : context_request_budget_fallback purpose=chat trigger=bytes action=send_original_after_summary_failure code=CONTEXT_SUMMARY_INPUT_BUDGET request_bytes=600000 byte_budget=524288",
    # Round 4: deferred signed tool round -> not an attempt.
    "10-19 I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=50000 input_budget=1000000 request_bytes=500000 byte_budget=524288 reason=bytes",
    "10-19 I Eta : context_budget_decision purpose=chat trigger=bytes essential=false action=deferred code=SIGNED_TOOL_ROUND estimated_tokens=50000 input_budget=1000000 request_bytes=500000 byte_budget=524288",
    # Round 5: token pressure hard failure -> one attempt.
    "10-19 I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=700000 input_budget=1000000 request_bytes=2100000 byte_budget=524288 reason=token",
    "10-19 I Eta : context_budget_decision purpose=chat trigger=token essential=true action=hard_failure code=HTTP_400 estimated_tokens=700000 input_budget=1000000 request_bytes=2100000 byte_budget=524288 duration_ms=700",
    # Round 6: 48 MiB control group, token-only success.
    "10-19 I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=700000 input_budget=1000000 request_bytes=2100000 byte_budget=50331648 reason=none",
    "10-19 I Eta : context_budget_decision purpose=chat trigger=token essential=true action=compact_done estimated_tokens=700000 input_budget=1000000 request_bytes=2100000 byte_budget=50331648 duration_ms=800 tokens_after=300000 bytes_after=900000",
]


class MetricsTest(unittest.TestCase):
    def setUp(self):
        self.summary = metrics.summarize(FIXED_SAMPLE)

    def test_pre_rounds_are_not_double_counted_by_post_measurement(self):
        self.assertEqual(self.summary["chat_rounds"], 6)
        self.assertEqual(self.summary["legacy_budget_lines_without_phase"], 0)

    def test_successful_compaction_counts_as_one_attempt_with_duration(self):
        self.assertEqual(self.summary["attempts_by_action"]["compact_done"], 2)
        self.assertEqual(self.summary["attempts_by_trigger"]["bytes"], 2)
        self.assertEqual(self.summary["byte_only_attempts"], 2)
        self.assertEqual(self.summary["byte_only_wait_ms"], 1900)
        self.assertEqual(self.summary["byte_only_per_100_rounds"], 33.33)
        self.assertEqual(self.summary["byte_only_wait_ms_per_100_rounds"], 31666.67)

    def test_deferred_is_not_an_attempt(self):
        self.assertEqual(self.summary["deferred_rounds"], 1)
        self.assertNotIn("deferred", self.summary["attempts_by_action"])

    def test_fallback_and_essential_counts(self):
        self.assertEqual(self.summary["fallback_rounds"], 1)
        self.assertEqual(self.summary["essential_attempts"], 2)
        self.assertEqual(self.summary["essential_wait_ms"], 1500)
        self.assertEqual(self.summary["attempts_by_code"]["HTTP_400"], 1)

    def test_512_kib_and_48_mib_groups_are_separated(self):
        groups = self.summary["groups"]
        self.assertEqual(groups["524288"]["chat_rounds"], 5)
        self.assertEqual(groups["524288"]["byte_only_attempts"], 2)
        self.assertEqual(groups["524288"]["byte_only_wait_ms"], 1900)
        self.assertEqual(groups["524288"]["byte_only_per_100_rounds"], 40.0)
        self.assertEqual(groups["524288"]["byte_only_wait_ms_per_100_rounds"], 38000.0)
        self.assertEqual(groups["524288"]["fallback_rounds"], 1)
        self.assertEqual(groups["524288"]["deferred_rounds"], 1)
        self.assertEqual(groups["50331648"]["chat_rounds"], 1)
        self.assertEqual(groups["50331648"]["byte_only_attempts"], 0)

    def test_legacy_lines_without_phase_are_counted_and_flagged(self):
        legacy = [
            "I Eta : context_request_budget purpose=chat estimated_tokens=1 input_budget=1 request_bytes=1 byte_budget=524288 reason=none",
            "I Eta : context_budget_decision purpose=chat trigger=bytes essential=false action=fallback duration_ms=100 byte_budget=524288",
        ]
        summary = metrics.summarize(legacy)
        self.assertEqual(summary["chat_rounds"], 1)
        self.assertEqual(summary["legacy_budget_lines_without_phase"], 1)
        self.assertEqual(summary["byte_only_attempts"], 1)

    def test_duplicate_pre_line_with_same_decision_id_is_counted_once(self):
        duplicated = [
            "I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=1 input_budget=1 request_bytes=1 byte_budget=524288 reason=bytes decision_id=abc123def456",
            "I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=1 input_budget=1 request_bytes=1 byte_budget=524288 reason=bytes decision_id=abc123def456",
        ]
        summary = metrics.summarize(duplicated)
        self.assertEqual(summary["chat_rounds"], 1)
        self.assertEqual(summary["duplicate_pre_budget_lines_ignored"], 1)


if __name__ == "__main__":
    unittest.main()
