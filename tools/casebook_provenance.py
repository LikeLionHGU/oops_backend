"""Shared validation for user-approved casebook decisions.

This verifies an approval record's integrity and scope. It does not establish
that the transcript is accurate, the interpretation is correct, or the label
is independent gold data.
"""

from review_context_pilot import require, text


def validate_approval_document(link, document, decision, selected_ids, source_sha256=None, video_id=None):
    require(isinstance(link, dict) and set(link) == {"path", "sha256", "eventIds"},
            "APPROVAL_RECORD_INVALID")
    event_ids = link["eventIds"]
    require(isinstance(event_ids, list) and event_ids
            and all(text(event_id, 100) for event_id in event_ids)
            and len(event_ids) == len(set(event_ids)), "APPROVAL_EVENT_IDS_REQUIRED")
    require(isinstance(document, dict) and isinstance(document.get("reviews"), list),
            "APPROVAL_REVIEWS_REQUIRED")
    selected = set(selected_ids)
    require(selected, "APPROVAL_SCOPE_EMPTY")
    require(isinstance(source_sha256, str) and len(source_sha256) == 64
            and all(ch in "0123456789abcdef" for ch in source_sha256)
            and type(video_id) is int and video_id > 0,
            "APPROVAL_SOURCE_BINDING_REQUIRED")
    approved_segments = set()
    for event_id in event_ids:
        matches = [review for review in document["reviews"]
                   if review.get("eventId") == event_id or event_id in review.get("eventIds", [])]
        require(len(matches) == 1, "UNIQUE_APPROVAL_REQUIRED")
        review = matches[0]
        require(review.get("decision") == decision and text(review.get("scope"), 2000)
                and (text(review.get("userReply"), 2000)
                     or text(review.get("userConfirmation"), 2000)),
                "EXPLICIT_MATCHING_USER_REVIEW_REQUIRED")
        segment_ids = review.get("segmentIds")
        require(review.get("sourceSha256") == source_sha256 and review.get("videoId") == video_id
                and isinstance(segment_ids, list) and segment_ids
                and all(text(segment_id, 100) for segment_id in segment_ids)
                and len(segment_ids) == len(set(segment_ids)) and set(segment_ids) <= selected,
                "APPROVAL_SOURCE_SCOPE_MISMATCH")
        anchors = ([review["anchorId"]] if review.get("anchorId")
                   else review.get("anchorIds", []))
        require(isinstance(anchors, list) and anchors and set(anchors) <= set(segment_ids),
                "APPROVAL_ANCHOR_OUTSIDE_SELECTION")
        approved_segments.update(segment_ids)
    require(approved_segments == selected, "APPROVAL_SELECTION_NOT_FULLY_REVIEWED")
    # One explicit user review may approve several event IDs as a single grouped decision.
    # It still must be unique for every requested ID; duplicating the review object in the
    # document makes that ID ambiguous and is rejected by the per-event check above.
    return link
