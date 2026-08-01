"""Byte-preserving, deterministic redaction for copied evidence.

The evidence pipeline retains an identity hash for the bytes it observed, but it
must never republish secret values or third-party contact details.  This module
therefore operates on bytes rather than decoded text: invalid UTF-8 remains
unchanged while ASCII-shaped credentials can still be replaced safely.
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass


OWNER_PUBLIC_EMAIL = "mps756@gmail.com"
_REDACTION_PREFIX = b"[REDACTED:"
_BINARY_MARKER = b"[REDACTED BINARY PAYLOAD]\n"
_BLOB_ID = re.compile(rb"[0-9A-Fa-f]{7,64}\Z")
_CANONICAL_MARKER = re.compile(rb"\[REDACTED:[a-z0-9-]+\]\Z")
_CANONICAL_MARKER_PREFIX = re.compile(rb"\[REDACTED:[a-z0-9-]+\]")
_STRUCTURAL_NEXT_FIELD = re.compile(
    rb"[ \t]*(?:"
    rb'"(?:[^"\\\r\n]|\\[^\r\n])*"'
    rb"|'(?:[^'\\\r\n]|\\[^\r\n])*'"
    rb"|[A-Za-z_][A-Za-z0-9_.-]*"
    rb")[ \t]*[:=]"
)

_AWS_SECRET_KEY_NAME = rb"(?:aws_?)?secret(?:_?access)?_?key"
_GENERIC_CREDENTIAL_KEY_NAME = rb"(?:password|passwd|secret|api[_-]?key|token)"

# These expressions only locate an assignment and its safe prefix.  A bounded
# byte parser below owns value boundaries, quoting, escapes, and delimiters.
_AWS_SECRET_ASSIGNMENT_START = re.compile(
    rb"(?im)(?P<prefix>(?<![A-Za-z0-9_-])(?:"
    + rb'"'
    + _AWS_SECRET_KEY_NAME
    + rb'"'
    + rb"|'"
    + _AWS_SECRET_KEY_NAME
    + rb"'|"
    + _AWS_SECRET_KEY_NAME
    + rb")[ \t]*(?:\r?\n[ \t]*)?[:=][ \t]*"
    + rb"(?:\r?\n[ \t]*(?=[\"']))?)"
)
_GENERIC_CREDENTIAL_ASSIGNMENT_START = re.compile(
    rb"(?im)(?P<prefix>(?<![A-Za-z0-9_-])(?:"
    + rb'"'
    + _GENERIC_CREDENTIAL_KEY_NAME
    + rb'"'
    + rb"|'"
    + _GENERIC_CREDENTIAL_KEY_NAME
    + rb"'|"
    + _GENERIC_CREDENTIAL_KEY_NAME
    + rb")[ \t]*[:=][ \t]*)"
)
_URL_CREDENTIALS = re.compile(
    rb"(?i)(?<![A-Za-z0-9+.-])"
    rb"[A-Za-z][A-Za-z0-9+.-]*://"
    rb"[^\s/@:]*:[^\s/@]+@"
    rb"[^\s/?#\"'<>]+(?:[/?#][^\s\"'<>]*)?"
)


@dataclass(frozen=True, slots=True)
class RedactionResult:
    """Safe bytes together with reproducible identities for raw and stored data."""

    value: bytes
    raw_hash: str
    stored_hash: str
    kinds: tuple[str, ...]


Replacement = Callable[[re.Match[bytes]], bytes]
Rule = tuple[str, re.Pattern[bytes], Replacement]


def _marker(kind: str) -> bytes:
    return _REDACTION_PREFIX + kind.encode("ascii") + b"]"


def _replace_with(kind: str) -> Replacement:
    marker = _marker(kind)

    def replacement(_: re.Match[bytes]) -> bytes:
        return marker

    return replacement


def _replace_header_value(kind: str) -> Replacement:
    marker = _marker(kind)

    def replacement(match: re.Match[bytes]) -> bytes:
        return match.group("prefix") + marker

    return replacement


# Rules are intentionally compiled once and ordered from structurally specific
# credential forms to broad contact matching.  Markers do not match their own
# rules, keeping a safe representation byte-idempotent.
_TEXT_RULES: tuple[Rule, ...] = (
    (
        "pem-private-key",
        re.compile(
            rb"-----BEGIN (?P<label>[A-Z0-9 ]*PRIVATE KEY)-----"
            rb"(?:"
            rb"(?:(?!-----BEGIN [A-Z0-9 ]+-----)[\s\S])*?"
            rb"-----END (?P=label)-----"
            rb"|(?:(?!-----BEGIN [A-Z0-9 ]+-----)[\s\S])*"
            rb"(?=-----BEGIN [A-Z0-9 ]+-----|\Z)"
            rb")"
        ),
        _replace_with("pem-private-key"),
    ),
    (
        "bearer-token",
        re.compile(rb"(?i:bearer)[ \t]+[A-Za-z0-9._~+/=-]+"),
        _replace_with("bearer-token"),
    ),
    (
        "github-token",
        re.compile(
            rb"\bgh(?:p|o|u|s|r)_[A-Za-z0-9_]{20,}\b"
            rb"|\bgithub_pat_[A-Za-z0-9_]{20,}\b"
        ),
        _replace_with("github-token"),
    ),
    (
        "aws-access-key",
        re.compile(rb"\b(?:AKIA|ASIA|A3T[A-Z0-9])[A-Z0-9]{16}\b"),
        _replace_with("aws-access-key"),
    ),
    (
        "cookie",
        re.compile(
            rb"(?im)^(?![+\- ]*[ \t]*(?:set-)?cookie[ \t]*:[ \t]*"
            rb"\[REDACTED:cookie\][ \t]*\r?$)"
            rb"(?P<prefix>[+\- ]*[ \t]*(?:set-)?cookie[ \t]*:[ \t]*)[^\r\n]*"
        ),
        _replace_header_value("cookie"),
    ),
    (
        "url-credentials",
        _URL_CREDENTIALS,
        _replace_with("url-credentials"),
    ),
)
_EMAIL = re.compile(
    rb"(?i)(?<![A-Z0-9.!#$%&'*+/?^_`{|}~-])(?:"
    rb'"(?:[\x20-\x21\x23-\x5b\x5d-\x7e]|\\[\x20-\x7e])*"'
    rb"|[A-Z0-9.!#$%&'*+/?^_`{|}~-]+"
    rb")@"
    rb"[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?"
    rb"(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9-])"
)


@dataclass(frozen=True, slots=True)
class _ScalarSpan:
    end: int
    opening_quote: int | None
    balanced: bool


def _record_content_end(data: bytes, start: int) -> int:
    """Return the CR/LF-exclusive end of the record containing ``start``."""

    carriage_return = data.find(b"\r", start)
    line_feed = data.find(b"\n", start)
    boundaries = tuple(
        boundary for boundary in (carriage_return, line_feed) if boundary >= 0
    )
    return min(boundaries, default=len(data))


def _closing_chain_is_safe(tail: bytes) -> bool:
    """Accept only complete closing-delimiter chains and safe continuations."""

    offset = len(tail) - len(tail.lstrip(b" \t"))
    saw_closer = False
    while offset < len(tail) and tail[offset] in (0x7D, 0x5D):
        saw_closer = True
        offset += 1
        while offset < len(tail) and tail[offset] in (0x20, 0x09):
            offset += 1
    if not saw_closer:
        return False
    if offset == len(tail):
        return True
    if tail[offset] not in (0x2C, 0x3B):
        return False
    continuation = tail[offset + 1 :]
    return _STRUCTURAL_NEXT_FIELD.match(continuation) is not None


def _has_structural_tail(data: bytes, delimiter: int, record_end: int) -> bool:
    """Accept separators only before another field or a complete closer chain."""

    tail = data[delimiter + 1 : record_end]
    if not tail.strip(b" \t"):
        return True
    return (
        _STRUCTURAL_NEXT_FIELD.match(tail) is not None
        or _closing_chain_is_safe(tail)
    )


def _bounded_scalar_span(data: bytes, start: int) -> _ScalarSpan:
    """Parse one scalar without crossing its CR/LF-delimited source record.

    Backslash escapes and doubled quote characters remain inside a quoted
    scalar.  Commas and semicolons terminate it only when the following bytes
    are recognisable as a new field; closing braces/brackets always terminate
    it.  This makes an arbitrary suffix part of the sensitive span instead of
    letting a token-oriented regular expression leave it behind.
    """

    record_end = _record_content_end(data, start)
    if start >= record_end:
        return _ScalarSpan(start, None, True)

    opening_quote = data[start] if data[start] in (0x22, 0x27) else None
    active_quote = opening_quote
    index = start + 1 if opening_quote is not None else start
    if opening_quote is None:
        existing_marker = _CANONICAL_MARKER_PREFIX.match(data, start, record_end)
        if existing_marker is not None:
            index = existing_marker.end()

    while index < record_end:
        current = data[index]
        if active_quote is not None:
            if current == 0x5C:
                index = min(index + 2, record_end)
                continue
            if current == active_quote:
                if index + 1 < record_end and data[index + 1] == active_quote:
                    index += 2
                    continue
                active_quote = None
            index += 1
            continue

        if current in (0x22, 0x27):
            active_quote = current
            index += 1
            continue
        if current in (0x7D, 0x5D) and _closing_chain_is_safe(
            data[index:record_end]
        ):
            break
        if current in (0x2C, 0x3B) and _has_structural_tail(
            data, index, record_end
        ):
            break
        index += 1

    semantic_end = index
    while semantic_end > start and data[semantic_end - 1] in (0x20, 0x09):
        semantic_end -= 1
    return _ScalarSpan(semantic_end, opening_quote, active_quote is None)


def _is_canonical_scalar(value: bytes) -> bool:
    candidate = value.strip(b" \t")
    if _CANONICAL_MARKER.fullmatch(candidate):
        return True
    if (
        len(candidate) >= 2
        and candidate[0] in (0x22, 0x27)
        and candidate[-1] == candidate[0]
    ):
        return _CANONICAL_MARKER.fullmatch(candidate[1:-1]) is not None
    return False


def _redact_assignment_values(
    data: bytes, candidate_rule: re.Pattern[bytes], kind: str
) -> tuple[bytes, bool]:
    """Redact candidate assignment values using complete bounded spans."""

    chunks: list[bytes] = []
    copied_through = 0
    search_from = 0
    changed = False
    marker = _marker(kind)

    while match := candidate_rule.search(data, search_from):
        value_start = match.end("prefix")
        span = _bounded_scalar_span(data, value_start)
        if span.end <= value_start:
            search_from = max(match.end(), value_start + 1)
            continue

        scalar = data[value_start : span.end]
        if _is_canonical_scalar(scalar):
            search_from = span.end
            continue

        replacement = marker
        if span.opening_quote is not None and span.balanced:
            quote = bytes((span.opening_quote,))
            replacement = quote + marker + quote

        chunks.extend((data[copied_through:value_start], replacement))
        copied_through = span.end
        search_from = span.end
        changed = True

    if not changed:
        return data, False
    chunks.append(data[copied_through:])
    return b"".join(chunks), True


def _as_bytes(data: bytes | bytearray | memoryview) -> bytes:
    if not isinstance(data, bytes | bytearray | memoryview):
        raise TypeError("redaction input must be bytes-like")
    return bytes(data)


def _allowed_contact_set(
    allowed_contacts: Iterable[str | bytes] | None,
) -> frozenset[bytes]:
    contacts = (OWNER_PUBLIC_EMAIL,) if allowed_contacts is None else allowed_contacts
    normalized: set[bytes] = set()
    for contact in contacts:
        if isinstance(contact, str):
            encoded = contact.encode("ascii")
        elif isinstance(contact, bytes):
            encoded = contact
        else:
            raise TypeError("allowed contacts must be str or bytes")
        normalized.add(encoded.lower())
    return frozenset(normalized)


def _result(value: bytes, raw: bytes, kinds: set[str]) -> RedactionResult:
    return RedactionResult(
        value=value,
        raw_hash=hashlib.sha256(raw).hexdigest(),
        stored_hash=hashlib.sha256(value).hexdigest(),
        kinds=tuple(sorted(kinds)),
    )


def redact_text(
    data: bytes | bytearray | memoryview,
    allowed_contacts: Iterable[str | bytes] | None = None,
) -> RedactionResult:
    """Redact credentials and unapproved emails without decoding copied bytes.

    The returned hashes always identify the supplied raw bytes and the safe
    stored bytes respectively.  No source value is logged or included in an
    exception message.
    """

    raw = _as_bytes(data)
    value = raw
    kinds: set[str] = set()
    for kind, pattern, replacement in _TEXT_RULES:
        redacted = pattern.sub(replacement, value)
        if redacted != value:
            kinds.add(kind)
        value = redacted

    value, changed = _redact_assignment_values(
        value, _AWS_SECRET_ASSIGNMENT_START, "aws-secret-access-key"
    )
    if changed:
        kinds.add("aws-secret-access-key")
    value, changed = _redact_assignment_values(
        value, _GENERIC_CREDENTIAL_ASSIGNMENT_START, "credential-value"
    )
    if changed:
        kinds.add("credential-value")

    allowed = _allowed_contact_set(allowed_contacts)

    def redact_email(match: re.Match[bytes]) -> bytes:
        candidate = match.group(0)
        if candidate.lower() in allowed:
            return candidate
        kinds.add("third-party-email")
        return _marker("third-party-email")

    value = _EMAIL.sub(redact_email, value)
    return _result(value, raw, kinds)


def _safe_blob_metadata(blob_metadata: Mapping[object, object]) -> bytes:
    if not isinstance(blob_metadata, Mapping):
        raise TypeError("blob metadata must be a mapping")

    lines: list[bytes] = []
    for key in ("old_blob", "new_blob"):
        value = blob_metadata.get(key)
        if isinstance(value, str):
            try:
                encoded = value.encode("ascii")
            except UnicodeEncodeError:
                continue
            if _BLOB_ID.fullmatch(encoded):
                lines.append(key.encode("ascii") + b": " + encoded + b"\n")
    for key in ("old_size", "new_size"):
        value = blob_metadata.get(key)
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
            lines.append(
                key.encode("ascii") + b": " + str(value).encode("ascii") + b"\n"
            )
    return b"".join(lines)


def redact_binary_patch(
    data: bytes | bytearray | memoryview,
    blob_metadata: Mapping[object, object],
) -> RedactionResult:
    """Replace an unscannable binary patch with fixed, allowlisted metadata.

    The original bytes influence only ``raw_hash``.  Arbitrary metadata keys or
    values are intentionally discarded so they cannot smuggle copied payloads
    or secrets into the stored representation.
    """

    raw = _as_bytes(data)
    value = _safe_blob_metadata(blob_metadata) + _BINARY_MARKER
    return _result(value, raw, {"binary-patch"})
