import datetime
import importlib.util
import json
import os
import tempfile
import unittest
from unittest import mock
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("collector", Path(__file__).resolve().parents[1] / "collect_youtube_comments.py")
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)


def page(comment_id="synthetic_id"):
    return {"items": [{"snippet": {"topLevelComment": {"id": comment_id, "snippet": {
        "textDisplay": "합성 댓글", "publishedAt": "2024-05-17T00:00:00Z", "updatedAt": "2024-05-17T00:00:00Z",
        "likeCount": 0, "authorDisplayName": "must-not-be-stored", "authorChannelId": {"value": "private"}}}}}],
        "nextPageToken": "not-persisted"}


class CollectorTests(unittest.TestCase):
    def test_literal_env_read_only_target_key_and_explicit_precedence(self):
        for literal in ("synthetic-key", "'synthetic-key'", '"synthetic-key" # local comment'):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / ".env"
                path.write_text("OTHER=$(must-not-run)\nexport YOUTUBE_API_KEY=" + literal + "\n")
                path.chmod(0o600)
                self.assertEqual("synthetic-key", collector.read_key(path, {"YOUTUBE_API_KEY": "stale-key-value"}))
        self.assertEqual("environment-key", collector.read_key(environ={"YOUTUBE_API_KEY": "environment-key"}))

    def test_env_errors_never_echo_values(self):
        for content in ("YOUTUBE_API_KEY=$(sensitive-command)", "YOUTUBE_API_KEY='sensitive-key",
                        "YOUTUBE_API_KEY=sensitive-key\nYOUTUBE_API_KEY=duplicate-secret"):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / ".env"
                path.write_text(content)
                path.chmod(0o600)
                with self.assertRaises(collector.CollectionError) as error:
                    collector.read_key(path)
                self.assertNotIn("sensitive", str(error.exception))
                self.assertNotIn("duplicate-secret", str(error.exception))

    def test_env_file_must_be_private_bounded_and_not_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("YOUTUBE_API_KEY=synthetic-key")
            path.chmod(0o644)
            if os.name == "posix":
                with self.assertRaises(collector.CollectionError):
                    collector.read_key(path)
            path.chmod(0o600)
            link = Path(directory) / "link"
            link.symlink_to(path)
            with self.assertRaises(collector.CollectionError):
                collector.read_key(link)
            path.write_text("x" * 65537)
            with self.assertRaises(collector.CollectionError):
                collector.read_key(path)

    def test_discovery_is_one_bounded_request_without_comment_collection(self):
        calls = []
        def fetch(key, endpoint, params):
            calls.append((endpoint, params))
            return {"items": [{"id": {"videoId": "abcdefghijk"}, "snippet": {
                "title": "Synthetic report", "channelTitle": "Synthetic news", "publishedAt": "2020-03-13T00:00:00Z"}}]}
        result = collector.discover("synthetic-key", "synthetic search", fetch)
        self.assertEqual(1, len(calls))
        self.assertEqual("search", calls[0][0])
        self.assertEqual(5, calls[0][1]["maxResults"])
        self.assertEqual(0, result["commentsCollected"])
        self.assertFalse(result["candidates"][0]["originalClipVerified"])

    def test_http_diagnostic_never_exposes_request_key_or_response_body(self):
        import io
        import urllib.error
        error = urllib.error.HTTPError("https://example.org/?key=sensitive-key", 403, "secret error", {},
                                       io.BytesIO(b'{"error":{"errors":[{"reason":"unknown-sensitive-reason"}]}}'))
        with mock.patch.object(collector.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(collector.CollectionError) as captured:
                collector.fetch_page("sensitive-key", "abcdefghijk", "relevance", 50)
        self.assertEqual("HTTP_403", str(captured.exception))

    def test_bounded_requests_dedup_and_no_author_or_key(self):
        calls = []
        def fetch(key, video_id, order, limit):
            calls.append((video_id, order, limit))
            return page()
        result = collector.collect("synthetic-secret", collector.DEFAULT_VIDEOS, fetch=fetch)
        self.assertEqual(4, len(calls))
        self.assertEqual(2, len(result["comments"]))
        self.assertEqual(["relevance", "time"], result["comments"][0]["sampleOrders"])
        serialized = json.dumps(result)
        for forbidden in ("synthetic-secret", "must-not-be-stored", "authorChannelId", "nextPageToken"):
            self.assertNotIn(forbidden, serialized)
        self.assertFalse(result["datasetUseAuthorized"])

    def test_error_keeps_successful_other_order_without_faking_comments(self):
        def fetch(key, video_id, order, limit):
            if order == "time":
                raise collector.CollectionError("commentsDisabled")
            return page()
        result = collector.collect("synthetic", collector.DEFAULT_VIDEOS[:1], fetch=fetch)
        self.assertEqual("PARTIAL_SAMPLE", result["status"])
        self.assertEqual(1, len(result["comments"]))
        self.assertEqual("commentsDisabled", result["sampling"][1]["failureCode"])

    def test_all_failures_are_not_complete_empty_sample(self):
        def fetch(*args):
            raise collector.CollectionError("quotaExceeded")
        result = collector.collect("synthetic", collector.DEFAULT_VIDEOS[:1], fetch=fetch)
        self.assertEqual("FAILED", result["status"])
        self.assertEqual([], result["comments"])

    def test_input_limits_before_network(self):
        def fetch(*args):
            self.fail("Must not make request")
        for key, videos, limit in (("", collector.DEFAULT_VIDEOS, 50), ("x", ["invalid"], 50),
                                   ("x", collector.DEFAULT_VIDEOS, 101), ("x", collector.DEFAULT_VIDEOS * 2, 50)):
            with self.assertRaises(collector.CollectionError):
                collector.collect(key, videos, limit, fetch)

    def test_malformed_page_is_not_partially_accepted(self):
        def fetch(*args):
            result = page()
            result["items"].append({"snippet": {}})
            return result
        result = collector.collect("synthetic", collector.DEFAULT_VIDEOS[:1], fetch=fetch)
        self.assertEqual("FAILED", result["status"])
        self.assertEqual([], result["comments"])

    def test_retention_timestamp(self):
        now = datetime.datetime(2026, 10, 9, tzinfo=datetime.timezone.utc)
        result = collector.collect("synthetic", collector.DEFAULT_VIDEOS[:1], fetch=lambda *args: page(), now=now)
        self.assertEqual("2026-11-08T00:00:00+00:00", result["refreshOrDeleteBy"])

    def test_private_snapshots_do_not_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            first = collector.save_snapshot({"comments": []}, directory)
            second = collector.save_snapshot({"comments": []}, directory)
            self.assertNotEqual(first, second)
            self.assertEqual(0o600, first.stat().st_mode & 0o777)
            self.assertEqual([], json.loads(first.read_text())["comments"])


if __name__ == "__main__":
    unittest.main()
