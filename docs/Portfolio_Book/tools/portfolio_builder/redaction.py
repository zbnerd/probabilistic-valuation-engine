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


def _replace_sensitive_value(kind: str) -> Replacement:
    marker = _marker(kind)

    def replacement(match: re.Match[bytes]) -> bytes:
        value = match.group("value")
        if _CANONICAL_MARKER.fullmatch(value):
            return match.group(0)
        quote = match.group("quote") or b""
        closing = match.group("closing") or b""
        wrapper = quote if quote and closing == quote else b""
        return match.group("prefix") + wrapper + marker + wrapper

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
        "aws-secret-access-key",
        re.compile(
            rb"(?im)(?P<prefix>\b(?:aws_)?secret(?:_?access)?_?key\b[\"']?"
            rb"(?:[ \t]*\r?\n)?[ \t]*[:=](?:[ \t]*\r?\n)?[ \t]*)"
            rb"(?P<quote>[\"'])?(?P<value>"
            rb"\[REDACTED:aws-secret-access-key\][^\s\"'\r\n,}]*"
            rb"|[A-Za-z0-9/+=]{32,})(?P<closing>[\"'])?"
        ),
        _replace_sensitive_value("aws-secret-access-key"),
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
        re.compile(
            rb"\b(?i:https?|ssh)://[^\s/@:]+(?::[^\s/@]*)?@[^\s/]+(?:/[^\s]*)?"
        ),
        _replace_with("url-credentials"),
    ),
    (
        "credential-value",
        re.compile(
            rb"(?im)(?P<prefix>\b(?:password|passwd|secret|api[_-]?key|token)\b"
            rb"[ \t]*[:=][ \t]*)(?P<quote>[\"'])?(?P<value>"
            rb"\[REDACTED:[a-z0-9-]+\][^\s\"'\r\n,}]*"
            rb"|[^\s\"'`,;]+)(?P<closing>[\"'])?"
        ),
        _replace_sensitive_value("credential-value"),
    ),
)
_EMAIL = re.compile(
    rb"(?i)\b[A-Z0-9.!#$%&'*+/?^_`{|}~-]+@"
    rb"[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?"
    rb"(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+\b"
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
