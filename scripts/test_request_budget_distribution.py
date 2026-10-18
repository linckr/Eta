#!/usr/bin/env python3
"""Fixed-sample regression tests for scripts/request_budget_distribution.py."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import request_budget_distribution as distribution  # noqa: E402


SAMPLE = [
    # 100 KB, no pressure.
    "I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=10000 input_budget=1000000 request_bytes=100000 byte_budget=50331648 reason=none",
    # 540,885 B, token ratio 18.4% -> shadow trigger under 512 KiB.
    "I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=183591 input_budget=1000000 request_bytes=540885 byte_budget=50331648 reason=none",
    # 600 KB but token pressure 90% -> not a byte-only shadow trigger.
    "I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=900000 input_budget=1000000 request_bytes=600000 byte_budget=50331648 reason=none",
    # 700 KB with unknown window -> over threshold but token pressure unknown.
    "I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=200000 input_budget=unknown request_bytes=700000 byte_budget=50331648 reason=none",
    # 500 KB, no pressure.
    "I Eta : context_request_budget phase=pre purpose=chat estimated_tokens=100000 input_budget=1000000 request_bytes=500000 byte_budget=50331648 reason=none",
    # chat HTTP traces: four successes and one failure.
    "I Eta : model_http_trace trace_id=a protocol=responses purpose=chat phase=response_body event=http_call_completed request_bytes=100000 written_bytes=100000 write_completed=true status=200",
    "I Eta : model_http_trace trace_id=b protocol=responses purpose=chat phase=response_body event=http_call_completed request_bytes=540885 written_bytes=540885 write_completed=true status=200",
    "I Eta : model_http_trace trace_id=c protocol=responses purpose=chat phase=response_body event=http_call_completed request_bytes=600000 written_bytes=600000 write_completed=true status=200",
    "I Eta : model_http_trace trace_id=d protocol=responses purpose=chat phase=response_body event=http_call_completed request_bytes=700000 written_bytes=700000 write_completed=false status=500",
]


class DistributionTest(unittest.TestCase):
    def setUp(self):
        self.summary = distribution.summarize(SAMPLE)

    def test_request_count_and_percentiles(self):
        self.assertEqual(self.summary["requests"], 5)
        self.assertEqual(self.summary["request_bytes_p50"], 540885)
        self.assertEqual(self.summary["request_bytes_max"], 700000)

    def test_over_threshold_and_shadow_trigger_counts(self):
        self.assertEqual(self.summary["over_512k"], 3)
        self.assertEqual(self.summary["over_1mib"], 0)
        self.assertEqual(self.summary["shadow_512k_triggers"], 1)
        self.assertEqual(self.summary["shadow_unknown_window"], 1)
        self.assertEqual(self.summary["token_pressure"], 1)

    def test_upload_failure_detection(self):
        self.assertEqual(self.summary["http_completed"], 4)
        self.assertEqual(self.summary["http_upload_failures"], 1)
        self.assertEqual(self.summary["http_statuses"]["200"], 3)
        self.assertEqual(self.summary["http_statuses"]["500"], 1)

    def test_non_chat_and_legacy_phase_are_ignored(self):
        lines = [
            "I Eta : context_request_budget phase=pre purpose=compaction estimated_tokens=1 input_budget=1 request_bytes=999999 byte_budget=524288 reason=bytes",
            "I Eta : model_http_trace purpose=compaction event=http_call_completed status=200",
        ]
        summary = distribution.summarize(lines)
        self.assertEqual(summary["requests"], 0)
        self.assertEqual(summary["http_completed"], 0)


if __name__ == "__main__":
    unittest.main()
