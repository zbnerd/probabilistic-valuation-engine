import hashlib

import pytest

from portfolio_builder.redaction import redact_binary_patch, redact_text


OWNER_EMAIL = "mps756@gmail.com"


@pytest.mark.parametrize(
    ("source", "kind", "secret"),
    [
        (
            b"token=ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
            "github-token",
            b"ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
        ),
        (
            b"AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
            "aws-access-key",
            b"AKIAIOSFODNN7EXAMPLE",
        ),
        (
            b"-----BEGIN PRIVATE KEY-----\nvery-secret-material\n-----END PRIVATE KEY-----",
            "pem-private-key",
            b"very-secret-material",
        ),
        (
            b"Authorization: Bearer access-token_1234567890",
            "bearer-token",
            b"access-token_1234567890",
        ),
        (
            b"Cookie: session=private-session-value; theme=dark",
            "cookie",
            b"session=private-session-value",
        ),
        (
            b"https://alice:private-password@example.test/path",
            "url-credentials",
            b"alice:private-password",
        ),
    ],
)
def test_redact_text_replaces_each_secret_kind_without_retaining_value(
    source, kind, secret
):
    result = redact_text(source, allowed_contacts=(OWNER_EMAIL,))

    assert result.value == b"[REDACTED:" + kind.encode() + b"]" or (
        b"[REDACTED:" + kind.encode() + b"]" in result.value
    )
    assert secret not in result.value
    assert result.kinds == (kind,)
    assert result.raw_hash == hashlib.sha256(source).hexdigest()
    assert result.stored_hash == hashlib.sha256(result.value).hexdigest()
    assert result.raw_hash != result.stored_hash


def test_redact_text_preserves_owner_email_and_redacts_third_party_contact():
    source = b"owner=mps756@gmail.com reviewer=third.party@example.org"

    result = redact_text(source, allowed_contacts=(OWNER_EMAIL,))

    assert b"mps756@gmail.com" in result.value
    assert b"third.party@example.org" not in result.value
    assert result.value.endswith(b"[REDACTED:third-party-email]")
    assert result.kinds == ("third-party-email",)


@pytest.mark.parametrize(
    ("source", "expected"),
    [
        (
            b"+Cookie: session=added-secret\n",
            b"+Cookie: [REDACTED:cookie]\n",
        ),
        (
            b"- Cookie: session=removed-secret\n",
            b"- Cookie: [REDACTED:cookie]\n",
        ),
        (
            b" Cookie: session=context-secret\n",
            b" Cookie: [REDACTED:cookie]\n",
        ),
        (
            b"    Set-Cookie:\tindented-secret\n",
            b"    Set-Cookie:\t[REDACTED:cookie]\n",
        ),
    ],
)
def test_cookie_redacts_git_patch_context_and_indented_header_forms(source, expected):
    result = redact_text(source)

    assert result.value == expected
    assert b"secret" not in result.value
    assert result.kinds == ("cookie",)


@pytest.mark.parametrize(
    "source",
    [
        b"Cookie: [REDACTED:cookie]; session=raw-cookie-secret\n",
        b"Set-Cookie: [REDACTED:cookie]raw-set-cookie-secret\n",
    ],
)
def test_cookie_marker_prefix_cannot_smuggle_a_secret_suffix(source):
    first = redact_text(source)
    second = redact_text(first.value)

    assert b"raw-" not in first.value
    assert first.value.endswith(b"[REDACTED:cookie]\n")
    assert first.value == second.value


@pytest.mark.parametrize(
    "source",
    [
        b'AWS_SECRET_ACCESS_KEY="abcdEFGHijklMNOPqrstUVWXyz0123456789+/="',
        b'{"AWS_SECRET_ACCESS_KEY": "abcdEFGHijklMNOPqrstUVWXyz0123456789+/="}',
    ],
)
def test_aws_secret_access_key_redacts_quoted_env_and_json_values(source):
    secret = b"abcdEFGHijklMNOPqrstUVWXyz0123456789+/="

    result = redact_text(source)

    assert secret not in result.value
    assert b"[REDACTED:aws-secret-access-key]" in result.value
    assert result.kinds == ("aws-secret-access-key",)


@pytest.mark.parametrize(
    "source",
    [
        b'AWS_SECRET_ACCESS_KEY="abcdEFGHijklMNOPqrstUVWXyz0123456789+/=',
        b'{"SecretAccessKey":"abcdEFGHijklMNOPqrstUVWXyz0123456789+/="}',
        b'{\n  "SecretAccessKey"\n  :\n  "abcdEFGHijklMNOPqrstUVWXyz0123456789+/="\n}',
        b'AWS_SECRET_ACCESS_KEY="[REDACTED:aws-secret-access-key]raw-aws-secret',
    ],
)
def test_aws_secret_access_key_handles_truncated_camel_multiline_and_smuggling(source):
    first = redact_text(source)
    second = redact_text(first.value)

    assert b"abcdEFGHijklMNOPqrstUVWXyz0123456789+/=" not in first.value
    assert b"raw-aws-secret" not in first.value
    assert b"[REDACTED:aws-secret-access-key]" in first.value
    assert first.value == second.value
    assert first.kinds == ("aws-secret-access-key",)


def test_multiline_aws_json_does_not_consume_the_next_record():
    source = (
        b'{\n  "SecretAccessKey"\n  :\n  "abcdEFGHijklMNOPqrstUVWXyz0123456789+/="\n}\n'
        b'{"next_record":"must-remain"}'
    )

    result = redact_text(source)

    assert b"abcdEFGHijklMNOPqrstUVWXyz0123456789+/=" not in result.value
    assert result.value.endswith(b'{"next_record":"must-remain"}')
    assert result.kinds == ("aws-secret-access-key",)


@pytest.mark.parametrize(
    "source",
    [
        b"password=[REDACTED:credential-value]raw-password-secret",
        b'password="[REDACTED:credential-value]raw-quoted-password-secret"',
    ],
)
def test_generic_credential_marker_prefix_cannot_smuggle_a_secret_suffix(source):
    first = redact_text(source)
    second = redact_text(first.value)

    assert b"raw-" not in first.value
    assert b"[REDACTED:credential-value]" in first.value
    assert first.value == second.value


@pytest.mark.parametrize(
    ("source", "kind", "suffix"),
    [
        (
            b'password="[REDACTED:credential-value] raw-password-secret"',
            "credential-value",
            b"raw-password-secret",
        ),
        (
            b"password='[REDACTED:credential-value],raw-password-secret'",
            "credential-value",
            b"raw-password-secret",
        ),
        (
            b'password="[REDACTED:credential-value];raw-password-secret"',
            "credential-value",
            b"raw-password-secret",
        ),
        (
            b'password="[REDACTED:credential-value] raw-unbalanced-password-secret',
            "credential-value",
            b"raw-unbalanced-password-secret",
        ),
        (
            b'AWS_SECRET_ACCESS_KEY="[REDACTED:aws-secret-access-key] raw-aws-secret"',
            "aws-secret-access-key",
            b"raw-aws-secret",
        ),
        (
            b"SecretAccessKey='[REDACTED:aws-secret-access-key],raw-aws-secret'",
            "aws-secret-access-key",
            b"raw-aws-secret",
        ),
        (
            b'AWS_SECRET_ACCESS_KEY="[REDACTED:aws-secret-access-key];raw-unbalanced-aws-secret',
            "aws-secret-access-key",
            b"raw-unbalanced-aws-secret",
        ),
    ],
)
def test_quoted_marker_suffixes_are_parsed_as_one_sensitive_value(source, kind, suffix):
    first = redact_text(source)
    second = redact_text(first.value)

    assert suffix not in first.value
    assert first.kinds == (kind,)
    assert first.raw_hash != first.stored_hash
    assert first.value == second.value


@pytest.mark.parametrize(
    ("source", "expected"),
    [
        (
            b'password="[REDACTED:credential-value]"',
            b'password="[REDACTED:credential-value]"',
        ),
        (
            b"password='[REDACTED:credential-value]'",
            b"password='[REDACTED:credential-value]'",
        ),
        (
            b'password="[REDACTED:credential-value]',
            b"password=[REDACTED:credential-value]",
        ),
        (
            b'AWS_SECRET_ACCESS_KEY="[REDACTED:aws-secret-access-key]"',
            b'AWS_SECRET_ACCESS_KEY="[REDACTED:aws-secret-access-key]"',
        ),
        (
            b'AWS_SECRET_ACCESS_KEY="[REDACTED:aws-secret-access-key]',
            b"AWS_SECRET_ACCESS_KEY=[REDACTED:aws-secret-access-key]",
        ),
    ],
)
def test_exact_quoted_marker_values_are_stable_or_safely_normalized(source, expected):
    first = redact_text(source)
    second = redact_text(first.value)

    assert first.value == expected
    assert first.value == second.value


@pytest.mark.parametrize(
    "source",
    [
        b"-----BEGIN RSA PRIVATE KEY-----\nrsa-secret\n",
        b"-----BEGIN EC PRIVATE KEY-----\nec-secret\n-----END EC PRIVATE KEY-----",
        b"-----BEGIN OPENSSH PRIVATE KEY-----\nssh-secret\n-----END OPENSSH PRIVATE KEY-----",
    ],
)
def test_pem_private_key_variants_and_truncated_blocks_are_redacted(source):
    result = redact_text(source)

    assert b"secret" not in result.value
    assert result.value == b"[REDACTED:pem-private-key]"
    assert result.kinds == ("pem-private-key",)


def test_truncated_pem_stops_before_a_following_pem_document():
    source = (
        b"-----BEGIN RSA PRIVATE KEY-----\nrsa-secret\n"
        b"-----BEGIN EC PRIVATE KEY-----\nec-secret\n-----END EC PRIVATE KEY-----"
    )

    result = redact_text(source)

    assert b"rsa-secret" not in result.value
    assert b"ec-secret" not in result.value
    assert result.value.count(b"[REDACTED:pem-private-key]") == 2
    assert result.kinds == ("pem-private-key",)


def test_mixed_case_http_credentials_are_redacted():
    source = b"HTTPS://Alice:private-password@example.test/path"

    result = redact_text(source)

    assert b"Alice:private-password" not in result.value
    assert result.value == b"[REDACTED:url-credentials]"
    assert result.kinds == ("url-credentials",)


def test_default_owner_allowlist_and_multiple_contact_matches_are_deterministic():
    source = b"mps756@gmail.com a@example.org b@example.org"

    result = redact_text(source)

    assert result.value.startswith(b"mps756@gmail.com ")
    assert result.value.count(b"[REDACTED:third-party-email]") == 2
    assert result.kinds == ("third-party-email",)


def test_rules_are_ordered_and_kinds_are_sorted_unique_for_overlapping_content():
    source = (
        b"Bearer ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD "
        b"email=other@example.org token=ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD"
    )

    result = redact_text(source, allowed_contacts=(OWNER_EMAIL,))

    assert result.value.count(b"[REDACTED:github-token]") == 1
    assert b"[REDACTED:bearer-token]" in result.value
    assert b"[REDACTED:third-party-email]" in result.value
    assert result.kinds == tuple(sorted(set(result.kinds)))
    assert result.kinds == ("bearer-token", "github-token", "third-party-email")


def test_redaction_is_byte_idempotent_and_handles_non_utf8_bytes():
    source = b"\xff before ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD after \xfe"

    first = redact_text(source, allowed_contacts=(OWNER_EMAIL,))
    second = redact_text(first.value, allowed_contacts=(OWNER_EMAIL,))

    assert first.value == second.value
    assert first.stored_hash == second.stored_hash
    assert first.value.startswith(b"\xff before ")
    assert first.value.endswith(b" after \xfe")


def test_binary_patch_only_retains_validated_fixed_metadata_and_marker():
    source = (
        b"diff --git a/private.bin b/private.bin\n"
        b"GIT binary patch\nliteral 99\nsecret-binary-payload\n"
    )
    metadata = {
        "old_blob": "0123456789abcdef0123456789abcdef01234567",
        "new_blob": "fedcba9876543210fedcba9876543210fedcba98",
        "old_size": 12,
        "new_size": 99,
        "attacker\nheader": "ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
        "note": "third.party@example.org",
    }

    result = redact_binary_patch(source, metadata)

    assert result.value == (
        b"old_blob: 0123456789abcdef0123456789abcdef01234567\n"
        b"new_blob: fedcba9876543210fedcba9876543210fedcba98\n"
        b"old_size: 12\n"
        b"new_size: 99\n"
        b"[REDACTED BINARY PAYLOAD]\n"
    )
    assert b"secret-binary-payload" not in result.value
    assert b"attacker" not in result.value
    assert b"third.party" not in result.value
    assert result.kinds == ("binary-patch",)
    assert result.raw_hash == hashlib.sha256(source).hexdigest()
    assert result.stored_hash == hashlib.sha256(result.value).hexdigest()
    assert result.raw_hash != result.stored_hash


def test_binary_patch_is_byte_idempotent_and_metadata_validation_is_deny_by_default():
    metadata = {
        "old_blob": "0123456789abcdef0123456789abcdef01234567",
        "old_size": True,
        "new_size": -1,
        "new_blob": "not a blob id",
    }

    first = redact_binary_patch(b"untrusted binary data", metadata)
    second = redact_binary_patch(first.value, metadata)

    assert first.value == (
        b"old_blob: 0123456789abcdef0123456789abcdef01234567\n"
        b"[REDACTED BINARY PAYLOAD]\n"
    )
    assert first.value == second.value
    assert first.stored_hash == second.stored_hash


def test_binary_patch_rejects_non_ascii_blob_metadata_without_normalizing_it():
    metadata = {
        "old_blob": "0123456789abcdef0123456789abcdef01234567é",
        "new_blob": "fedcba9876543210fedcba9876543210fedcba98",
    }

    result = redact_binary_patch(b"untrusted binary data", metadata)

    assert b"old_blob" not in result.value
    assert b"new_blob: fedcba9876543210fedcba9876543210fedcba98" in result.value
    assert result.value.endswith(b"[REDACTED BINARY PAYLOAD]\n")


def _assert_adversarial_text_redaction(source, kind, secrets):
    first = redact_text(source)
    second = redact_text(first.value)

    for secret in secrets:
        assert secret not in first.value
    assert kind in first.kinds
    assert first.raw_hash == hashlib.sha256(source).hexdigest()
    assert first.stored_hash == hashlib.sha256(first.value).hexdigest()
    assert first.raw_hash != first.stored_hash
    assert first.value == second.value
    assert first.stored_hash == second.stored_hash


@pytest.mark.parametrize(
    ("source", "kind", "secret"),
    [
        (
            b'AWS_SECRET_ACCESS_KEY="[REDACTED:aws-secret-access-key]"raw-aws-secret',
            "aws-secret-access-key",
            b"raw-aws-secret",
        ),
        (
            b'password="[REDACTED:credential-value]\\"raw-secret"',
            "credential-value",
            b"raw-secret",
        ),
        (
            b"password='[REDACTED:credential-value]''raw-secret'",
            "credential-value",
            b"raw-secret",
        ),
        (
            b'password="[REDACTED:credential-value]""raw-double-secret"',
            "credential-value",
            b"raw-double-secret",
        ),
        (
            b"password='[REDACTED:credential-value]\\'raw-single-secret'",
            "credential-value",
            b"raw-single-secret",
        ),
        (
            b'password="[REDACTED:credential-value]"\'raw-adjacent-secret\'',
            "credential-value",
            b"raw-adjacent-secret",
        ),
        (
            b"password='[REDACTED:credential-value]'raw-post-close-secret",
            "credential-value",
            b"raw-post-close-secret",
        ),
        (
            b'password="[REDACTED:credential-value]\\"raw-unbalanced-secret',
            "credential-value",
            b"raw-unbalanced-secret",
        ),
        (
            b"password='[REDACTED:credential-value]''raw-unbalanced-secret",
            "credential-value",
            b"raw-unbalanced-secret",
        ),
    ],
)
def test_quoted_scalar_parser_consumes_escapes_doubled_quotes_and_suffixes(
    source, kind, secret
):
    _assert_adversarial_text_redaction(source, kind, (secret,))


def test_quoted_scalar_parser_stops_at_the_next_independent_record():
    source = (
        b'password="[REDACTED:credential-value]\\"raw-first-secret\n'
        b'next_record="must-remain-byte-for-byte"\n'
    )

    first = redact_text(source)
    second = redact_text(first.value)

    assert b"raw-first-secret" not in first.value
    assert first.value.endswith(b'next_record="must-remain-byte-for-byte"\n')
    assert first.kinds == ("credential-value",)
    assert first.value == second.value


@pytest.mark.parametrize("separator", [b"\r", b"\r\n"])
def test_quoted_scalar_parser_honours_cr_based_record_boundaries(separator):
    source = (
        b'password="[REDACTED:credential-value]\\"raw-first-secret'
        + separator
        + b'next_record="must-remain-byte-for-byte"'
    )

    result = redact_text(source)

    assert b"raw-first-secret" not in result.value
    assert result.value.endswith(
        separator + b'next_record="must-remain-byte-for-byte"'
    )


def test_aws_candidate_does_not_take_an_unquoted_value_from_the_next_record():
    source = b"AWS_SECRET_ACCESS_KEY=\nNEXT_RECORD=must-remain-byte-for-byte\n"

    result = redact_text(source)

    assert result.value == source
    assert result.kinds == ()


def test_camel_case_aws_key_uses_the_same_bounded_scalar_parser():
    source = b'AwsSecretAccessKey="raw-camel-aws-secret"'

    _assert_adversarial_text_redaction(
        source, "aws-secret-access-key", (b"raw-camel-aws-secret",)
    )


@pytest.mark.parametrize(
    "source",
    [
        b"password=[REDACTED:credential-value],raw-comma-secret",
        b'password="[REDACTED:credential-value]";raw-semicolon-secret',
        b'password="[REDACTED:credential-value]"}raw-brace-secret',
        b'password="[REDACTED:credential-value]",}raw-comma-brace-secret',
    ],
)
def test_unproven_delimiters_and_their_suffixes_remain_inside_sensitive_span(source):
    _assert_adversarial_text_redaction(source, "credential-value", (b"raw-",))


@pytest.mark.parametrize(
    ("source", "secret", "preserved"),
    [
        (
            b'{"password":"raw-password-secret","safe":"kept"}',
            b"raw-password-secret",
            b',"safe":"kept"}',
        ),
        (
            b"'token' = 'raw-token-secret'\nnext = 'kept'",
            b"raw-token-secret",
            b"\nnext = 'kept'",
        ),
        (
            b'"api_key" = "raw-api-key-secret"; safe = "kept"',
            b"raw-api-key-secret",
            b'; safe = "kept"',
        ),
    ],
)
def test_balanced_quoted_generic_keys_preserve_only_safe_structure(
    source, secret, preserved
):
    _assert_adversarial_text_redaction(source, "credential-value", (secret,))
    assert preserved in redact_text(source).value


@pytest.mark.parametrize(
    ("source", "secret"),
    [
        (b"postgresql://alice:db-secret@db.example.test/app", b"db-secret"),
        (b"FTP://alice:ftp-secret@example.test/file", b"ftp-secret"),
        (b"custom+ssh.v1://:empty-user-secret@host.test/path", b"empty-user-secret"),
    ],
)
def test_scheme_neutral_url_userinfo_redacts_complete_credential_uri(source, secret):
    _assert_adversarial_text_redaction(source, "url-credentials", (secret,))
    assert redact_text(source).value == b"[REDACTED:url-credentials]"


@pytest.mark.parametrize(
    "source",
    [
        b"ordinary:value@host",
        b"path/to:user@host",
        b"custom://username-only@host/path",
        b"1invalid://user:password@host/path",
    ],
)
def test_url_userinfo_rule_avoids_non_credential_colon_at_forms(source):
    result = redact_text(source)

    assert result.value == source
    assert "url-credentials" not in result.kinds


@pytest.mark.parametrize(
    ("source", "secret"),
    [
        (b'contact="quoted.local"@example.org', b'"quoted.local"@example.org'),
        (
            b'contact="escaped\\"name"@example.org',
            b'"escaped\\"name"@example.org',
        ),
        (b'contact="mps756"@gmail.com', b'"mps756"@gmail.com'),
    ],
)
def test_quoted_ascii_email_local_parts_are_bounded_and_not_owner_aliases(
    source, secret
):
    _assert_adversarial_text_redaction(source, "third-party-email", (secret,))


def test_quoted_email_does_not_cross_a_record_boundary():
    source = b'contact="unterminated\nnext=mps756@gmail.com\n'

    result = redact_text(source)

    assert result.value == source
    assert result.kinds == ()
