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
_CANONICAL_MARKER = re.compile(
    rb"\[REDACTED:(?P<kind>[a-z0-9-]+)\]\Z"
)
_JSON_PRIMITIVE = re.compile(
    rb"(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)"
)
_JSON_WHITESPACE = b" \t\r\n"

_AWS_ACCESS_KEY_NAME = rb"(?:aws_?access_?key_?id|access_?key_?id)"
_AWS_SECRET_KEY_NAME = rb"(?:aws_?secret_?access_?key|secret_?access_?key)"
_GITHUB_TOKEN_KEY_NAME = rb"(?:github[_-]?(?:token|pat)|gh[_-]?token|token)"
_GENERIC_CREDENTIAL_KEY_NAME = rb"(?:password|passwd|secret|api[_-]?key|token)"


def _compile_line_assignment_start(key_name: bytes) -> re.Pattern[bytes]:
    """Locate line-bound assignments without granting JSON delimiters meaning."""

    return re.compile(
        rb"(?im)(?P<prefix>(?<![A-Za-z0-9_-])(?:"
        + rb'(?:"'
        + key_name
        + rb'"|\''
        + key_name
        + rb"')[ \t]*=|"
        + key_name
        + rb"[ \t]*[:=])[ \t]*)"
    )


def _compile_json_member_start(key_name: bytes) -> re.Pattern[bytes]:
    """Locate quoted-key/colon candidates; lexical proof is performed later."""

    return re.compile(
        rb'(?i)(?P<key>"'
        + key_name
        + rb'")(?P<separator>[ \t\r\n]*:[ \t\r\n]*)'
    )


def _compile_quoted_colon_start(key_name: bytes) -> re.Pattern[bytes]:
    """Locate same-record quoted-key/colon candidates for conservative fallback."""

    return re.compile(
        rb'(?i)(?P<key>"'
        + key_name
        + rb'")(?P<separator>[ \t]*:[ \t]*)'
    )


# Candidate regexes locate field starts only.  Explicit parsers below decide
# whether the grammar is a proven JSON object member or an unstructured record.
_AWS_ACCESS_LINE_START = _compile_line_assignment_start(_AWS_ACCESS_KEY_NAME)
_AWS_SECRET_LINE_START = _compile_line_assignment_start(_AWS_SECRET_KEY_NAME)
_GITHUB_TOKEN_LINE_START = _compile_line_assignment_start(
    _GITHUB_TOKEN_KEY_NAME
)
_GENERIC_CREDENTIAL_LINE_START = _compile_line_assignment_start(
    _GENERIC_CREDENTIAL_KEY_NAME
)

_AWS_ACCESS_JSON_START = _compile_json_member_start(_AWS_ACCESS_KEY_NAME)
_AWS_SECRET_JSON_START = _compile_json_member_start(_AWS_SECRET_KEY_NAME)
_GITHUB_TOKEN_JSON_START = _compile_json_member_start(
    _GITHUB_TOKEN_KEY_NAME
)
_GENERIC_CREDENTIAL_JSON_START = _compile_json_member_start(
    _GENERIC_CREDENTIAL_KEY_NAME
)

_AWS_ACCESS_QUOTED_COLON_START = _compile_quoted_colon_start(
    _AWS_ACCESS_KEY_NAME
)
_AWS_SECRET_QUOTED_COLON_START = _compile_quoted_colon_start(
    _AWS_SECRET_KEY_NAME
)
_GITHUB_TOKEN_QUOTED_COLON_START = _compile_quoted_colon_start(
    _GITHUB_TOKEN_KEY_NAME
)
_GENERIC_CREDENTIAL_QUOTED_COLON_START = _compile_quoted_colon_start(
    _GENERIC_CREDENTIAL_KEY_NAME
)

_AUTHORIZATION_BEARER_START = re.compile(
    rb"(?im)^(?P<prefix>[+\- ]*[ \t]*authorization[ \t]*:[ \t]*"
    rb"bearer[ \t]+)"
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


def _complete_line_quote(value: bytes) -> int | None:
    """Return a quote wrapper only when it encloses the whole line scalar."""

    if not value or value[0] not in (0x22, 0x27):
        return None
    quote = value[0]
    index = 1
    while index < len(value):
        current = value[index]
        if current == 0x5C:
            index += 2
            continue
        if current == quote:
            if index + 1 < len(value) and value[index + 1] == quote:
                index += 2
                continue
            return quote if index == len(value) - 1 else None
        index += 1
    return None


def _line_scalar_span(data: bytes, start: int) -> _ScalarSpan:
    """Treat every byte through CR/LF as one unstructured sensitive value."""

    record_end = _record_content_end(data, start)
    semantic_end = record_end
    while semantic_end > start and data[semantic_end - 1] in (0x20, 0x09):
        semantic_end -= 1
    if semantic_end <= start:
        return _ScalarSpan(start, None, True)
    quote = _complete_line_quote(data[start:semantic_end])
    return _ScalarSpan(semantic_end, quote, quote is not None)


def _canonical_scalar_kind(value: bytes) -> bytes | None:
    """Return the marker kind only for a complete semantic scalar."""

    candidate = value.strip(b" \t")
    match = _CANONICAL_MARKER.fullmatch(candidate)
    if match is not None:
        return match.group("kind")
    quote = _complete_line_quote(candidate)
    if quote is None:
        return None
    match = _CANONICAL_MARKER.fullmatch(candidate[1:-1])
    return None if match is None else match.group("kind")


def _is_canonical_scalar(value: bytes, kind: str | None = None) -> bool:
    marker_kind = _canonical_scalar_kind(value)
    if marker_kind is None:
        return False
    return kind is None or marker_kind == kind.encode("ascii")


def _json_member_contexts(
    data: bytes, candidate_starts: set[int]
) -> frozenset[int]:
    """Prove quoted-key candidates are outside strings in an object container."""

    if not candidate_starts:
        return frozenset()

    proven: set[int] = set()
    containers: list[int] = []
    in_string = False
    escaped = False
    last_significant: int | None = None

    for index, current in enumerate(data):
        if index in candidate_starts:
            if (
                not in_string
                and containers
                and containers[-1] == 0x7B
                and last_significant in (0x7B, 0x2C)
            ):
                proven.add(index)

        if in_string:
            if escaped:
                escaped = False
            elif current == 0x5C:
                escaped = True
            elif current == 0x22:
                in_string = False
                last_significant = current
            elif current in (0x0D, 0x0A):
                # Raw record boundaries invalidate a JSON string.  Resetting
                # lets a later independent JSON record prove its own context.
                in_string = False
                escaped = False
                containers.clear()
                last_significant = None
            continue

        if current in _JSON_WHITESPACE:
            continue
        if current == 0x22:
            in_string = True
            escaped = False
        elif current in (0x7B, 0x5B):
            containers.append(current)
        elif current in (0x7D, 0x5D):
            expected = 0x7B if current == 0x7D else 0x5B
            if containers and containers[-1] == expected:
                containers.pop()
            else:
                containers.clear()
        last_significant = current

    return frozenset(proven)


def _json_string_end(data: bytes, start: int) -> int | None:
    """Return the exclusive end of one valid, record-bounded JSON string."""

    if start >= len(data) or data[start] != 0x22:
        return None
    index = start + 1
    while index < len(data):
        current = data[index]
        if current in (0x0D, 0x0A):
            return None
        if current == 0x5C:
            if index + 1 >= len(data) or data[index + 1] in (0x0D, 0x0A):
                return None
            index += 2
            continue
        if current == 0x22:
            return index + 1
        index += 1
    return None


def _skip_json_whitespace(data: bytes, start: int) -> int:
    index = start
    while index < len(data) and data[index] in _JSON_WHITESPACE:
        index += 1
    return index


def _json_member_continuation(data: bytes, start: int) -> bool:
    """Accept only an object closer or a syntactically quoted next member."""

    index = _skip_json_whitespace(data, start)
    if index >= len(data):
        return False
    if data[index] == 0x7D:
        return True
    if data[index] != 0x2C:
        return False

    key_start = _skip_json_whitespace(data, index + 1)
    key_end = _json_string_end(data, key_start)
    if key_end is None:
        return False
    colon = _skip_json_whitespace(data, key_end)
    return colon < len(data) and data[colon] == 0x3A


def _json_scalar_span(data: bytes, start: int) -> _ScalarSpan:
    """Parse a scalar only after a JSON object-member context was proven."""

    if start >= len(data):
        return _ScalarSpan(start, None, True)

    if data[start] == 0x22:
        string_end = _json_string_end(data, start)
        if string_end is not None and _json_member_continuation(data, string_end):
            return _ScalarSpan(string_end, 0x22, True)
        return _line_scalar_span(data, start)

    primitive = _JSON_PRIMITIVE.match(data, start)
    if primitive is not None and _json_member_continuation(data, primitive.end()):
        return _ScalarSpan(primitive.end(), None, True)
    return _line_scalar_span(data, start)


ScalarPredicate = Callable[[bytes], bool]


def _replace_scalar(
    marker: bytes, span: _ScalarSpan
) -> bytes:
    if span.opening_quote is None or not span.balanced:
        return marker
    quote = bytes((span.opening_quote,))
    return quote + marker + quote


def _redact_line_values(
    data: bytes,
    candidate_rule: re.Pattern[bytes],
    kind: str,
    predicate: ScalarPredicate | None = None,
) -> tuple[bytes, bool]:
    """Redact complete CR/LF-bounded assignment or header values."""

    chunks: list[bytes] = []
    copied_through = 0
    changed = False
    marker = _marker(kind)
    accepted_marker_kind = None if kind == "credential-value" else kind

    for match in candidate_rule.finditer(data):
        if match.start() < copied_through:
            continue
        value_start = match.end("prefix")
        span = _line_scalar_span(data, value_start)
        if span.end <= value_start:
            continue
        scalar = data[value_start : span.end]
        if predicate is not None and not predicate(scalar):
            continue
        if _is_canonical_scalar(scalar, accepted_marker_kind):
            continue

        chunks.extend(
            (
                data[copied_through:value_start],
                _replace_scalar(marker, span),
            )
        )
        copied_through = span.end
        changed = True

    if not changed:
        return data, False
    chunks.append(data[copied_through:])
    return b"".join(chunks), True


def _redact_json_member_values(
    data: bytes,
    candidate_rule: re.Pattern[bytes],
    kind: str,
    predicate: ScalarPredicate | None = None,
) -> tuple[bytes, bool]:
    """Redact scalars only for lexically proven JSON object members."""

    matches = tuple(candidate_rule.finditer(data))
    proven = _json_member_contexts(
        data, {match.start("key") for match in matches}
    )
    chunks: list[bytes] = []
    copied_through = 0
    changed = False
    marker = _marker(kind)
    accepted_marker_kind = None if kind == "credential-value" else kind

    for match in matches:
        if match.start() < copied_through or match.start("key") not in proven:
            continue
        value_start = match.end("separator")
        span = _json_scalar_span(data, value_start)
        if span.end <= value_start:
            continue
        scalar = data[value_start : span.end]
        if predicate is not None and not predicate(scalar):
            continue
        if _is_canonical_scalar(scalar, accepted_marker_kind):
            continue

        chunks.extend(
            (
                data[copied_through:value_start],
                _replace_scalar(marker, span),
            )
        )
        copied_through = span.end
        changed = True

    if not changed:
        return data, False
    chunks.append(data[copied_through:])
    return b"".join(chunks), True


def _redact_unproven_quoted_colon_values(
    data: bytes,
    candidate_rule: re.Pattern[bytes],
    kind: str,
    predicate: ScalarPredicate | None = None,
) -> tuple[bytes, bool]:
    """Line-redact quoted colon fields that lack proven JSON object context."""

    matches = tuple(candidate_rule.finditer(data))
    proven = _json_member_contexts(
        data, {match.start("key") for match in matches}
    )
    chunks: list[bytes] = []
    copied_through = 0
    changed = False
    marker = _marker(kind)
    accepted_marker_kind = None if kind == "credential-value" else kind

    for match in matches:
        if match.start() < copied_through or match.start("key") in proven:
            continue
        value_start = match.end("separator")
        span = _line_scalar_span(data, value_start)
        if span.end <= value_start:
            continue
        scalar = data[value_start : span.end]
        if predicate is not None and not predicate(scalar):
            continue
        if _is_canonical_scalar(scalar, accepted_marker_kind):
            continue

        chunks.extend(
            (
                data[copied_through:value_start],
                _replace_scalar(marker, span),
            )
        )
        copied_through = span.end
        changed = True

    if not changed:
        return data, False
    chunks.append(data[copied_through:])
    return b"".join(chunks), True


def _github_token_scalar(value: bytes) -> bool:
    candidate = value.strip(b" \t")
    if b"[REDACTED:github-token]" in candidate:
        return True
    candidate = candidate.lstrip(b"\"'").lower()
    return candidate.startswith(
        (b"ghp_", b"gho_", b"ghu_", b"ghs_", b"ghr_", b"github_pat_")
    )


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

    def apply_parser(
        parser: Callable[..., tuple[bytes, bool]],
        candidate_rule: re.Pattern[bytes],
        kind: str,
        predicate: ScalarPredicate | None = None,
    ) -> None:
        nonlocal value
        value, changed = parser(value, candidate_rule, kind, predicate)
        if changed:
            kinds.add(kind)

    # Field-aware parsers run before token regexes so a fixed marker cannot
    # split one semantic credential into individually harmless-looking pieces.
    apply_parser(
        _redact_line_values,
        _AUTHORIZATION_BEARER_START,
        "bearer-token",
    )

    field_grammars = (
        (
            _AWS_ACCESS_JSON_START,
            _AWS_ACCESS_LINE_START,
            _AWS_ACCESS_QUOTED_COLON_START,
            "aws-access-key",
            None,
        ),
        (
            _AWS_SECRET_JSON_START,
            _AWS_SECRET_LINE_START,
            _AWS_SECRET_QUOTED_COLON_START,
            "aws-secret-access-key",
            None,
        ),
        (
            _GITHUB_TOKEN_JSON_START,
            _GITHUB_TOKEN_LINE_START,
            _GITHUB_TOKEN_QUOTED_COLON_START,
            "github-token",
            _github_token_scalar,
        ),
        (
            _GENERIC_CREDENTIAL_JSON_START,
            _GENERIC_CREDENTIAL_LINE_START,
            _GENERIC_CREDENTIAL_QUOTED_COLON_START,
            "credential-value",
            None,
        ),
    )
    for json_rule, line_rule, fallback_rule, kind, predicate in field_grammars:
        apply_parser(
            _redact_json_member_values,
            json_rule,
            kind,
            predicate,
        )
        apply_parser(
            _redact_line_values,
            line_rule,
            kind,
            predicate,
        )
        apply_parser(
            _redact_unproven_quoted_colon_values,
            fallback_rule,
            kind,
            predicate,
        )

    for kind, pattern, replacement in _TEXT_RULES:
        redacted = pattern.sub(replacement, value)
        if redacted != value:
            kinds.add(kind)
        value = redacted

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
