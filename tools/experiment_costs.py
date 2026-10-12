"""Historical reservation arithmetic only; not a tokenizer or billing guarantee."""
from decimal import Decimal
import json

from fixed_review_suite import require


def positive(value):
    require(type(value) in (int, float, str), "positive number required")
    number = Decimal(str(value))
    require(number.is_finite() and number > 0, "positive number required")
    return number


def reserve(body, config):
    # Keep the original byte-count heuristic exactly for frozen/hash compatibility.
    tokens = len(json.dumps(body, ensure_ascii=False).encode("utf-8")) + 2000
    pricing = config["pricing"]
    cost = (Decimal(tokens) * positive(pricing["inputUsdPerMillion"])
            + Decimal(config["maxCompletionTokens"]) * positive(pricing["outputUsdPerMillion"])) / 1000000
    return tokens, cost
