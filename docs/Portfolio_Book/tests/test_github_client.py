import hashlib
from http.client import RemoteDisconnected
import json
from dataclasses import dataclass
from pathlib import Path

import pytest

from portfolio_builder.github_client import (
    CheckpointStore,
    GitHubClient,
    GitHubClientError,
    HttpResponse,
)
from portfolio_builder.github_collector import PATCH_ACCEPT


FIXTURES = Path(__file__).parent / "fixtures" / "github"


@dataclass
class FakeClock:
    timestamp: float = 1_700_000_000.0

    def now(self):
        return self.timestamp

    def sleep(self, seconds):
        self.timestamp += seconds


class FixtureTransport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def request(self, url, headers):
        self.requests.append((url, dict(headers)))
        result = self.responses.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


def response(status, body=b"", **headers):
    return HttpResponse(status=status, headers=headers, body=body)


def fixture(name):
    return (FIXTURES / name).read_bytes()


def make_client(tmp_path, responses, *, token="top-secret-token", clock=None):
    transport = FixtureTransport(responses)
    client = GitHubClient(
        transport=transport,
        clock=clock or FakeClock(),
        checkpoint_store=CheckpointStore(tmp_path / "checkpoints"),
        token=token,
    )
    return client, transport


def test_link_pagination_uses_exact_api_headers_and_persists_checkpoints(tmp_path):
    link = '<https://api.github.com/repos/o/r/issues?page=2&per_page=2>; rel="next"'
    client, transport = make_client(
        tmp_path,
        [
            response(200, fixture("page-1.json"), Link=link, ETag='"page-one"'),
            response(200, fixture("page-2.json"), ETag='"page-two"'),
        ],
    )

    pages = client.get_pages("/repos/o/r/issues", {"state": "all", "per_page": 2})

    assert [page.json for page in pages] == [[{"id": 1}, {"id": 2}], [{"id": 3}]]
    assert [page.page_number for page in pages] == [1, 2]
    assert transport.requests[0][1] == {
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer top-secret-token",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    checkpoints = sorted((tmp_path / "checkpoints").glob("*.json"))
    assert len(checkpoints) == 2
    saved = json.loads(checkpoints[0].read_text(encoding="utf-8"))
    assert set(saved) == {
        "accept",
        "availability_status",
        "endpoint",
        "etag",
        "fetched_at",
        "page_number",
        "params",
        "response_hash",
        "status_code",
    }
    assert saved["availability_status"] == "available"
    assert b"top-secret-token" not in b"".join(
        path.read_bytes() for path in (tmp_path / "checkpoints").glob("*")
    )


def test_etag_304_resume_reads_verified_cached_body(tmp_path):
    first, _ = make_client(
        tmp_path,
        [response(200, fixture("page-1.json"), ETag='"stable"')],
    )
    original = first.get_json("/repos/o/r/issues", {"state": "all"})
    resumed, transport = make_client(tmp_path, [response(304)])

    again = resumed.get_json("/repos/o/r/issues", {"state": "all"})

    assert again == original
    assert transport.requests[0][1]["If-None-Match"] == '"stable"'


def test_paginated_304_resume_retains_the_cached_next_link(tmp_path):
    next_url = "https://api.github.com/repos/o/r/issues?page=2"
    first, _ = make_client(
        tmp_path,
        [
            response(
                200,
                fixture("page-1.json"),
                Link=f'<{next_url}>; rel="next"',
                ETag='"one"',
            ),
            response(200, fixture("page-2.json"), ETag='"two"'),
        ],
    )
    first.get_pages("/repos/o/r/issues")
    resumed, transport = make_client(tmp_path, [response(304), response(304)])

    pages = resumed.get_pages("/repos/o/r/issues")

    assert [page.json for page in pages] == [
        [{"id": 1}, {"id": 2}],
        [{"id": 3}],
    ]
    assert transport.requests[1][0] == next_url
    assert transport.requests[1][1]["If-None-Match"] == '"two"'


def test_get_bytes_does_not_require_a_json_payload(tmp_path):
    payload = b"\x00\xffnot-json\n"
    client, _ = make_client(tmp_path, [response(200, payload)])

    assert (
        client.get_bytes("/repos/o/r/pulls/1.patch", accept="application/patch")
        == payload
    )


def test_rate_limit_reset_and_bounded_502_backoff_use_fake_clock(tmp_path):
    clock = FakeClock()
    client, _ = make_client(
        tmp_path,
        [
            response(
                403,
                b'{"message":"rate limited"}',
                **{
                    "X-RateLimit-Remaining": "0",
                    "X-RateLimit-Reset": str(int(clock.now() + 7)),
                },
            ),
            response(502, b"upstream unavailable"),
            response(200, b'{"ok":true}', **{"X-RateLimit-Remaining": "42"}),
        ],
        clock=clock,
    )

    assert client.get_json("/repos/o/r") == {"ok": True}
    assert clock.now() == 1_700_000_008.0
    assert client.rate_limit_state.remaining == 42
    assert client.rate_limit_state.reset_at is None


def test_502_retry_is_bounded_and_error_does_not_expose_token_or_body(tmp_path):
    secret = "github_pat_NEVER_EXPOSE_THIS_VALUE"
    clock = FakeClock()
    client, _ = make_client(
        tmp_path,
        [response(502, secret.encode()) for _ in range(3)],
        token=secret,
        clock=clock,
    )

    with pytest.raises(GitHubClientError) as caught:
        client.get_bytes("/repos/o/r/archive")

    rendered = repr(caught.value) + str(caught.value)
    assert secret not in rendered
    assert "status=502" in rendered
    assert clock.now() == 1_700_000_003.0
    assert secret.encode() not in b"".join(
        path.read_bytes() for path in (tmp_path / "checkpoints").glob("*")
    )


def test_transient_disconnect_retries_then_returns_the_following_response(tmp_path):
    clock = FakeClock()
    client, transport = make_client(
        tmp_path,
        [RemoteDisconnected("connection closed"), response(200, b'{"ok":true}')],
        clock=clock,
    )

    assert client.get_json("/repos/o/r") == {"ok": True}
    assert len(transport.requests) == 2
    assert clock.now() == 1_700_000_001.0


def test_transient_disconnect_exhaustion_is_safe_and_writes_no_checkpoint(tmp_path):
    secret = "transport-secret-must-not-leak"
    clock = FakeClock()
    client, transport = make_client(
        tmp_path,
        [RemoteDisconnected(secret) for _ in range(3)],
        clock=clock,
    )

    with pytest.raises(GitHubClientError) as caught:
        client.get_bytes("/repos/o/r/archive")

    rendered = repr(caught.value) + str(caught.value)
    assert len(transport.requests) == 3
    assert clock.now() == 1_700_000_003.0
    assert secret not in rendered
    assert "endpoint=/repos/o/r/archive" in rendered
    assert not tuple((tmp_path / "checkpoints").glob("*"))


def test_404_records_confirmed_unavailable_without_guessing(tmp_path):
    client, _ = make_client(tmp_path, [response(404, b'{"message":"Not Found"}')])

    assert client.get_json("/repos/o/r/missing") is None
    saved = next((tmp_path / "checkpoints").glob("*.json"))
    checkpoint = json.loads(saved.read_text(encoding="utf-8"))
    assert checkpoint["availability_status"] == "confirmed-unavailable"
    assert checkpoint["response_hash"]


def test_terminal_unavailable_page_retains_status_hash_and_observed_time(tmp_path):
    body = b'{"message":"private diagnostic"}'
    client, _ = make_client(tmp_path, [response(451, body)])

    pages = client.get_pages("/repos/o/r/restricted", {"per_page": 100})

    assert len(pages) == 1
    assert pages[0].availability_status == "confirmed-unavailable"
    assert pages[0].status_code == 451
    assert pages[0].response_hash == hashlib.sha256(body).hexdigest()
    assert pages[0].fetched_at == "2023-11-14T22:13:20Z"
    assert pages[0].body == body
    checkpoint = json.loads(next((tmp_path / "checkpoints").glob("*.json")).read_text())
    assert checkpoint["status_code"] == 451


def test_terminal_unavailable_bytes_observation_is_not_collapsed(tmp_path):
    body = b"do-not-publish"
    client, _ = make_client(tmp_path, [response(410, body)])

    page = client.get_bytes_page("/repos/o/r/pulls/1.patch", accept="application/patch")

    assert page.status_code == 410
    assert page.response_hash == hashlib.sha256(body).hexdigest()
    assert page.fetched_at == "2023-11-14T22:13:20Z"


def test_live_pr_patch_406_is_terminal_only_for_exact_patch_variant(tmp_path):
    body = b'{"message":"unsafe patch diagnostic"}'
    endpoint = "/repos/zbnerd/probabilistic-valuation-engine/pulls/241.patch"
    client, _ = make_client(tmp_path, [response(406, body)])

    page = client.get_bytes_page(endpoint, accept=PATCH_ACCEPT)

    assert page.availability_status == "confirmed-unavailable"
    assert page.status_code == 406
    assert page.response_hash == hashlib.sha256(body).hexdigest()
    assert page.fetched_at == "2023-11-14T22:13:20Z"
    checkpoint = json.loads(
        next((tmp_path / "checkpoints").glob("*.json")).read_text(encoding="utf-8")
    )
    assert checkpoint["endpoint"] == endpoint
    assert checkpoint["params"] == {}
    assert checkpoint["accept"] == PATCH_ACCEPT
    assert checkpoint["status_code"] == 406
    assert checkpoint["response_hash"] == hashlib.sha256(body).hexdigest()
    assert checkpoint["fetched_at"] == "2023-11-14T22:13:20Z"


@pytest.mark.parametrize(
    ("request_kind", "accept"),
    [
        ("json", "application/vnd.github+json"),
        ("bytes", "application/vnd.github+json"),
        ("pages", PATCH_ACCEPT),
    ],
)
def test_406_remains_an_error_for_json_wrong_accept_or_paginated_requests(
    tmp_path, request_kind, accept
):
    endpoint = "/repos/zbnerd/probabilistic-valuation-engine/pulls/241.patch"
    client, _ = make_client(tmp_path, [response(406, b"do-not-publish")])

    with pytest.raises(GitHubClientError, match="status=406"):
        if request_kind == "json":
            client.get_json("/repos/zbnerd/probabilistic-valuation-engine/pulls/241")
        elif request_kind == "bytes":
            client.get_bytes_page(endpoint, accept=accept)
        else:
            client.get_pages(endpoint, accept=accept)

    assert not tuple((tmp_path / "checkpoints").glob("*"))


def test_406_after_available_paginated_content_still_blocks(tmp_path):
    endpoint = "/repos/zbnerd/probabilistic-valuation-engine/pulls/241.patch"
    next_url = "https://api.github.com" + endpoint + "?page=2"
    client, _ = make_client(
        tmp_path,
        [
            response(200, b"[]", Link=f'<{next_url}>; rel="next"'),
            response(406, b"do-not-publish"),
        ],
    )

    with pytest.raises(GitHubClientError, match="status=406"):
        client.get_pages(endpoint, accept=PATCH_ACCEPT)

    checkpoints = tuple((tmp_path / "checkpoints").glob("*.json"))
    assert len(checkpoints) == 1
    assert json.loads(checkpoints[0].read_text(encoding="utf-8"))["status_code"] == 200


def test_environment_token_precedes_gh_fallback_and_neither_is_exposed(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("GITHUB_TOKEN", "environment-secret")

    def forbidden(*args, **kwargs):
        raise AssertionError("gh fallback must not run")

    monkeypatch.setattr("portfolio_builder.github_client.subprocess.run", forbidden)
    transport = FixtureTransport([response(200, b"{}")])
    client = GitHubClient(
        transport=transport,
        clock=FakeClock(),
        checkpoint_store=CheckpointStore(tmp_path),
    )

    assert client.get_json("/repos/o/r") == {}
    assert transport.requests[0][1]["Authorization"] == "Bearer environment-secret"


def test_gh_auth_token_is_the_fallback(tmp_path, monkeypatch):
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)

    class Completed:
        returncode = 0
        stdout = "gh-secret\n"
        stderr = ""

    monkeypatch.setattr(
        "portfolio_builder.github_client.subprocess.run", lambda *args, **kwargs: Completed()
    )
    transport = FixtureTransport([response(200, b"{}")])
    client = GitHubClient(
        transport=transport,
        clock=FakeClock(),
        checkpoint_store=CheckpointStore(tmp_path),
    )

    assert client.get_json("/repos/o/r") == {}
    assert transport.requests[0][1]["Authorization"] == "Bearer gh-secret"
