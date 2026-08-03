"""Small, resumable GitHub REST client for evidence collection."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Mapping, Protocol


API_ROOT = "https://api.github.com"
DEFAULT_ACCEPT = "application/vnd.github+json"
PATCH_ACCEPT = "application/vnd.github.patch"
API_VERSION = "2022-11-28"
MAX_ATTEMPTS = 3


class GitHubClientError(RuntimeError):
    """A safe request failure which never includes response or credential data."""


@dataclass(frozen=True, slots=True)
class HttpResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes = b""


class Transport(Protocol):
    def request(self, url: str, headers: Mapping[str, str]) -> HttpResponse: ...


class Clock(Protocol):
    def now(self) -> float: ...

    def sleep(self, seconds: float) -> None: ...


class UrlLibTransport:
    """Production transport. HTTP status responses remain data for client policy."""

    def request(self, url: str, headers: Mapping[str, str]) -> HttpResponse:
        request = urllib.request.Request(url, headers=dict(headers), method="GET")
        try:
            with urllib.request.urlopen(request) as response:
                return HttpResponse(
                    status=response.status,
                    headers=dict(response.headers.items()),
                    body=response.read(),
                )
        except urllib.error.HTTPError as error:
            return HttpResponse(
                status=error.code,
                headers=dict(error.headers.items()) if error.headers else {},
                body=error.read(),
            )


class SystemClock:
    def now(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        time.sleep(seconds)


@dataclass(frozen=True, slots=True)
class RateLimitState:
    remaining: int | None = None
    reset_at: int | None = None

    @classmethod
    def from_headers(cls, headers: Mapping[str, str]) -> RateLimitState:
        remaining = _integer_header(headers, "X-RateLimit-Remaining")
        reset_at = _integer_header(headers, "X-RateLimit-Reset")
        return cls(remaining=remaining, reset_at=reset_at)


@dataclass(frozen=True, slots=True)
class Checkpoint:
    accept: str
    endpoint: str
    params: dict[str, object]
    etag: str | None
    fetched_at: str
    response_hash: str
    page_number: int
    availability_status: str
    status_code: int


@dataclass(frozen=True, slots=True)
class GitHubPage:
    endpoint: str
    params: dict[str, object]
    page_number: int
    body: bytes
    json: object
    response_hash: str
    availability_status: str
    status_code: int
    fetched_at: str


def _canonical_params(params: Mapping[str, object] | None) -> dict[str, object]:
    return {key: value for key, value in sorted((params or {}).items())}


def _header(headers: Mapping[str, str], name: str) -> str | None:
    lowered = name.casefold()
    return next(
        (value for key, value in headers.items() if key.casefold() == lowered), None
    )


def _integer_header(headers: Mapping[str, str], name: str) -> int | None:
    value = _header(headers, name)
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None


def is_exact_patch_variant(
    endpoint: str,
    params: Mapping[str, object],
    page_number: int,
    accept: str,
) -> bool:
    """Identify the sole request variant where GitHub 406 is terminal evidence."""
    return (
        accept == PATCH_ACCEPT
        and not params
        and page_number == 1
        and re.fullmatch(r"/repos/[^/]+/[^/]+/pulls/[0-9]+\.patch", endpoint)
        is not None
    )


class CheckpointStore:
    """Deterministic checkpoint metadata plus a hash-verified local body cache."""

    def __init__(self, root: str | Path):
        self.root = Path(root)

    def _stem(
        self,
        endpoint: str,
        params: Mapping[str, object],
        page_number: int,
        accept: str,
    ) -> str:
        identity = json.dumps(
            {
                "accept": accept,
                "endpoint": endpoint,
                "page_number": page_number,
                "params": _canonical_params(params),
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(identity).hexdigest()

    def load(
        self,
        endpoint: str,
        params: Mapping[str, object],
        page_number: int,
        accept: str = DEFAULT_ACCEPT,
    ) -> Checkpoint | None:
        path = self.root / f"{self._stem(endpoint, params, page_number, accept)}.json"
        if not path.is_file():
            return None
        payload = json.loads(path.read_text(encoding="utf-8"))
        stored_accept = payload.get("accept")
        if stored_accept is None:
            payload["accept"] = accept
        elif stored_accept != accept:
            raise GitHubClientError(
                f"checkpoint accept mismatch: endpoint={endpoint}"
            )
        if "status_code" not in payload:
            if payload.get("availability_status") != "available":
                raise GitHubClientError(
                    f"checkpoint lacks terminal status: endpoint={endpoint}"
                )
            payload["status_code"] = 200
        return Checkpoint(**payload)

    def load_body(
        self,
        checkpoint: Checkpoint,
        accept: str = DEFAULT_ACCEPT,
    ) -> bytes:
        stem = self._stem(
            checkpoint.endpoint, checkpoint.params, checkpoint.page_number, accept
        )
        body = (self.root / f"{stem}.body").read_bytes()
        if hashlib.sha256(body).hexdigest() != checkpoint.response_hash:
            raise GitHubClientError(
                f"checkpoint body hash mismatch: endpoint={checkpoint.endpoint}"
            )
        return body

    def load_next(
        self,
        checkpoint: Checkpoint,
        accept: str = DEFAULT_ACCEPT,
    ) -> str | None:
        stem = self._stem(
            checkpoint.endpoint, checkpoint.params, checkpoint.page_number, accept
        )
        path = self.root / f"{stem}.next"
        return path.read_text(encoding="utf-8") if path.is_file() else None

    def save(
        self,
        checkpoint: Checkpoint,
        body: bytes,
        accept: str = DEFAULT_ACCEPT,
        next_url: str | None = None,
    ) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        stem = self._stem(
            checkpoint.endpoint, checkpoint.params, checkpoint.page_number, accept
        )
        metadata = json.dumps(
            asdict(checkpoint),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8") + b"\n"
        self._replace(self.root / f"{stem}.body", body)
        next_path = self.root / f"{stem}.next"
        if next_url is None:
            next_path.unlink(missing_ok=True)
        else:
            self._replace(next_path, next_url.encode("utf-8"))
        self._replace(self.root / f"{stem}.json", metadata)

    def _replace(self, target: Path, value: bytes) -> None:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{target.name}.", suffix=".tmp", dir=self.root
        )
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)


class GitHubClient:
    def __init__(
        self,
        *,
        checkpoint_store: CheckpointStore,
        transport: Transport | None = None,
        clock: Clock | None = None,
        token: str | None = None,
    ):
        self._transport = transport or UrlLibTransport()
        self._clock = clock or SystemClock()
        self._checkpoints = checkpoint_store
        self._token = token if token is not None else self._resolve_token()
        self.rate_limit_state = RateLimitState()

    def _resolve_token(self) -> str:
        environment_token = os.environ.get("GITHUB_TOKEN", "").strip()
        if environment_token:
            return environment_token
        completed = subprocess.run(
            ["gh", "auth", "token"],
            check=False,
            capture_output=True,
            text=True,
        )
        token = completed.stdout.strip()
        if completed.returncode != 0 or not token:
            raise GitHubClientError("GitHub credentials are unavailable")
        return token

    def get_pages(
        self,
        path: str,
        params: Mapping[str, object] | None = None,
        accept: str = DEFAULT_ACCEPT,
    ) -> tuple[GitHubPage, ...]:
        endpoint = path
        current_params = _canonical_params(params)
        url = self._url(path, current_params)
        page_number = int(current_params.get("page", 1))
        pages: list[GitHubPage] = []
        while url is not None:
            page, next_url = self._fetch_page(
                url, endpoint, current_params, page_number, accept, parse_json=True
            )
            if page.availability_status == "confirmed-unavailable":
                return tuple((*pages, page))
            pages.append(page)
            url = next_url
            if url is not None:
                page_number += 1
                current_params = _params_from_url(url)
        return tuple(pages)

    def get_json(
        self,
        path: str,
        params: Mapping[str, object] | None = None,
        accept: str = DEFAULT_ACCEPT,
    ) -> object | None:
        page, _ = self._fetch_page(
            self._url(path, params),
            path,
            _canonical_params(params),
            1,
            accept,
            parse_json=True,
        )
        return None if page.availability_status == "confirmed-unavailable" else page.json

    def get_bytes(
        self,
        path: str,
        params: Mapping[str, object] | None = None,
        accept: str = DEFAULT_ACCEPT,
    ) -> bytes | None:
        page = self.get_bytes_page(path, params, accept)
        return None if page.availability_status == "confirmed-unavailable" else page.body

    def get_bytes_page(
        self,
        path: str,
        params: Mapping[str, object] | None = None,
        accept: str = DEFAULT_ACCEPT,
    ) -> GitHubPage:
        """Return byte-response provenance, including terminal availability."""
        page, _ = self._fetch_page(
            self._url(path, params),
            path,
            _canonical_params(params),
            1,
            accept,
            parse_json=False,
        )
        return page

    def _fetch_page(
        self,
        url: str,
        endpoint: str,
        params: dict[str, object],
        page_number: int,
        accept: str,
        *,
        parse_json: bool,
    ) -> tuple[GitHubPage, str | None]:
        prior = self._checkpoints.load(endpoint, params, page_number, accept)
        headers = {
            "Accept": accept,
            "Authorization": f"Bearer {self._token}",
            "X-GitHub-Api-Version": API_VERSION,
        }
        if prior is not None and prior.etag:
            headers["If-None-Match"] = prior.etag

        server_error_attempt = 0
        transport_error_attempt = 0
        for attempt in range(MAX_ATTEMPTS):
            try:
                response = self._transport.request(url, headers)
            except (ConnectionError, urllib.error.URLError, TimeoutError):
                if attempt + 1 < MAX_ATTEMPTS:
                    self._clock.sleep(float(2**transport_error_attempt))
                    transport_error_attempt += 1
                    continue
                raise GitHubClientError(
                    f"github retries exhausted: endpoint={endpoint}"
                ) from None
            transport_error_attempt = 0
            self.rate_limit_state = RateLimitState.from_headers(response.headers)
            if response.status == 304:
                if prior is None:
                    raise GitHubClientError(
                        f"304 response without checkpoint: endpoint={endpoint}"
                    )
                body = self._checkpoints.load_body(prior, accept)
                return (
                    self._page(prior, body, parse_json=parse_json),
                    self._checkpoints.load_next(prior, accept),
                )
            if response.status == 200:
                checkpoint = self._checkpoint(
                    endpoint, params, page_number, accept, response, "available"
                )
                next_url = _next_link(response.headers)
                self._checkpoints.save(
                    checkpoint, response.body, accept, next_url=next_url
                )
                return (
                    self._page(checkpoint, response.body, parse_json=parse_json),
                    next_url,
                )
            patch_variant_406 = (
                response.status == 406
                and not parse_json
                and is_exact_patch_variant(endpoint, params, page_number, accept)
            )
            if response.status in (404, 410, 451) or patch_variant_406:
                checkpoint = self._checkpoint(
                    endpoint,
                    params,
                    page_number,
                    accept,
                    response,
                    "confirmed-unavailable",
                )
                self._checkpoints.save(checkpoint, response.body, accept)
                return self._page(checkpoint, response.body, parse_json=False), None
            if self._is_rate_limited(response):
                if attempt + 1 < MAX_ATTEMPTS:
                    self._clock.sleep(self._rate_limit_delay())
                    continue
            elif response.status == 502 and attempt + 1 < MAX_ATTEMPTS:
                self._clock.sleep(float(2**server_error_attempt))
                server_error_attempt += 1
                continue
            raise GitHubClientError(
                f"github request failed: status={response.status} endpoint={endpoint}"
            )
        raise GitHubClientError(f"github retries exhausted: endpoint={endpoint}")

    def _is_rate_limited(self, response: HttpResponse) -> bool:
        return response.status == 403 and self.rate_limit_state.remaining == 0

    def _rate_limit_delay(self) -> float:
        reset_at = self.rate_limit_state.reset_at
        return max(float(reset_at) - self._clock.now(), 0.0) if reset_at else 1.0

    def _checkpoint(
        self,
        endpoint: str,
        params: dict[str, object],
        page_number: int,
        accept: str,
        response: HttpResponse,
        availability_status: str,
    ) -> Checkpoint:
        return Checkpoint(
            accept=accept,
            endpoint=endpoint,
            params=params,
            etag=_header(response.headers, "ETag"),
            fetched_at=datetime.fromtimestamp(self._clock.now(), UTC)
            .isoformat()
            .replace("+00:00", "Z"),
            response_hash=hashlib.sha256(response.body).hexdigest(),
            page_number=page_number,
            availability_status=availability_status,
            status_code=response.status,
        )

    def _page(
        self, checkpoint: Checkpoint, body: bytes, *, parse_json: bool = True
    ) -> GitHubPage:
        parsed: object = None
        if parse_json:
            try:
                parsed = json.loads(body)
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise GitHubClientError(
                    f"malformed GitHub JSON: endpoint={checkpoint.endpoint}"
                ) from error
        return GitHubPage(
            endpoint=checkpoint.endpoint,
            params=checkpoint.params,
            page_number=checkpoint.page_number,
            body=body,
            json=parsed,
            response_hash=checkpoint.response_hash,
            availability_status=checkpoint.availability_status,
            status_code=checkpoint.status_code,
            fetched_at=checkpoint.fetched_at,
        )

    def _url(
        self, path: str, params: Mapping[str, object] | None = None
    ) -> str:
        if path.startswith("https://"):
            base = path
        else:
            base = API_ROOT + "/" + path.lstrip("/")
        query = urllib.parse.urlencode(_canonical_params(params), doseq=True)
        return base + (f"?{query}" if query else "")


def _params_from_url(url: str) -> dict[str, object]:
    parsed = urllib.parse.urlparse(url)
    values = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    return {
        key: item[0] if len(item) == 1 else item
        for key, item in sorted(values.items())
    }


def _next_link(headers: Mapping[str, str]) -> str | None:
    link = _header(headers, "Link")
    if not link:
        return None
    for member in link.split(","):
        sections = [section.strip() for section in member.split(";")]
        if len(sections) < 2 or not sections[0].startswith("<"):
            continue
        relations = {
            value.strip('"')
            for section in sections[1:]
            if section.startswith("rel=")
            for value in section[4:].split()
        }
        if "next" in relations:
            return sections[0][1:-1]
    return None
