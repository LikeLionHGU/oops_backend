"""Small official-API snapshot for temporary review, not an approved training dataset.

Reads YOUTUBE_API_KEY from this process environment. Never prints keys or comments.
No scraping, video downloading, model calls, labeling, or production archive updates.
"""
import argparse
import datetime
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}\Z")
DEFAULT_VIDEOS = ["mM3su9pxkJQ", "4bmimxyVA3k"]
MAX_RESPONSE_BYTES = 2_097_152
FIELDS = "nextPageToken,items(snippet(topLevelComment(id,snippet(textDisplay,publishedAt,updatedAt,likeCount))))"


class CollectionError(Exception):
    """Safe, constant diagnostics only: no URL, key or provider response."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CollectionError("REDIRECT_REFUSED")


def fetch_page(key, video_id, order, limit):
    query = urllib.parse.urlencode({"key": key, "part": "snippet", "videoId": video_id,
                                   "order": order, "maxResults": limit,
                                   "textFormat": "plainText", "fields": FIELDS})
    request = urllib.request.Request("https://www.googleapis.com/youtube/v3/commentThreads?" + query)
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
        if len(raw) > MAX_RESPONSE_BYTES:
            raise CollectionError("RESPONSE_TOO_LARGE")
        result = json.loads(raw)
        if not isinstance(result, dict) or not isinstance(result.get("items"), list):
            raise CollectionError("INVALID_RESPONSE")
        return result
    except urllib.error.HTTPError as error:
        # Do not print error URL/body: request URLs contain the key.
        reason = ""
        try:
            body = json.loads(error.read(8192))
            reason = body["error"]["errors"][0]["reason"]
        except (ValueError, KeyError, IndexError, TypeError):
            pass
        safe_reasons = {"commentsDisabled", "quotaExceeded", "dailyLimitExceeded", "videoNotFound",
                        "keyInvalid", "accessNotConfigured", "ipRefererBlocked", "forbidden"}
        raise CollectionError(reason if isinstance(reason, str) and reason in safe_reasons
                              else "HTTP_" + str(error.code)) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise CollectionError("NETWORK_ERROR") from None
    except (ValueError, UnicodeError):
        raise CollectionError("INVALID_JSON") from None


def collect(key, video_ids, limit=50, fetch=fetch_page, now=None):
    if not isinstance(key, str) or not key.strip():
        raise CollectionError("YOUTUBE_API_KEY_NOT_SET")
    if not video_ids or len(video_ids) > 5 or len(set(video_ids)) != len(video_ids) or any(
            not isinstance(v, str) or not VIDEO_ID.fullmatch(v) for v in video_ids):
        raise CollectionError("INVALID_VIDEO_IDS")
    if type(limit) is not int or not 1 <= limit <= 100:
        raise CollectionError("LIMIT_MUST_BE_1_TO_100")
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if now.tzinfo is None:
        raise CollectionError("TIMEZONE_REQUIRED")
    fetched_at = now.isoformat()
    rows, sampling = {}, []
    for video_id in video_ids:
        for order in ("relevance", "time"):
            trace = {"videoId": video_id, "order": order, "requested": limit, "received": 0}
            # Stage each page so malformed items never leave a partially trusted page behind.
            page_rows = []
            try:
                page = fetch(key, video_id, order, limit)
                items = page["items"]
                if not isinstance(items, list) or len(items) > limit:
                    raise CollectionError("INVALID_RESPONSE")
                for item in items:
                    comment = item["snippet"]["topLevelComment"]
                    snippet = comment["snippet"]
                    comment_id, text = comment["id"], snippet["textDisplay"]
                    if not isinstance(comment_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", comment_id):
                        raise CollectionError("INVALID_COMMENT_ID")
                    if not isinstance(text, str) or not text.strip() or len(text) > 20000:
                        raise CollectionError("INVALID_COMMENT_TEXT")
                    for field in ("publishedAt", "updatedAt"):
                        value = snippet[field]
                        if not isinstance(value, str) or datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).tzinfo is None:
                            raise CollectionError("INVALID_COMMENT_TIME")
                    likes = snippet.get("likeCount", 0)
                    if type(likes) is not int or likes < 0:
                        raise CollectionError("INVALID_LIKE_COUNT")
                    page_rows.append({"commentId": comment_id, "videoId": video_id, "text": text,
                                      "publishedAt": snippet["publishedAt"], "updatedAt": snippet["updatedAt"],
                                      "likeCount": likes, "sampleOrders": [order]})
                for row in page_rows:
                    identity = (video_id, row["commentId"])
                    if identity in rows:
                        existing = rows[identity]
                        if order not in existing["sampleOrders"]:
                            existing["sampleOrders"].append(order)
                        # Preserve text from first observation; no inferred duplicate-content labels.
                    else:
                        rows[identity] = row
                trace.update(state="SUCCESS", received=len(items), hasMore=bool(page.get("nextPageToken")))
            except CollectionError as error:
                trace.update(state="FAILED", failureCode=str(error))
            except (KeyError, TypeError, ValueError, AttributeError):
                trace.update(state="FAILED", failureCode="INVALID_RESPONSE")
            sampling.append(trace)
    failures = sum(t["state"] == "FAILED" for t in sampling)
    return {"schemaVersion": "youtube-temporary-review-1", "collectedAt": fetched_at,
            "refreshOrDeleteBy": (now + datetime.timedelta(days=30)).isoformat(),
            "status": "COMPLETE_SAMPLE" if not failures else "PARTIAL_SAMPLE" if rows else "FAILED",
            "purpose": "TEMPORARY_REVIEW_ONLY", "approvalStatus": "UNREVIEWED",
            "datasetUseAuthorized": False, "trainingUseAuthorized": False, "privacyReviewed": False,
            "sampling": sampling, "comments": list(rows.values()),
            "limitations": ["Top-level comments only; not a complete or representative population.",
                            "No news narration, original-viewer requirement, labels or summaries collected.",
                            "No author metadata saved; personal information may remain in comment text.",
                            "Refresh/delete deadline recorded, not automatically enforced."]}


def save_snapshot(snapshot, directory):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    suffix = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = directory / ("youtube-comments-" + suffix + ".json")
    # Exclusive creation and private file mode: never replace an existing snapshot.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(snapshot, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video-id", action="append", help="Repeat for at most 5 video IDs")
    parser.add_argument("--limit-per-order", type=int, default=50)
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parents[1] / "uploads/comment-collections"))
    args = parser.parse_args()
    try:
        snapshot = collect(os.environ.get("YOUTUBE_API_KEY", ""), args.video_id or DEFAULT_VIDEOS, args.limit_per_order)
        path = save_snapshot(snapshot, args.output_dir)
    except CollectionError as error:
        parser.exit(2, str(error) + "\n")
    except OSError:
        parser.exit(2, "SNAPSHOT_WRITE_FAILED\n")
    print(json.dumps({"status": snapshot["status"], "uniqueComments": len(snapshot["comments"]),
                      "apiRequests": len(snapshot["sampling"]),
                      "sources": snapshot["sampling"], "savedTo": str(path),
                      "refreshOrDeleteBy": snapshot["refreshOrDeleteBy"]}, ensure_ascii=False, indent=2))
    if snapshot["status"] != "COMPLETE_SAMPLE":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
