# Exhaustive Portfolio Case and Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read and classify every captured source/document claim, turn every evidenced achievement or problem-solving record into a case, and generate an exhaustive resume, portfolio, and human-readable evidence book without inferred facts or count limits.

**Architecture:** Deterministic review manifests divide the frozen evidence ledger into individually addressable targets and manifest-fixed structural groups small enough to review as atomic units. Primary reviewers collectively read every target through its exact group/part union and fresh verifiers check every decision against the same cited bytes; accepted annotations feed a relation-constrained case catalog. Both Markdown documents are generated from the same immutable `CaseRecord` objects, while the evidence book iterates the complete source and document-claim sets.

**Tech Stack:** Python 3.12, uv 0.11+, pytest 9.1.1, JSONL/CSV, markdown-it-py 4.2.0, the evidence package created by the capture plan, and read-only agent review batches.

**Spec:** `docs/superpowers/specs/2026-08-01-exhaustive-portfolio-rebuild-design.md`

**Depends on:** `2026-08-01-exhaustive-portfolio-evidence-capture.md` completed with `verify-source-capture` passing.

## Global Constraints

Every fenced shell block starts from `/home/maple/probabilistic-valuation-engine`; working-directory changes never carry into a later block.

- Review every `SourceRecord` and every `DocumentClaim` exactly once; a batch size is a checkpoint size, never a selection limit.
- A target decision is valid only after the exact union of its manifest-fixed structural groups proves the complete stored representation was read; no individual agent is given an unbounded whole target.
- Do not use embeddings, topic similarity, timestamps, filenames, or nearby commits to invent relations.
- Merge sources into one case only through `explicit_relations` recorded by the evidence plan.
- Preserve distinct dates, environments, concurrency levels, error counts, and status labels as distinct facts or cases. Store the source's status wording byte-for-byte in `raw_status_label`; normalized `EvidenceStatus` is an additional search/gate category and never replaces it.
- Repository measurement documents are factual sources. Do not reject their recorded results because raw stdout is absent and do not calculate replacement values.
- Never turn `target`, `expected`, `failed`, `reverted`, `superseded`, or `verification-pending` into `measured`.
- When a complete unsplit logical source records only a result, use the exact text `원문에 문제·해결 과정 미기록` for both missing fields and a source-to-result Mermaid specification. When a field is absent only from an artificial bounded split whose same-logical-unit continuation groups still exist, use `이 bounded 관찰 범위에 <필드> 미기록` plus every continuation group ID; never falsely say the whole source omitted it or infer that a continuation contains it.
- Every factual sentence, including profile/project metadata and case titles, carries source IDs. Layout labels alone are exempt.
- Include every accepted case in both Markdown documents. Do not use top-N, slicing, page caps, representative-case filters, or token-budget truncation.
- Include every source and document claim in `전수증거장부.md`, including record-only, unavailable, malformed, failed, and rollback records.
- Reviewers never copy secrets or third-party contacts into annotations; they cite redacted stored locators and hashes.
- Preserve target `evidence_scope`/`claim_authority` byte-for-byte in the review manifest and effective annotation. `claim_stage` and corroborating relations are first assigned by the cited primary observation, hash-checked through verification/adjudication, and copied into `ClassifiedObservation`; every downstream fact must resolve its observation/source IDs back to those effective fields and rerun the authority/stage gate. `structural-reference` is always structure-ledger record-only and can never source a case/profile/project fact.
- An `ai-assertion` or `legacy-derived-record` can support a case/fact only when `corroborating_relation_ids` resolve to non-derived primary/personal/trace-observation evidence that supports the same exact assertion; otherwise it is unverified record-only and never sole authority. A `trace-observation` may stand on its exact captured result/exit/error/patch/log bytes, but a command input proves only `attempted` and truncated/missing output never proves success.
- `proposed`, `planned`, `target`, or `expected` alone is record-only. It can appear as a goal/condition only beside independently evidenced observed/attempted/diagnosed/implemented/measured/merged/failed/reverted/rolled-back work.
- Route every accepted observation through a controlled fact-candidate partition: zero or more `profile:<field>` / `project:<stable-project-key>:<field>` keys, or one explicit source-based `no_fact_candidate_reason`. Free-form later rescanning may not decide profile/project eligibility.
- Do not modify databases, servers, queues, GitHub state, original PDFs, `.gitignore`, or the frozen evidence inputs.

## Shared Data Contracts

Extend `models.py` with these frozen dataclasses and enums. JSONL field order remains canonical through `canonical_io.py`.

```python
class EvidenceStatus(StrEnum):
    MEASURED = "measured"
    IMPLEMENTED = "implemented"
    FAILED = "failed"
    REVERTED = "reverted"
    ROLLED_BACK = "rolled-back"
    SUPERSEDED = "superseded"
    ESTIMATED = "estimated"
    UNVERIFIED = "unverified"
    DIAGNOSED_NO_FIX = "diagnosed-no-fix"
    IMPLEMENTED_VERIFICATION_PENDING = "implemented-verification-pending"
    TARGET = "target"
    EXPECTED = "expected"
    PROPOSED = "proposed"
    PLANNED = "planned"
    NOT_RECORDED = "not-recorded"

@dataclass(frozen=True, slots=True)
class EvidenceExcerpt:
    target_id: str
    locator: str
    text: str
    stored_hash: str

@dataclass(frozen=True, slots=True)
class ReviewObservation:
    observation_id: str
    structural_group_id: str
    logical_continuation_id: str
    continuation_group_ids: tuple[str, ...]
    disposition: Literal["case", "record-only"]
    evidence_scope: Literal[
        "project-evidence", "personal-evidence", "structural-reference"
    ]
    claim_authority: Literal[
        "primary-record", "personal-record", "structural-reference",
        "ai-assertion", "trace-observation", "legacy-derived-record"
    ]
    claim_stage: Literal[
        "observed", "attempted", "diagnosed", "implemented", "measured",
        "merged", "failed", "reverted", "rolled-back",
        "proposed", "planned", "target", "expected", "unverified-assertion",
        "metadata"
    ]
    classifications: tuple[str, ...]
    status: str
    raw_status_label: str
    domain_excerpts: tuple[EvidenceExcerpt, ...]
    problem_excerpts: tuple[EvidenceExcerpt, ...]
    solution_excerpts: tuple[EvidenceExcerpt, ...]
    result_excerpts: tuple[EvidenceExcerpt, ...]
    condition_excerpts: tuple[EvidenceExcerpt, ...]
    review_part_ids: tuple[str, ...]
    relation_target_ids: tuple[str, ...]
    corroborating_relation_ids: tuple[str, ...]
    fact_candidate_keys: tuple[str, ...]
    no_fact_candidate_reason: str | None
    record_only_reason: str | None

@dataclass(frozen=True, slots=True)
class ReviewObservationFragment:
    fragment_id: str
    observation_group_id: str
    structural_group_id: str
    logical_unit_id: str
    part_id: str
    ordinal: int
    total: int
    field_name: Literal["domain", "problem", "solution", "result", "condition", "record-only"]
    text: str
    locator: str
    stored_hash: str
    disposition: Literal["case", "record-only"]
    evidence_scope: Literal[
        "project-evidence", "personal-evidence", "structural-reference"
    ]
    claim_authority: Literal[
        "primary-record", "personal-record", "structural-reference",
        "ai-assertion", "trace-observation", "legacy-derived-record"
    ]
    claim_stage: Literal[
        "observed", "attempted", "diagnosed", "implemented", "measured",
        "merged", "failed", "reverted", "rolled-back",
        "proposed", "planned", "target", "expected", "unverified-assertion",
        "metadata"
    ]
    classifications: tuple[str, ...]
    status: str
    raw_status_label: str
    relation_target_ids: tuple[str, ...]
    corroborating_relation_ids: tuple[str, ...]
    fact_candidate_keys: tuple[str, ...]
    no_fact_candidate_reason: str | None
    record_only_reason: str | None

@dataclass(frozen=True, slots=True)
class ReviewedMember:
    member_id: str
    locator: str
    sha256: str

@dataclass(frozen=True, slots=True)
class ReviewAnnotation:
    target_id: str
    target_kind: Literal["source-record", "document-claim"]
    evidence_scope: Literal[
        "project-evidence", "personal-evidence", "structural-reference"
    ]
    claim_authority: Literal[
        "primary-record", "personal-record", "structural-reference",
        "ai-assertion", "trace-observation", "legacy-derived-record"
    ]
    review_batch_ids: tuple[str, ...]
    reviewer_run_id: str
    reviewed_stored_hash: str
    covered_members: tuple[ReviewedMember, ...]
    covered_part_ids: tuple[str, ...]
    part_annotation_sha256s: tuple[str, ...]
    complete_representation_read: bool
    observations: tuple[ReviewObservation, ...]

@dataclass(frozen=True, slots=True)
class ReviewVerification:
    verification_id: str
    verifier_run_id: str
    primary_reviewer_run_id: str
    target_id: str
    review_batch_ids: tuple[str, ...]
    reviewed_annotation_sha256: str
    reviewed_verification_sha256: str | None
    covered_members: tuple[ReviewedMember, ...]
    covered_part_ids: tuple[str, ...]
    part_verification_sha256s: tuple[str, ...]
    complete_representation_read: bool
    decision: Literal["accepted", "disputed", "adjudicated"]
    checked_observation_ids: tuple[str, ...]
    correction_annotation: ReviewAnnotation | None
    reason: str | None

@dataclass(frozen=True, slots=True)
class ReviewTarget:
    target_id: str
    target_kind: Literal["source-record", "document-claim"]
    evidence_scope: Literal[
        "project-evidence", "personal-evidence", "structural-reference"
    ]
    claim_authority: Literal[
        "primary-record", "personal-record", "structural-reference",
        "ai-assertion", "trace-observation", "legacy-derived-record"
    ]
    raw_hash: str
    stored_hash: str
    stored_members: tuple[StoredArtifactMember, ...]

@dataclass(frozen=True, slots=True)
class ReviewTargetPart:
    part_id: str
    parent_target_id: str
    member_id: str
    logical_unit_id: str
    structural_group_id: str
    structural_group_ordinal: int
    structural_group_total: int
    continuation_group_ids: tuple[str, ...]
    logical_part_ordinal: int
    logical_part_total: int
    byte_start: int
    byte_end: int
    byte_count: int
    sha256: str
    stored_locator: str

@dataclass(frozen=True, slots=True)
class ReviewPartAnnotation:
    part_id: str
    parent_target_id: str
    reviewer_run_id: str
    reviewed_part_sha256: str
    complete_part_read: bool
    observations: tuple[ReviewObservation, ...]
    observation_fragments: tuple[ReviewObservationFragment, ...]
    no_observation_reason: str | None

@dataclass(frozen=True, slots=True)
class ReviewPartVerification:
    part_id: str
    parent_target_id: str
    verifier_run_id: str
    reviewed_part_sha256: str
    reviewed_part_annotation_sha256: str
    reviewed_part_verification_sha256: str | None
    complete_part_read: bool
    decision: Literal["accepted", "disputed", "adjudicated"]
    checked_observation_ids: tuple[str, ...]
    checked_fragment_ids: tuple[str, ...]
    correction_part_annotation: ReviewPartAnnotation | None
    reason: str | None

@dataclass(frozen=True, slots=True)
class ReviewBatch:
    batch_id: str
    phase: Literal["primary", "verification", "adjudication"]
    ordinal: int
    target_ids: tuple[str, ...]
    structural_group_ids: tuple[str, ...]
    part_ids: tuple[str, ...]
    review_byte_count: int
    input_sha256: str

@dataclass(frozen=True, slots=True)
class ReviewManifest:
    snapshot_id: str
    phase: Literal["primary", "verification", "adjudication"]
    source_records_sha256: str
    document_claims_sha256: str
    target_ids: tuple[str, ...]
    targets: tuple[ReviewTarget, ...]
    structural_group_ids: tuple[str, ...]
    parts: tuple[ReviewTargetPart, ...]
    part_ids: tuple[str, ...]
    batches: tuple[ReviewBatch, ...]

@dataclass(frozen=True, slots=True)
class ReviewBatchArtifact:
    batch_id: str
    path: Path
    byte_count: int
    sha256: str

@dataclass(frozen=True, slots=True)
class ReviewCoverage:
    expected_target_ids: tuple[str, ...]
    accepted_target_ids: tuple[str, ...]
    findings: tuple["ValidationFinding", ...]

@dataclass(frozen=True, slots=True)
class ObservationRelationEdge:
    edge_id: str
    from_observation_id: str
    to_observation_id: str
    source_relation_id: str
    owner_target_id: str
    target_target_id: str
    owner_evidence_locator: str
    owner_evidence_hash: str
    target_evidence_locator: str
    target_evidence_hash: str

@dataclass(frozen=True, slots=True)
class EvidenceText:
    text: str
    status: str
    raw_status_label: str
    source_ids: tuple[str, ...]
    excerpts: tuple[EvidenceExcerpt, ...]
    source_conflict_ids: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class ConflictValue:
    text: str
    status: str
    raw_status_label: str
    source_ids: tuple[str, ...]
    excerpts: tuple[EvidenceExcerpt, ...]

@dataclass(frozen=True, slots=True)
class SourceConflict:
    conflict_id: str
    field_name: str
    same_execution_key: str | None
    same_execution_relation_ids: tuple[str, ...]
    same_execution_evidence: tuple[EvidenceExcerpt, ...]
    values: tuple[ConflictValue, ...]

@dataclass(frozen=True, slots=True)
class FactSentence:
    fact_id: str
    author_run_id: str
    candidate_ids: tuple[str, ...]
    observation_ids: tuple[str, ...]
    text: str
    status: str
    raw_status_label: str
    source_ids: tuple[str, ...]
    excerpts: tuple[EvidenceExcerpt, ...]
    source_conflicts: tuple[SourceConflict, ...]

@dataclass(frozen=True, slots=True)
class FactVerification:
    verification_id: str
    verifier_run_id: str
    author_run_id: str
    fact_id: str
    reviewed_fact_sha256: str
    reviewed_verification_sha256: str | None
    checked_excerpt_hashes: tuple[str, ...]
    complete_representation_read: bool
    decision: Literal["accepted", "disputed", "adjudicated"]
    checked_source_ids: tuple[str, ...]
    correction_fact: FactSentence | None
    reason: str | None

@dataclass(frozen=True, slots=True)
class FactCandidate:
    candidate_id: str
    destination: Literal["profile", "project"]
    field_key: str
    accepted_observation_ids: tuple[str, ...]
    source_ids: tuple[str, ...]
    excerpts: tuple[EvidenceExcerpt, ...]
    input_sha256: str
    author_visible_byte_count: int

@dataclass(frozen=True, slots=True)
class FactReviewBatch:
    batch_id: str
    phase: Literal["author", "verification", "adjudication"]
    ordinal: int
    candidate_ids: tuple[str, ...]
    fact_ids: tuple[str, ...]
    reviewer_visible_byte_count: int
    input_sha256: str

@dataclass(frozen=True, slots=True)
class FactReviewManifest:
    snapshot_id: str
    phase: Literal["author", "verification", "adjudication"]
    source_records_sha256: str
    document_claims_sha256: str
    classification_decisions_sha256: str
    expected_candidate_ids: tuple[str, ...]
    expected_fact_ids: tuple[str, ...]
    batches: tuple[FactReviewBatch, ...]

@dataclass(frozen=True, slots=True)
class FactDisputeManifest:
    snapshot_id: str
    verification_manifest_sha256: str
    disputed_candidate_ids: tuple[str, ...]
    disputed_fact_ids: tuple[str, ...]
    disputed_verification_sha256s: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class FactAdjudicationReceipt:
    snapshot_id: str
    author_manifest_sha256: str
    verification_manifest_sha256: str
    adjudication_manifest_sha256: str
    dispute_manifest_sha256: str
    authored_candidate_ids: tuple[str, ...]
    authored_fact_ids: tuple[str, ...]
    accepted_candidate_ids: tuple[str, ...]
    accepted_fact_ids: tuple[str, ...]
    adjudicated_candidate_ids: tuple[str, ...]
    adjudicated_fact_ids: tuple[str, ...]
    effective_candidate_ids: tuple[str, ...]
    effective_fact_ids: tuple[str, ...]
    effective_fact_sha256s: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class DiagramNode:
    node_id: str
    label: EvidenceText

@dataclass(frozen=True, slots=True)
class DiagramEdge:
    from_node: str
    to_node: str
    label: EvidenceText

@dataclass(frozen=True, slots=True)
class DiagramSubgraph:
    subgraph_id: str
    title: EvidenceText
    node_ids: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class FlowchartDiagramSpec:
    diagram_type: Literal["flowchart"]
    direction: Literal["TB", "TD", "BT", "RL", "LR"]
    subgraphs: tuple[DiagramSubgraph, ...]
    nodes: tuple[DiagramNode, ...]
    edges: tuple[DiagramEdge, ...]

@dataclass(frozen=True, slots=True)
class SequenceParticipant:
    participant_id: str
    label: EvidenceText

@dataclass(frozen=True, slots=True)
class SequenceMessage:
    from_participant: str
    to_participant: str
    arrow: Literal["->>", "-->>", "-x", "--x"]
    label: EvidenceText

@dataclass(frozen=True, slots=True)
class SequenceDiagramSpec:
    diagram_type: Literal["sequenceDiagram"]
    participants: tuple[SequenceParticipant, ...]
    messages: tuple[SequenceMessage, ...]

@dataclass(frozen=True, slots=True)
class StateDiagramSpec:
    diagram_type: Literal["stateDiagram-v2"]
    states: tuple[DiagramNode, ...]
    transitions: tuple[DiagramEdge, ...]

@dataclass(frozen=True, slots=True)
class TimelineEvent:
    period: EvidenceText
    event: EvidenceText

@dataclass(frozen=True, slots=True)
class TimelineSection:
    title: EvidenceText
    events: tuple[TimelineEvent, ...]

@dataclass(frozen=True, slots=True)
class TimelineDiagramSpec:
    diagram_type: Literal["timeline"]
    sections: tuple[TimelineSection, ...]

type DiagramSpec = (
    FlowchartDiagramSpec
    | SequenceDiagramSpec
    | StateDiagramSpec
    | TimelineDiagramSpec
)

@dataclass(frozen=True, slots=True)
class CaseDefinition:
    definition_id: str
    author_run_id: str
    relation_component_id: str
    observation_ids: tuple[str, ...]
    continuation_group_ids: tuple[str, ...]
    project_id: str
    ordinal: int
    case_kind: str
    canonical_title: EvidenceText
    domain: EvidenceText
    problem: EvidenceText
    solution: EvidenceText
    results: tuple[EvidenceText, ...]
    measurement_conditions: tuple[EvidenceText, ...]
    recorded_status: str
    raw_status_label: str
    source_conflicts: tuple[SourceConflict, ...]
    diagram: DiagramSpec

@dataclass(frozen=True, slots=True)
class CaseDefinitionVerification:
    verification_id: str
    verifier_run_id: str
    author_run_id: str
    definition_id: str
    reviewed_definition_sha256: str
    reviewed_verification_sha256: str | None
    checked_excerpt_hashes: tuple[str, ...]
    complete_representation_read: bool
    decision: Literal["accepted", "disputed", "adjudicated"]
    checked_observation_ids: tuple[str, ...]
    correction_definition: CaseDefinition | None
    reason: str | None

@dataclass(frozen=True, slots=True)
class CaseAuthoringPart:
    part_id: str
    relation_component_ids: tuple[str, ...]
    observation_ids: tuple[str, ...]
    internal_relation_ids: tuple[str, ...]
    boundary_relation_ids: tuple[str, ...]
    input_sha256: str
    author_visible_byte_count: int

@dataclass(frozen=True, slots=True)
class CaseDefinitionBatch:
    batch_id: str
    phase: Literal["author", "verification", "adjudication"]
    ordinal: int
    authoring_part_ids: tuple[str, ...]
    observation_ids: tuple[str, ...]
    definition_ids: tuple[str, ...]
    reviewer_visible_byte_count: int
    input_sha256: str

@dataclass(frozen=True, slots=True)
class CaseDefinitionManifest:
    snapshot_id: str
    phase: Literal["author", "verification", "adjudication"]
    observation_relations_sha256: str
    expected_observation_ids: tuple[str, ...]
    expected_relation_ids: tuple[str, ...]
    expected_definition_ids: tuple[str, ...]
    parts: tuple[CaseAuthoringPart, ...]
    batches: tuple[CaseDefinitionBatch, ...]

@dataclass(frozen=True, slots=True)
class CaseDefinitionDisputeManifest:
    snapshot_id: str
    verification_manifest_sha256: str
    disputed_definition_ids: tuple[str, ...]
    disputed_verification_sha256s: tuple[str, ...]
    disputed_observation_ids: tuple[str, ...]
    disputed_relation_ids: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class CaseDefinitionAdjudicationReceipt:
    snapshot_id: str
    dispute_manifest_sha256: str
    adjudication_manifest_sha256: str
    accepted_definition_ids: tuple[str, ...]
    adjudicated_definition_ids: tuple[str, ...]
    effective_definition_ids: tuple[str, ...]
    effective_observation_ids: tuple[str, ...]
    effective_relation_ids: tuple[str, ...]
    effective_definition_sha256s: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class RequiredClaim:
    requirement_id: str
    exact_token_groups: tuple[tuple[str, ...], ...]
    allowed_statuses: tuple[str, ...]
    required_condition_tokens: tuple[str, ...]
    allowed_source_path_patterns: tuple[str, ...]
    forbidden_cooccurrence_token_groups: tuple[tuple[str, ...], ...]

@dataclass(frozen=True, slots=True)
class RequiredClaimReport:
    satisfied_requirement_ids: tuple[str, ...]
    missing_requirement_ids: tuple[str, ...]
    findings: tuple[ValidationFinding, ...]

@dataclass(frozen=True, slots=True)
class ProfileRecord:
    record_id: str
    section: Literal[
        "motivation", "introduction", "profile", "open-source",
        "career", "education", "certificate"
    ]
    ordinal: int
    field_name: str
    fact: FactSentence

@dataclass(frozen=True, slots=True)
class ProjectRecord:
    project_id: str
    title: FactSentence
    monthly_period: FactSentence
    technologies: tuple[FactSentence, ...]
    participant_counts: tuple[FactSentence, ...]
    overview: FactSentence

@dataclass(frozen=True, slots=True)
class CaseRecord:
    case_id: str
    project_id: str
    ordinal: int
    case_kind: str
    observation_ids: tuple[str, ...]
    continuation_group_ids: tuple[str, ...]
    canonical_title: EvidenceText
    domain: EvidenceText
    problem: EvidenceText
    solution: EvidenceText
    results: tuple[EvidenceText, ...]
    measurement_conditions: tuple[EvidenceText, ...]
    recorded_status: str
    raw_status_label: str
    source_conflicts: tuple[SourceConflict, ...]
    source_ids: tuple[str, ...]
    relation_evidence_ids: tuple[str, ...]
    diagram: DiagramSpec

@dataclass(frozen=True, slots=True)
class CaseSourceLink:
    target_id: str
    observation_id: str
    case_id: str | None
    disposition: Literal["case", "record-only"]

@dataclass(frozen=True, slots=True)
class ClassifiedObservation:
    observation_id: str
    disposition: Literal["case", "record-only"]
    evidence_scope: str
    claim_authority: str
    claim_stage: str
    corroborating_relation_ids: tuple[str, ...]
    fact_candidate_keys: tuple[str, ...]
    no_fact_candidate_reason: str | None
    case_ids: tuple[str, ...]
    record_only_reason: str | None
    normalized_status: str
    raw_status_label: str

@dataclass(frozen=True, slots=True)
class ClassifiedSourceRecord:
    target_id: str
    target_kind: Literal["source-record", "document-claim"]
    snapshot_id: str
    stored_hash: str
    decision_sha256: str
    observations: tuple[ClassifiedObservation, ...]

@dataclass(frozen=True, slots=True)
class ReleaseCoverageManifest:
    snapshot_id: str
    capture_coverage_sha256: str
    classification_decisions_sha256: str
    case_catalog_sha256: str
    case_source_map_sha256: str
    source_conflicts_sha256: str
    observation_relations_sha256: str
    profile_facts_sha256: str
    profile_fact_verifications_sha256: str
    project_catalog_sha256: str
    project_fact_verifications_sha256: str
    fact_adjudications_sha256: str
    fact_adjudication_receipt_sha256: str
    classified_target_ids: tuple[str, ...]
    classified_observation_ids: tuple[str, ...]
    case_observation_ids: tuple[str, ...]
    record_only_observation_ids: tuple[str, ...]
    catalog_case_ids: tuple[str, ...]
    source_conflict_ids: tuple[str, ...]
    document_unit_ids: tuple[str, ...]
    document_hashes: dict[str, str]
    checks: dict[str, int]
    findings: tuple["ValidationFinding", ...]

@dataclass(frozen=True, slots=True)
class ValidationFinding:
    code: str
    target_id: str | None
    message: str

@dataclass(frozen=True, slots=True)
class ContentVerificationReport:
    checks: dict[str, int]
    findings: tuple[ValidationFinding, ...]

type ExplicitRelationGraph = dict[str, tuple[ExplicitRelation, ...]]
type FactRegistry = dict[str, FactSentence]
```

For primary target-level `accepted`/`disputed` verifications, `reviewed_verification_sha256` is `None`; an `adjudicated` record must point to the canonical hash of exactly one disputed verification. The same rule applies part-by-part through `reviewed_part_verification_sha256`, and a corrected part annotation must be embedded rather than overwriting its primary. Every normalized status stores the source wording separately in `raw_status_label`; mapping is allowed, rewriting or discarding the source label is not.

`SourceConflict` is not a replacement status. It is created only when explicit evidence says two or more different values describe the same execution/field and no later source corrects one of them. It must carry either a source-backed `same-execution` relation or `same_execution_evidence` from every side containing the same explicit run identifier/reference; date, environment, command, filename, or numeric similarity alone never qualifies. Every conflicting `ConflictValue` remains independently cited and rendered; validators reject selecting one value, averaging/recalculating them, merging different executions into a conflict, omitting a side, or inventing a resolution. The complete conflict object is carried by every affected case/fact, deduplicated into the registry, and listed in both final documents wherever that fact appears.

Canonical case order is `(project_id, ordinal, case_id)`. Markdown case markers are exactly:

```text
<!-- CASE:<case-id>:START -->
<!-- CASE:<case-id>:END -->
```

Both writers use the same title/citation serializer; neither constructs a case heading independently.

```python
def format_source_ids(source_ids: Sequence[str]) -> str:
    ordered = tuple(sorted(set(source_ids), key=lambda value: value.encode("utf-8")))
    return "[근거: " + ", ".join(f"`{source_id}`" for source_id in ordered) + "]"

def rendered_case_title(case: CaseRecord) -> str:
    return f"{case.canonical_title.text} {format_source_ids(case.canonical_title.source_ids)}"

def render_case_markers(case_id: str, heading: str, body: str) -> str:
    return (
        f"<!-- CASE:{case_id}:START -->\n"
        f"{heading}\n\n{body.rstrip()}\n"
        f"<!-- CASE:{case_id}:END -->\n"
    )
```

---

### Task 1: Add deterministic review targets, batches, and validation

**Files:**

- Modify: `docs/Portfolio_Book/tools/portfolio_builder/models.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/review_batches.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/review_validation.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/reviewer_protocol.md`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/test_review_batches.py`
- Create: `docs/Portfolio_Book/tests/test_review_validation.py`

**Interfaces:**

```python
def build_review_manifest(
    sources: Sequence[SourceRecord],
    claims: Sequence[DocumentClaim],
    max_parts: int = 12,
    max_review_bytes: int = 96_000,
    max_part_bytes: int = 32_000,
) -> ReviewManifest

def materialize_review_batches(
    manifest: ReviewManifest,
    evidence_root: Path,
    destination: Path,
) -> tuple[ReviewBatchArtifact, ...]

def build_phase_review_manifest(
    primary_manifest: ReviewManifest,
    phase: Literal["verification", "adjudication"],
    prior_artifacts: Sequence[Path],
    max_parts: int = 12,
    max_review_bytes: int = 96_000,
) -> ReviewManifest

def validate_annotations(
    manifest: ReviewManifest,
    sources: Sequence[SourceRecord],
    claims: Sequence[DocumentClaim],
    annotations: Sequence[ReviewAnnotation],
    verifications: Sequence[ReviewVerification],
) -> ReviewCoverage
```

- [ ] **Step 1: Write failing individual-target, byte-range, and batch tests**

Use 107 fixture targets with varied member sizes, including members larger than one batch and one collector-proved logical claim whose bytes cross three stored members. Before batching, derive immutable structural groups from JSON/JSONL record boundaries, diff file/hunk boundaries, Markdown/PDF claim/unit boundaries, or exact opaque byte slices. Every group is capped at 12 parts, 32,000 safe source bytes, and 96,000 complete reviewer-visible bytes; a larger logical unit becomes multiple independent groups at an exact syntax/range boundary rather than one cross-batch semantic observation. Assert stable UTF-8 target/member/logical-unit/group/range order, byte-identical manifests, every group remains wholly inside one batch, every batch at most 12 parts, every part and final canonical `ReviewObservation` at most 32,000 bytes, and an exact target/member byte-range union with no gap, overlap, empty range, or duplicate. For primary, verification, and adjudication phases alike, `ReviewBatch.review_byte_count` must equal the exact byte count of the canonical reviewer-visible batch artifact—including safe part bytes, target metadata, relations, prior annotations/verification reasons, and supplied context—and remain at most 96,000; no referenced ledger or prior-artifact body may be injected outside that measured artifact. Phase-specific rebatching may change batch boundaries but must preserve each structural group atomically and preserve the exact target/member/group/part/range set. Fragments may join only inside one manifest-fixed structural group, must carry its structural group ID while sharing one deterministic group-local observation ID and exact fragment ordinal/total, and must assemble into one byte-identical observation in the same bounded artifact; a reviewer-created cross-group ID, missing, duplicated, reordered, stale-hash, inconsistent-status/disposition, or oversized assembled observation must fail. A source claim too large for one observation is partitioned into multiple deterministic group observations rather than truncated or semantically synthesized. Mutating a source member after parting, manipulating manifest targets while leaving parts self-consistent, incomplete primary/verifier group or part coverage, or `complete_part_read=False` must also fail. Assert wildcard IDs, range decisions, stale stored hashes, incomplete or duplicated target/member/group unions, empty target observations, unsupported empty group receipts, `complete_representation_read=False`, missing observation-level record-only reasons, unknown relation targets, duplicate target decisions, missing run IDs, same-role run-ID reuse, stale verification hashes, and adjudications that do not hash the exact disputed verification all fail. Reject `record-only` for an atomic group observation containing a source-backed problem/failure even when no diagnosis or fix is recorded, solution/implemented change even when its motivating problem is in another group, result/measurement, problem-plus-solution, any failed experiment, fixed/reverted/rolled-back failure, diagnosed problem even when no fix was applied, implemented feature/guardrail, implemented-but-verification-pending change, verified test/run, or upstream-merged contribution. A different inert observation in the same target may remain record-only with its own cited reason.

Add authority/stage negative fixtures. Every renewal-guide target and observation must retain `structural-reference/structural-reference` and accept only record-only; attempting to cite it in a case/profile/project fact fails. AI prose saying "implemented" and a legacy generated report asserting a result, with no exact relation to a non-derived source supporting the same words/execution, must remain `unverified-assertion` record-only; a matching full commit/diff/tool-result relation permits only the portion supported by that source, and missing/wrong relation IDs fail. Conversely, an exact `trace-observation` tool result/error/exit or immutable patch/log may support only the bytes and status it records; a command input alone is at most `attempted`, and truncated/missing output cannot become successful/implemented/measured. An issue or ADR containing only proposed/planned/target/expected problem-and-solution text must be legal record-only and must fail case promotion, while an explicitly attempted/diagnosed/implemented/failed observation remains mandatory. Scope/authority must be byte-identical from source/claim through manifest and effective annotation. Stage/corroborating relations originate in the primary observation, remain exact in part→target merge, are checked via observation/relation IDs plus canonical annotation hashes by verifiers/adjudicators, and must equal the final effective/ClassifiedObservation values.

In every mandatory-case assertion above and below, "source-backed problem/solution/result" means the observation carries an actuality stage from `observed`, `attempted`, `diagnosed`, `implemented`, `measured`, `merged`, `failed`, `reverted`, or `rolled-back`. It never includes `proposed`, `planned`, `target`, `expected`, `unverified-assertion`, or `metadata` by wording alone.

The manifest preassigns `structural_group_id`, not the unknown semantic observation count. Inside one wholly co-located group, the reviewer assigns deterministic source-order IDs `<structural_group_id>:O0001`, `:O0002`, and so on; fragments for one observation use that observation ID as `observation_group_id` and also carry the separate structural group ID. Validators require contiguous group-local ordinals, unique IDs, exact fragment unions, and no cross-group observation. This allows multiple independent observations in one diff hunk/JSON record without collisions.

```python
def test_every_target_requires_one_individual_annotation():
    with pytest.raises(ReviewCoverageError, match="DOC-0002"):
        validate_annotations(
            manifest, sources, claims, annotations_for_only_doc_0001, []
        )

def test_batching_is_a_checkpoint_not_a_limit():
    manifest = build_review_manifest(
        sources, claims, max_parts=12, max_review_bytes=96_000, max_part_bytes=32_000
    )
    assert all(len(batch.part_ids) <= 12 for batch in manifest.batches)
    assert all(batch.review_byte_count <= 96_000 for batch in manifest.batches)
    assert exact_part_range_union(manifest.targets, manifest.parts)
```

Run: `cd docs/Portfolio_Book && python3 tools/run_portfolio_command.py -- uv run pytest tests/test_review_batches.py tests/test_review_validation.py -q`

Expected: FAIL because the review modules do not exist.

- [ ] **Step 2: Implement the models and deterministic batch materializer**

Split each safe stored member deterministically into logical JSON/JSONL records/scalars, diff file/hunks/lines, Markdown/PDF blocks/claims, or line-preserving opaque units. From those syntax/range facts, build deterministic `structural_group_id` values before human review. A group contains at most 12 parts, at most 32,000 safe source bytes, and at most 96,000 bytes after complete reviewer metadata is serialized. If any limit would be exceeded, split at the next exact syntax, field, line, or UTF-8-safe opaque range boundary and give each resulting group a separate ID; never ask reviewers in different batches to coordinate a semantic observation. Each `ReviewTargetPart` records its parent target/member, logical-unit/group IDs and ordinals/totals, half-open owned byte range, exact bytes/hash, and locator. Adjacent pieces retain only the collector-proved same-logical-unit/range relation; they do not acquire semantic relations. Batch whole structural groups by both limits, greedily measuring the final canonical serialized artifact after adding IDs, hashes, relevant explicit relations, and bounded context; if the next complete group would make that file exceed 96,000 bytes, start another batch. One large target therefore spans as many batches as required instead of exceeding a reviewer context. Every phase-specific batch must keep a group whole. Each batch file contains everything the reviewer may read for that evidence batch and never embeds unredacted bytes; external ledgers/archives are locator stores used by validators, not extra unmetered prompt content. The immutable manifest embeds the complete `ReviewTarget` values from the frozen ledgers, batch ordinal, target/group/part/logical-unit IDs, original hashes for identity, stored and part hashes for review verification, byte-range/member-union hashes, exact serialized review byte count, and input SHA-256. Validators reassemble every part against each manifest target's `StoredArtifactMember.byte_count` and SHA-256, then require the manifest target set and hashes to equal the frozen source/claim ledgers; parts cannot serve as their own completeness authority. The orchestrator gives every fresh agent an immutable unique run ID. Primary/verification/adjudication state is computed solely from validated output-file presence, distinct role/run IDs, exact target/member/group/byte-range/logical-unit unions, and canonical hash chains; it is never written back into the manifest.

- [ ] **Step 3: Write the mandatory reviewer protocol**

The protocol requires part reviewers to:

1. resolve and read every assigned part completely and verify its parent member, byte range, and hash;
2. inspect every file/change or child event represented inside that part;
3. emit complete observations in source order inside each manifest-fixed structural group using group-local IDs `<structural_group_id>:O0001...`; if one observation's collector-proved logical bytes cross parts, its fragments share that observation ID, carry the separate preassigned structural group ID and exact fragment ordinal/total, and remain inside the same bounded batch; carry the target's exact scope/authority, one cited `claim_stage`, only ledger-resolved corroborating relation IDs, and controlled fact keys (`profile:<field>` or `project:<stable-project-key>:<field>`) or an explicit no-fact-candidate reason; never create a cross-group/cross-batch observation ID;
4. force structural-reference, uncorroborated AI/legacy-derived assertions, and proposed/planned/target/expected-only observations to `record-only` with a concrete source-based reason; for eligible actuality evidence, use record-only only when that atomic observation supports no mandatory case category; other observations from the same target retain their own independent dispositions;
5. use `not-recorded` rather than filling absent problem/solution/result fields;
6. cite only relations already present in `explicit_relations`;
7. report redaction/unavailable/parse limits without guessing hidden content.

After all groups for a target pass, `merge-primary-parts` first validates every fragment's group-local observation ID, separate manifest structural group ID, exact ordinal/hash/logical-unit union, and byte-identical metadata, then mechanically concatenates each field's exact fragment text/excerpts into one bounded `ReviewObservation`. The manifest generator, not a reviewer, creates deterministic structural group and same-logical-unit continuation IDs at exact syntax/field/range boundaries when one row would exceed 32,000 bytes. The merger concatenates complete observations and same-observation fragments in stable range order into one `ReviewAnnotation`; it cannot join observations from different groups, summarize, reinterpret, synthesize prose/relations, or silently resolve inconsistent fragment metadata. A problem in one group and a solution/result in another therefore remain separately exhaustive case observations unless a captured explicit relation independently proves grouping. Every split observation retains its same-logical-unit continuation group IDs as non-semantic related evidence. Any ambiguity becomes a dispute, not a guessed observation. `complete_representation_read=True` is derived only from the exact target/member/group/part/byte-range/logical-unit union, not accepted as an unsupported target-level assertion.

For compatibility, target aggregates retain fields named `reviewer_run_id`, `verifier_run_id`, and `primary_reviewer_run_id`; on mechanically merged target rows these identify immutable aggregation invocations, not a claim that one reviewer held the whole target in context. Human review identity and independence are proven by each `ReviewPartAnnotation.reviewer_run_id` and corresponding `ReviewPartVerification.verifier_run_id`. Validators require exact part-artifact hashes and distinct role IDs for every part before deriving any target-level read flag or decision.

The protocol also repeats the non-optional case threshold from the approved design: for eligible non-structural, non-derived authority with an actuality stage, create a case observation for any recorded problem/failure even with no diagnosis or fix, solution/implemented change even when its problem is elsewhere, problem-plus-solution, before/after result, failed experiment, reproduced failure with fix or rollback, diagnosed problem with no fix applied, implemented feature/architecture/operational guardrail, implemented change whose verification is pending, test/long-run/load result, upstream merge, or result-only achievement. A `trace-observation` is eligible only for its exact output/error/exit/patch/log bytes and recorded limitation; command-only or truncated data cannot claim a result. Corroborated AI/legacy-derived text may accompany only the exact primary/personal/trace-observation claim it points to and never supplies an unsupported phrase. For problem-only or diagnosis-only observations, preserve what is recorded and mark only the genuinely absent solution/result fields `not-recorded`; for solution-only observations, mark only the absent problem/result fields `not-recorded`. `record-only` is decided per atomic structural-group observation and is legal only when an eligible actuality observation supports none of those categories; it is never inferred from another part or applied once to an entire mixed target.

- [ ] **Step 4: Implement validation and CLI commands**

Add `make-review-batches`, `make-phase-review-batches`, `review-status`, `validate-primary-parts`, `merge-primary-parts`, `validate-primary-reviews`, `validate-verification-parts`, `merge-verification-parts`, `validate-review-verifications`, `validate-adjudication-parts`, `merge-adjudication-parts`, and `merge-reviewed-annotations`. Every validator receives or resolves the frozen source/claim ledgers and checks their exact target ID/raw-hash/stored-hash/member/scope/authority set against `ReviewManifest.targets` before trusting parts. Validation then checks exact target/member/structural-group coverage; exact nonempty, nonoverlapping, gap-free part/logical-unit ranges; whole-group batch atomicity and exact serialized byte limits for every phase-specific reviewer-visible artifact; manifest-fixed fragment ordinal/text/hash/metadata unions; exact primary/verifier/adjudicator group and part unions; excerpt text inside the cited safe part bytes; unique observation/fragment IDs; distinct corresponding part-reviewer/verifier/adjudicator run IDs; exact part-annotation→target-annotation→verification→adjudication hashes; normalized status vocabulary plus byte-identical `raw_status_label`; controlled fact-key/no-candidate exact partition; scope/authority/stage preservation; exact corroborating relation resolution and same-assertion primary support; structural-reference and proposed/planned/target/expected-only case prohibition; relation edges; observation-level mandatory case-category rules including observed problem-only, failed/diagnosis-only/pending-verification cases; and secret/contact patterns. No reviewer is asked to prove or reproduce inaccessible original bytes represented only by `raw_hash`.

- [ ] **Step 5: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- \
  uv run pytest tests/test_review_batches.py tests/test_review_validation.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/models.py \
  docs/Portfolio_Book/tools/portfolio_builder/review_batches.py \
  docs/Portfolio_Book/tools/portfolio_builder/review_validation.py \
  docs/Portfolio_Book/tools/portfolio_builder/reviewer_protocol.md \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_review_batches.py \
  docs/Portfolio_Book/tests/test_review_validation.py
git diff --cached --check
git commit -m "feat(portfolio): add exhaustive evidence review workflow"
```

---

### Task 2: Materialize the real review manifest

**Files:**

- Generate: `docs/Portfolio_Book/output/research/review_batches/manifest.json`
- Generate: `docs/Portfolio_Book/output/research/review_batches/inputs/*.jsonl`

**Interfaces:**

- Consumes the byte-frozen evidence outputs from the capture plan.
- Produces the complete ordered work queue for primary and independent review.

- [ ] **Step 1: Generate batches from both ledgers**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-review-batches \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --evidence-root output/research \
  --max-parts 12 \
  --max-review-bytes 96000 \
  --max-part-bytes 32000 \
  --output output/research/review_batches
```

Expected: every source ID and document-claim ID appears once in the target manifest; every derived part ID appears in exactly one bounded batch; output reports exact target, member, part, byte, and batch counts.

- [ ] **Step 2: Verify the manifest against the snapshot**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book review-status \
  --manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --phase inputs
```

Expected: `missing_targets=0`, `duplicate_targets=0`, `missing_parts=0`, `duplicate_parts=0`, `part_range_gaps=0`, `part_range_overlaps=0`, `stale_target_hashes=0`, `stale_part_hashes=0`, `oversized_parts=0`, `oversized_batches=0`, and every input hash valid.

- [ ] **Step 3: Commit only the deterministic work queue**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add docs/Portfolio_Book/output/research/review_batches/manifest.json \
  docs/Portfolio_Book/output/research/review_batches/inputs
git diff --cached --check
git commit -m "data(portfolio): queue every evidence target for review"
```

---

### Task 3: Perform the primary read of every target

**Files:**

- Generate: `docs/Portfolio_Book/output/research/annotations/primary-parts/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/annotations/primary/targets.jsonl`

**Interfaces:**

- Consumes one byte-bounded part batch plus safe stored locators and raw identity hashes at a time; it never opens an unredacted raw payload.
- Produces exactly one `ReviewPartAnnotation` per part, then a deterministic target aggregate with exactly one `ReviewAnnotation` per source/document target.

- [ ] **Step 1: Dispatch every pending primary batch to a fresh reviewer**

Iterate bounded part batches in numeric ordinal order. For each entry, give a fresh review agent only the design spec, `reviewer_protocol.md`, and the measured batch artifact containing the exact safe bytes to review. Do not inject whole frozen ledgers, archive members, or unrelated context. Validators may resolve recorded locators afterward to prove hashes/excerpts. The reviewer writes the corresponding `annotations/primary-parts/<batch-id>.jsonl`. At most three non-overlapping batches run concurrently; no reviewer edits shared code or another batch file. No target size changes these byte limits; a large target simply creates more batches.

The executor must continue until this command reports no pending batch:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book review-status \
  --manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --phase primary
```

- [ ] **Step 2: Validate every part, then aggregate targets mechanically**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-primary-parts \
  --manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --annotations output/research/annotations/primary-parts
python3 tools/run_portfolio_command.py -- uv run portfolio-book merge-primary-parts \
  --manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --part-annotations output/research/annotations/primary-parts \
  --output output/research/annotations/primary/targets.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-primary-reviews \
  --manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --annotations output/research/annotations/primary/targets.jsonl
```

A failure names the exact batch/part/target/member/range. Return that bounded batch to a fresh reviewer; never suppress a missing or unread part. `merge-primary-parts` performs only stable concatenation and hash/coverage derivation; it cannot add, rewrite, relate, or drop an observation.

- [ ] **Step 3: Checkpoint without selecting or truncating**

After every 20 manifest ordinals, and after the final remainder, stage only those validated part-annotation files and the current derived target aggregate, then commit with `data(portfolio): checkpoint primary evidence review`. The immutable manifest is not rewritten. This checkpoint cadence does not stop or reduce the full manifest.

- [ ] **Step 4: Pass the complete primary gate**

Expected final output: `pending_batches=0`, `missing_targets=0`, `duplicate_targets=0`, `missing_parts=0`, `duplicate_parts=0`, `unread_parts=0`, `stale_stored_hashes=0`, `stale_part_hashes=0`, `part_range_gaps=0`, `part_range_overlaps=0`, `fragment_group_gaps=0`, `fragment_metadata_conflicts=0`, `uncovered_members=0`, `duplicate_members=0`, `unread_targets=0`, `invalid_excerpts=0`, `invalid_relations=0`, and `secret_findings=0`.

---

### Task 4: Independently verify every primary decision and adjudicate disputes

**Files:**

- Generate: `docs/Portfolio_Book/output/research/annotations/verification-parts/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/annotations/verification/targets.jsonl`
- Generate: `docs/Portfolio_Book/output/research/review_batches/verification/manifest.json`
- Generate: `docs/Portfolio_Book/output/research/review_batches/verification/inputs/*.jsonl`
- Generate when needed: `docs/Portfolio_Book/output/research/annotations/adjudication-parts/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/annotations/adjudication/targets.jsonl`
- Generate: `docs/Portfolio_Book/output/research/review_batches/adjudication/manifest.json`
- Generate when needed: `docs/Portfolio_Book/output/research/review_batches/adjudication/inputs/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/classification_decisions.jsonl`

**Interfaces:**

- Consumes the same frozen bounded parts plus their primary part annotations.
- Produces one independent `ReviewPartVerification` per part, a hash-chained `ReviewVerification` per target, and one final accepted/adjudicated annotation.

- [ ] **Step 1: Dispatch every verification batch to a different fresh reviewer**

First run `make-phase-review-batches --phase verification` with the primary manifest and validated primary-part annotations. It re-batches the exact same structural-group set so each final canonical verifier-visible input—including safe source bytes and the relevant primary annotation—remains at most 96,000 bytes; it records an immutable verification manifest and inputs. If one atomic group-plus-annotation alone exceeds the limit, there is no oversized exception: deterministically split that structural group itself at an exact syntax/field/range boundary into new group IDs, invalidate the old manifest and every affected primary/phase artifact, regenerate the affected manifests, and repeat fresh primary review until each group fits. Merely subdividing parts while preserving the same atomic group is forbidden because it cannot reduce reviewer-visible bytes. Dispatch the bounded artifacts to fresh verifiers. For each group/part, the verifier must re-read the complete safe bytes and check every observation/fragment, excerpt, normalized/raw status pair, relation, missing-field marker, and record-only reason. The primary part annotation is evidence to audit, not authority. Each verifier records a run ID different from that part's primary reviewer, the exact part/range hash and read flag, and the canonical SHA-256 of the exact part annotation reviewed, then emits `accepted` or `disputed`. After all parts arrive, `merge-verification-parts` deterministically derives the target `ReviewVerification`, exact member/group/part union, and target decision; any disputed part disputes the target. Silence, group/part/member omission, corresponding-role run-ID reuse, an oversized phase artifact, or a post-verification hash change is a coverage failure.

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-phase-review-batches \
  --phase verification \
  --primary-manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --primary-parts output/research/annotations/primary-parts \
  --max-parts 12 \
  --max-review-bytes 96000 \
  --output output/research/review_batches/verification
python3 tools/run_portfolio_command.py -- uv run portfolio-book review-status \
  --manifest output/research/review_batches/verification/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --phase verification
```

Dispatch and checkpoint until this phase-specific status reports `pending_batches=0`; a primary batch boundary is never assumed to be a valid verifier batch boundary.

- [ ] **Step 2: Materialize and resolve the dispute queue**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-verification-parts \
  --manifest output/research/review_batches/manifest.json \
  --phase-manifest output/research/review_batches/verification/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --primary-parts output/research/annotations/primary-parts \
  --verification-parts output/research/annotations/verification-parts
python3 tools/run_portfolio_command.py -- uv run portfolio-book merge-verification-parts \
  --manifest output/research/review_batches/manifest.json \
  --phase-manifest output/research/review_batches/verification/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --primary-parts output/research/annotations/primary-parts \
  --primary output/research/annotations/primary/targets.jsonl \
  --verification-parts output/research/annotations/verification-parts \
  --output output/research/annotations/verification/targets.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-review-verifications \
  --manifest output/research/review_batches/manifest.json \
  --phase-manifest output/research/review_batches/verification/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --primary-parts output/research/annotations/primary-parts \
  --primary output/research/annotations/primary/targets.jsonl \
  --verification-parts output/research/annotations/verification-parts \
  --verification output/research/annotations/verification/targets.jsonl \
  --write-disputes output/research/review_batches/disputes.json
```

Run `make-phase-review-batches --phase adjudication` from the exact dispute set, safe source groups/parts, primary part annotations, and disputed part verifications. It writes a canonical empty manifest when there are no disputes; otherwise it re-batches every disputed structural group so the complete adjudicator-visible artifact remains at most 96,000 bytes. A single oversized adjudication unit triggers the same deterministic structural-group split at an exact syntax/field/range boundary and complete fresh primary→verification chain for every affected new group; part-only subdivision is forbidden and nothing is truncated. For each disputed group/part, a third fresh reviewer with a third distinct run ID re-reads that complete bounded artifact, then writes an adjudicated part record hash-linked to the exact disputed verification. The target adjudication is mechanically derived only after every disputed part is resolved and the full group/part/member/range union is exact. Its `reviewed_annotation_sha256` matches the immutable primary target annotation, `reviewed_verification_sha256` matches the exact disputed target verification, `decision` is `adjudicated`, and its `correction_annotation` is the stable union of corrected part observations; no target-level prose synthesis, majority vote, inferred compromise, or uncovered bytes are allowed. The cited source controls.

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-phase-review-batches \
  --phase adjudication \
  --primary-manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --disputes output/research/review_batches/disputes.json \
  --primary-parts output/research/annotations/primary-parts \
  --verification-parts output/research/annotations/verification-parts \
  --max-parts 12 \
  --max-review-bytes 96000 \
  --output output/research/review_batches/adjudication
python3 tools/run_portfolio_command.py -- uv run portfolio-book review-status \
  --manifest output/research/review_batches/adjudication/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --phase adjudication
```

When disputes exist, dispatch and checkpoint until the adjudication status reports `pending_batches=0`; an empty dispute manifest reports zero immediately.

After the bounded adjudication queue is complete, validate and merge it explicitly:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-adjudication-parts \
  --manifest output/research/review_batches/manifest.json \
  --phase-manifest output/research/review_batches/adjudication/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --disputes output/research/review_batches/disputes.json \
  --primary-parts output/research/annotations/primary-parts \
  --verification-parts output/research/annotations/verification-parts \
  --adjudication-parts output/research/annotations/adjudication-parts
python3 tools/run_portfolio_command.py -- uv run portfolio-book merge-adjudication-parts \
  --manifest output/research/review_batches/manifest.json \
  --phase-manifest output/research/review_batches/adjudication/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --disputes output/research/review_batches/disputes.json \
  --primary-parts output/research/annotations/primary-parts \
  --primary output/research/annotations/primary/targets.jsonl \
  --verification-parts output/research/annotations/verification-parts \
  --verification output/research/annotations/verification/targets.jsonl \
  --adjudication-parts output/research/annotations/adjudication-parts \
  --output output/research/annotations/adjudication/targets.jsonl
```

Both commands are deterministic and refuse unresolved or extra parts. With no disputes, `validate-adjudication-parts` requires no part files and `merge-adjudication-parts` still writes the canonical empty target JSONL, so the next command has one unambiguous path.

- [ ] **Step 3: Merge only accepted/adjudicated decisions**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book merge-reviewed-annotations \
  --manifest output/research/review_batches/manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --primary output/research/annotations/primary/targets.jsonl \
  --verification output/research/annotations/verification/targets.jsonl \
  --adjudication output/research/annotations/adjudication/targets.jsonl \
  --output output/research/classification_decisions.jsonl
```

Expected: `unverified_targets=0`, `unverified_parts=0`, `unverified_fragments=0`, `unresolved_disputes=0`, `reviewer_role_reuse=0`, `stale_hash_chain=0`, `verifier_uncovered_parts=0`, `verifier_uncovered_members=0`, `part_range_gaps=0`, `part_range_overlaps=0`, `fragment_group_gaps=0`, `fragment_metadata_conflicts=0`, `unassigned_targets=0`, and final target/stored-hash/member/part/byte-range/fragment unions exactly equal the review manifest and part artifacts.

- [ ] **Step 4: Checkpoint and commit the completed review set**

Use the same 20-batch checkpoint rule while verification is in progress. After the final merge:

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add docs/Portfolio_Book/output/research/review_batches/manifest.json \
  docs/Portfolio_Book/output/research/review_batches/disputes.json \
  docs/Portfolio_Book/output/research/annotations \
  docs/Portfolio_Book/output/research/classification_decisions.jsonl
git diff --cached --check
git commit -m "data(portfolio): verify every evidence classification"
```

If no disputes occurred, do not create an `adjudication-parts` directory; `disputes.json` records an empty array and `annotations/adjudication/targets.jsonl` is the canonical empty merge output with a recorded hash.

---

### Task 5: Author and verify every case definition from accepted excerpts

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/case_catalog.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/required_claims.json`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/test_case_catalog.py`
- Create: `docs/Portfolio_Book/tests/test_required_claims.py`
- Generate: `docs/Portfolio_Book/output/research/case_definition_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/observation_relations.jsonl`
- Generate: `docs/Portfolio_Book/output/research/case_definition_batches/author/inputs/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/case_definition_batches/verification/manifest.json`
- Generate: `docs/Portfolio_Book/output/research/case_definition_batches/verification/inputs/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/case_definition_batches/adjudication/manifest.json`
- Generate when needed: `docs/Portfolio_Book/output/research/case_definition_batches/adjudication/inputs/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/case_definitions/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/case_definition_verifications/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/case_definition_adjudications/disputes.json`
- Generate: `docs/Portfolio_Book/output/research/case_definition_adjudications/receipt.json`
- Generate when needed: `docs/Portfolio_Book/output/research/case_definition_adjudications/*.jsonl`

**Interfaces:**

```python
def build_case_definition_manifest(
    decisions: Sequence[ReviewAnnotation],
    observation_relations: Sequence[ObservationRelationEdge],
    max_observations: int = 12,
    max_author_bytes: int = 96_000,
) -> CaseDefinitionManifest

def build_observation_relation_edges(
    decisions: Sequence[ReviewAnnotation],
    relations: ExplicitRelationGraph,
) -> tuple[ObservationRelationEdge, ...]

def build_case_definition_phase_manifest(
    author_manifest: CaseDefinitionManifest,
    phase: Literal["verification", "adjudication"],
    prior_artifacts: Sequence[Path],
    max_parts: int = 12,
    max_reviewer_bytes: int = 96_000,
) -> CaseDefinitionManifest

def validate_case_definition_verifications(
    author_manifest: CaseDefinitionManifest,
    verification_manifest: CaseDefinitionManifest,
    definitions: Sequence[CaseDefinition],
    verifications: Sequence[CaseDefinitionVerification],
) -> CaseDefinitionDisputeManifest

def validate_case_definition_adjudications(
    author_manifest: CaseDefinitionManifest,
    verification_manifest: CaseDefinitionManifest,
    adjudication_manifest: CaseDefinitionManifest,
    disputes: CaseDefinitionDisputeManifest,
    definitions: Sequence[CaseDefinition],
    verifications: Sequence[CaseDefinitionVerification],
    adjudications: Sequence[CaseDefinitionVerification],
) -> CaseDefinitionAdjudicationReceipt

def validate_case_definition(
    definition: CaseDefinition,
    accepted_annotations: Mapping[str, ReviewAnnotation],
    source_relations: ExplicitRelationGraph,
    observation_relations: Sequence[ObservationRelationEdge],
) -> tuple[ValidationFinding, ...]

def verify_required_claims(
    definitions: Sequence[CaseDefinition],
    requirements: Sequence[RequiredClaim],
) -> RequiredClaimReport
```

- [ ] **Step 1: Write failing no-inference and no-limit tests**

Assert unrelated sources cannot be grouped, every grouping edge has relation evidence, a source can yield multiple independent cases, 31 cases remain 31 through batching, numeric/date tokens occur in cited excerpts, and result-only definitions use the exact missing-process text. Add a single explicit-relation component containing 1,000 bounded observations: author, verifier, and adjudicator artifacts must each stay at most 96,000 canonical visible bytes while observation IDs form an exact no-gap/no-duplicate union and every internal/boundary relation ID remains resolvable. No definition may cross an authoring-part boundary; boundary edges survive as related-case evidence. Reordering or dropping one authoring part/observation/edge/definition ID, an oversized artifact/definition, a verification manifest whose definition set differs from the authored set, or an adjudication manifest whose definition set differs from the exact disputed subset must fail. Add singleton-overflow fixtures in which author input plus definition fits the author limit but definition+accepted observations+verification metadata or disputed verification+correction would exceed 96,000 bytes: the workflow must split at an observation boundary and repeat affected authoring/review; if one observation alone cannot fit, it must subdivide the underlying structural source group at an exact range boundary and rerun that affected primary→verification→definition chain. It may never truncate or exempt the singleton. Test both no-dispute and mixed accepted/disputed paths: the no-dispute adjudication manifest and receipt have canonical empty disputed/adjudicated sets, while the final accepted-plus-adjudicated effective definition/observation/relation union exactly equals the author output. Add same-execution fixtures with two directly conflicting values and a shared explicit run ID: both `ConflictValue` records, the same-execution excerpts/relation, and exact raw labels must survive in a `SourceConflict`; selecting one, averaging/recalculating, dropping a side, using only time/command/environment/value similarity, omitting the stable run evidence, or marking different executions as a conflict must fail.

Repeat the authority/stage truth table at the definition boundary so a forged review output cannot bypass it. `build-case-definition-manifest`, `validate-case-definition`, required-claim verification, catalog construction, and content verification must resolve every observation/source ID through `ClassifiedObservation`: reject structural-reference sources, proposed/planned/target/expected-only case triggers, uncorroborated AI/legacy-derived phrases, trace command inputs claimed as results, and any phrase beyond a truncated trace observation. Required documented metrics may be satisfied only by allowed primary/personal/exact trace-observation evidence, never the renewal guide's examples or legacy derived judgments.

Add a relation-granularity fixture where one paragraph in a long document explicitly links one PR but another observation in that document contains an unrelated metric. A source-level `ExplicitRelation` alone must not connect arbitrary observations. Build an `ObservationRelationEdge` only when the owner relation locator/hash overlaps the from-observation's accepted excerpt and a verified target-side locator/hash identifies the applicable to-observation; derive `edge_id` from both observation IDs plus the immutable source relation ID/evidence hashes. Missing, ambiguous, container-wide, non-overlapping, or many-to-any applicability stays as source-ledger provenance and cannot join cases. Case components use only validated observation edges, never target co-membership.

```python
def test_unsplit_result_only_uses_exact_non_inference_text():
    case = build_case_catalog(result_only_definition)[0]
    assert case.problem.text == "원문에 문제·해결 과정 미기록"
    assert case.solution.text == "원문에 문제·해결 과정 미기록"
    assert isinstance(case.diagram, FlowchartDiagramSpec)
    assert [(e.from_node, e.to_node) for e in case.diagram.edges] == [("source", "recorded_result")]

def test_unrelated_sources_cannot_be_grouped():
    with pytest.raises(CatalogError, match="explicit relation"):
        validate_case_definition(
            joined_without_relation, annotations, source_graph, observation_edges
        )
```

- [ ] **Step 2: Encode the mandatory documented-fact regression floor**

`required_claims.json` is a verification list, not a source of facts. Each entry requires exact token groups, allowed statuses, measurement-condition tokens where recorded, and a matching document source locator. Include at minimum:

- `97 → 7,347 RPS` and documented `76배`;
- `p99 4,100ms → 36ms`, documented `99% 감소`, and `59.7% → 0%` error rate;
- `223 → 97`, `97 → 555`, `555 → 674`, `674 → 965`, stateless `325`, and auto-warmup `287 → 940 RPS` as distinct records;
- 2026-03-19 `7,347 RPS / p99 36ms / errors 65`;
- post-fix empty-DB c200 `7,347`, c500 `10,994 RPS`, errors 0;
- 2026-03-24 real-data `200K–300K rows`, `wrk -t4 -c200 -d120s`, `7,347 RPS`, `p99 36ms`, errors 0;
- PGMQ `3.3 → 90 tasks/s`, `25,466 → 864ms`, and `98.4% → 0%` 503;
- Like-path DB QPS `2,500–3,500/s → <200` and Hikari `75–125% → 10–15%`;
- heap `120MB → ~64KB`, external API `102 → 150 files/s`, calculator `186 → 362 users/s`;
- `599,800 IGN`, `~402 files/s`, and StackOverflow 0;
- cleanup missing `~200GB` and one recovery `~14GB`;
- the 82h run's documented peak `497 users/s`, `32,441 items/s`, phase rates, and sustained `210–250 users/s`;
- the approximately 80h lifetime average `136.57 users/s` exactly as documented;
- the document's `약 71h` wording without substituting `68h27m`;
- MinIO temp-file correction, claim-check, idempotency, ETL ownership, security response, and upstream-merged open-source work.

Also encode forbidden conflations: the three 7,347-RPS runs cannot be merged; 06-25's cause cannot be copied from 06-26; targets/expected values cannot satisfy measured requirements; ADR-730 is a correctness recovery, the 80h writer diagnosis has no same-condition after, and the 82h result belongs only to its documented three-service configuration.

- [ ] **Step 3: Implement relation-constrained definition manifests**

First derive and validate locator-specific `ObservationRelationEdge` rows from accepted observations and the immutable source relation ledger. The owner evidence locator/hash must overlap an accepted owner excerpt, the target evidence locator/hash must identify one applicable target observation, and both observation IDs are included in the deterministic edge ID. Target co-membership, a relation elsewhere in the same long source, or source-level reachability without excerpt applicability cannot create an edge. Build the observation-edge graph, then deterministically partition each connected component at observation boundaries into `CaseAuthoringPart` values whose complete canonical author-visible artifact is at most 96,000 bytes and contains at most 12 already bounded observations. A component of any size therefore creates as many parts as necessary. Record every internal edge and every cut boundary edge by deterministic edge ID; an author may group observations only inside one part, while boundary edges remain related-case evidence and never disappear. Every case observation occurs in exactly one authoring part and exactly one final definition. A source with multiple independent observations may therefore create multiple cases. `record-only` observations do not create cases. The author-phase manifest has an empty `expected_definition_ids` because definitions do not exist yet; after author outputs validate, their deterministic IDs become the exact expected definition set of the separately hashed verification-phase manifest. Author/verifier/adjudicator batching limits are context-safety checkpoints, never case or component limits.

Register `build-case-definition-manifest` and `verify-case-definitions` in `cli.py`, then generate the immutable queue before authoring:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book build-observation-relations \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --output output/research/observation_relations.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book build-case-definition-manifest \
  --sources output/research/source_records.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --observation-relations output/research/observation_relations.jsonl \
  --output output/research/case_definition_manifest.json \
  --input-dir output/research/case_definition_batches/author/inputs \
  --max-observations 12 \
  --max-author-bytes 96000
```

- [ ] **Step 4: Author every case definition and independently verify it**

For each bounded author artifact, a fresh case author records a unique author run ID and uses only its accepted excerpts to write canonical title, problem, solution, results, conditions, normalized status, verbatim raw status label, any explicitly same-execution `SourceConflict`, structured `DiagramSpec`, and every same-logical-unit continuation group ID. A definition cannot cite an observation outside its authoring part and its canonical serialized size is at most 64,000 bytes. Before accepting it, serialize the exact projected verification artifact and worst-case bounded adjudication envelope using its complete accepted observations/metadata; both must fit 96,000 bytes. If independent observations/results or either projected phase payload would exceed a bound, partition into separate definitions at observation boundaries. If a later actual verification/adjudication singleton still exceeds 96,000 bytes, invalidate that definition and repeat authoring from the smaller observation partition; if one observation alone is the cause, subdivide its structural source group at an exact syntax/field/range boundary and rerun the affected primary→verification→definition chain. No phase has an oversized exception or truncation path. Missing-field text uses whole-source `원문에 ... 미기록` only when no artificial continuation group exists; otherwise it says the field is missing from this bounded observation and lists continuation IDs without claiming their semantics. A conflict preserves every differing value separately; it never chooses, averages, recalculates, or resolves them without a cited later correction. The canonical title is a lossless one-sentence projection of the case: it retains the source-backed domain, problem, solution, every result, required measurement conditions, status tokens, continuation boundary marker when needed, and an unresolved-conflict marker when applicable.

After all author parts pass exact observation/relation coverage, run `make-case-definition-phase-batches --phase verification`. It includes each definition, its bounded accepted inputs, and author hash in freshly packed verifier-visible artifacts of at most 96,000 bytes; a verifier never receives an unmetered whole component. Its expected definition IDs are exactly the authored definition set, and its observation/relation union is exactly the author manifest's union although batch boundaries may differ. A different reviewer records a distinct run ID, the definition's canonical SHA-256, complete excerpt-hash/read evidence, verifies every factual phrase, conflict side, diagram node/edge, observation assignment, and boundary relation, and writes the verification file. `validate-case-definition-verifications` then hash-checks the complete verification set and always writes the canonical `disputes.json`, including an empty set when all definitions are accepted.

Materialize the adjudication phase only from that exact dispute manifest. Its expected definition IDs, observations, relations, and authoring parts are the exact disputed subset, not the full author union; therefore a no-dispute adjudication manifest is canonically empty. For each dispute, a third reviewer with another distinct run ID writes a `CaseDefinitionVerification` with `decision="adjudicated"`, `reviewed_verification_sha256` equal to the disputed verification hash, and a complete bounded `correction_definition` under `case_definition_adjudications/`; primary definitions and verification files are never overwritten. `validate-case-definition-adjudications` always writes a canonical receipt and requires the effective union of accepted verification definitions plus adjudicated corrections to equal the complete authored definition/observation/relation union. An oversized phase artifact, role reuse, hash mismatch, raw-label rewrite, conflict-side omission, extra/missing disputed ID, or unresolved dispute blocks continuation.

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-case-definition-phase-batches \
  --phase verification \
  --author-manifest output/research/case_definition_manifest.json \
  --definitions output/research/case_definitions \
  --max-parts 12 \
  --max-reviewer-bytes 96000 \
  --output output/research/case_definition_batches/verification
python3 tools/run_portfolio_command.py -- uv run portfolio-book case-definition-status \
  --manifest output/research/case_definition_batches/verification/manifest.json \
  --phase verification
```

Dispatch fresh verification reviewers and checkpoint until that status reports `pending_batches=0`. Only then validate the complete verification set and create the exact dispute queue:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-case-definition-verifications \
  --author-manifest output/research/case_definition_manifest.json \
  --verification-manifest output/research/case_definition_batches/verification/manifest.json \
  --definitions output/research/case_definitions \
  --verifications output/research/case_definition_verifications \
  --write-disputes output/research/case_definition_adjudications/disputes.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-case-definition-phase-batches \
  --phase adjudication \
  --author-manifest output/research/case_definition_manifest.json \
  --definitions output/research/case_definitions \
  --verifications output/research/case_definition_verifications \
  --disputes output/research/case_definition_adjudications/disputes.json \
  --max-parts 12 \
  --max-reviewer-bytes 96000 \
  --output output/research/case_definition_batches/adjudication
python3 tools/run_portfolio_command.py -- uv run portfolio-book case-definition-status \
  --manifest output/research/case_definition_batches/adjudication/manifest.json \
  --phase adjudication
```

With no disputes, `disputes.json` and the adjudication manifest are canonical and empty and status immediately reports zero; no input or adjudication JSONL files are fabricated. Otherwise dispatch fresh adjudicators and checkpoint until adjudication status reports `pending_batches=0`. Only then validate the exact adjudication hash chain and materialize one canonical receipt in both paths:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-case-definition-adjudications \
  --author-manifest output/research/case_definition_manifest.json \
  --verification-manifest output/research/case_definition_batches/verification/manifest.json \
  --adjudication-manifest output/research/case_definition_batches/adjudication/manifest.json \
  --disputes output/research/case_definition_adjudications/disputes.json \
  --definitions output/research/case_definitions \
  --verifications output/research/case_definition_verifications \
  --adjudications output/research/case_definition_adjudications \
  --write-receipt output/research/case_definition_adjudications/receipt.json
```

Continue until:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-case-definitions \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --manifest output/research/case_definition_manifest.json \
  --verification-manifest output/research/case_definition_batches/verification/manifest.json \
  --adjudication-manifest output/research/case_definition_batches/adjudication/manifest.json \
  --disputes output/research/case_definition_adjudications/disputes.json \
  --adjudication-receipt output/research/case_definition_adjudications/receipt.json \
  --observation-relations output/research/observation_relations.jsonl \
  --definitions output/research/case_definitions \
  --verifications output/research/case_definition_verifications \
  --adjudications output/research/case_definition_adjudications \
  --decisions output/research/classification_decisions.jsonl \
  --requirements tools/portfolio_builder/required_claims.json
```

Expected: `missing_authoring_parts=0`, `oversized_author_artifacts=0`, `oversized_definitions=0`, `oversized_verifier_artifacts=0`, `oversized_adjudicator_artifacts=0`, `missing_observations=0`, `duplicate_observations=0`, `missing_relation_edges=0`, `missing_definitions=0`, `unverified_definitions=0`, `reviewer_role_reuse=0`, `stale_hash_chain=0`, `unrelated_sources=0`, `uncited_phrases=0`, `raw_status_label_mismatch=0`, `status_promotions=0`, `source_conflict_side_loss=0`, `source_conflict_inventions=0`, `required_claim_missing=0`, and `forbidden_conflations=0`.

- [ ] **Step 5: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- \
  uv run pytest tests/test_case_catalog.py tests/test_required_claims.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/case_catalog.py \
  docs/Portfolio_Book/tools/portfolio_builder/required_claims.json \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_case_catalog.py \
  docs/Portfolio_Book/tests/test_required_claims.py \
  docs/Portfolio_Book/output/research/observation_relations.jsonl \
  docs/Portfolio_Book/output/research/case_definition_manifest.json \
  docs/Portfolio_Book/output/research/case_definition_batches \
  docs/Portfolio_Book/output/research/case_definitions \
  docs/Portfolio_Book/output/research/case_definition_verifications \
  docs/Portfolio_Book/output/research/case_definition_adjudications
git diff --cached --check
git commit -m "data(portfolio): define every evidenced portfolio case"
```

---

### Task 6: Build the canonical case catalog and source map

**Files:**

- Modify: `docs/Portfolio_Book/tools/portfolio_builder/case_catalog.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/fixtures/content/`
- Modify: `docs/Portfolio_Book/tests/test_case_catalog.py`
- Generate: `docs/Portfolio_Book/output/research/case_catalog.jsonl`
- Generate: `docs/Portfolio_Book/output/research/case_source_map.csv`

**Interfaces:**

```python
def build_case_catalog(
    definitions: Sequence[CaseDefinition],
    verifications: Sequence[CaseDefinitionVerification],
    adjudications: Sequence[CaseDefinitionVerification],
) -> tuple[CaseRecord, ...]

def write_case_source_map(
    cases: Sequence[CaseRecord],
    decisions: Sequence[ReviewAnnotation],
    destination: Path,
) -> None
```

- [ ] **Step 1: Add failing stable-ID, exact-title, and complete-map tests**

Use an 11-case fixture to prove no 3–5 case cap exists. Assert case IDs and order are stable, every case observation is represented exactly once, every case source is relation-valid, every source/document target remains present, every accepted observation maps to exactly one case or one explicit record-only reason, mixed case/record-only observations under one target both survive, and no accepted observation disappears.

- [ ] **Step 2: Implement catalog construction without prose generation**

Canonical title is defined once in `CaseRecord`. Register `build-case-catalog` and `verify-case-catalog` in `cli.py`. Convert only independently verified definitions, applying a hash-matched adjudicated correction as an overlay without overwriting the primary definition. Sort cases by `(project_id, ordinal, case_id)` and sort source IDs by UTF-8 bytes. Do not collapse cases with equal titles or measurements.

- [ ] **Step 3: Build and verify the real outputs**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book build-case-catalog \
  --definitions output/research/case_definitions \
  --verifications output/research/case_definition_verifications \
  --adjudications output/research/case_definition_adjudications \
  --observation-relations output/research/observation_relations.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --catalog output/research/case_catalog.jsonl \
  --source-map output/research/case_source_map.csv
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-case-catalog \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --observation-relations output/research/observation_relations.jsonl \
  --catalog output/research/case_catalog.jsonl \
  --source-map output/research/case_source_map.csv
```

Expected: target union exact, `unassigned=0`, `missing_case_observations=0`, `duplicate_case_ids=0`, and required documented claims all found.

- [ ] **Step 4: Commit**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add docs/Portfolio_Book/tools/portfolio_builder/case_catalog.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/fixtures/content \
  docs/Portfolio_Book/tests/test_case_catalog.py \
  docs/Portfolio_Book/output/research/case_catalog.jsonl \
  docs/Portfolio_Book/output/research/case_source_map.csv
git diff --cached --check
git commit -m "data(portfolio): build exhaustive case catalog"
```

---

### Task 7: Generate the exhaustive resume and evidence book

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/content_writer.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/content_verifier.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/test_content_writer.py`
- Create: `docs/Portfolio_Book/tests/test_content_verifier.py`
- Generate: `docs/Portfolio_Book/output/research/profile_facts.jsonl`
- Generate: `docs/Portfolio_Book/output/research/profile_fact_verifications.jsonl`
- Generate: `docs/Portfolio_Book/output/research/project_catalog.jsonl`
- Generate: `docs/Portfolio_Book/output/research/project_fact_verifications.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_adjudications.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_candidates.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_review_batches/author/manifest.json`
- Generate: `docs/Portfolio_Book/output/research/fact_review_batches/author/inputs/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_review_batches/verification/manifest.json`
- Generate: `docs/Portfolio_Book/output/research/fact_review_batches/verification/inputs/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_review_batches/adjudication/manifest.json`
- Generate when needed: `docs/Portfolio_Book/output/research/fact_review_batches/adjudication/inputs/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_author_parts/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_verification_parts/*.jsonl`
- Generate when needed: `docs/Portfolio_Book/output/research/fact_adjudication_parts/*.jsonl`
- Generate: `docs/Portfolio_Book/output/research/fact_disputes.json`
- Generate: `docs/Portfolio_Book/output/research/fact_adjudication_receipt.json`
- Generate: `docs/Portfolio_Book/output/research/source_conflicts.jsonl`
- Replace: `docs/Portfolio_Book/output/final/이력서_완성본.md`
- Replace: `docs/Portfolio_Book/output/final/전수증거장부.md`

**Interfaces:**

```python
def render_resume(
    snapshot: SnapshotManifest,
    profile_facts: Sequence[ProfileRecord],
    profile_verifications: Sequence[FactVerification],
    projects: Sequence[ProjectRecord],
    project_verifications: Sequence[FactVerification],
    fact_adjudications: Sequence[FactVerification],
    fact_adjudication_receipt: Path,
    cases: Sequence[CaseRecord],
    source_conflicts: Sequence[SourceConflict],
) -> str

def render_evidence_book(
    snapshot: SnapshotManifest,
    sources: Sequence[SourceRecord],
    claims: Sequence[DocumentClaim],
    decisions: Sequence[ReviewAnnotation],
    case_map: Sequence[CaseSourceLink],
    source_conflicts: Sequence[SourceConflict],
) -> str

def collect_source_conflicts(
    cases: Sequence[CaseRecord],
    profile_facts: Sequence[ProfileRecord],
    profile_verifications: Sequence[FactVerification],
    projects: Sequence[ProjectRecord],
    project_verifications: Sequence[FactVerification],
    fact_adjudications: Sequence[FactVerification],
    fact_adjudication_receipt: Path,
) -> tuple[SourceConflict, ...]

def verify_factual_sentences(
    document: str,
    facts: FactRegistry,
) -> tuple[ValidationFinding, ...]

def build_fact_review_manifest(
    sources: Sequence[SourceRecord],
    claims: Sequence[DocumentClaim],
    decisions: Sequence[ReviewAnnotation],
    cases: Sequence[CaseRecord],
    max_candidates: int = 12,
    max_reviewer_bytes: int = 96_000,
) -> FactReviewManifest

def build_fact_phase_manifest(
    author_manifest: FactReviewManifest,
    phase: Literal["verification", "adjudication"],
    prior_artifacts: Sequence[Path],
    max_candidates: int = 12,
    max_reviewer_bytes: int = 96_000,
) -> FactReviewManifest
```

- [ ] **Step 1: Write failing all-case/all-record, lossless-title, and citation tests**

Assert the resume emits every catalog case in canonical order, uses each canonical title byte-for-byte, and emits no `[:N]`/top-case behavior. For each case, require the title's cited excerpts/token groups to cover its domain, problem, solution, every result, required measurement condition, status, and unresolved-conflict marker; deleting any one result, condition, or conflict side must fail. Assert the evidence book contains every source ID and every document-claim ID exactly once in its primary index, plus stored locator/hash, availability, parse status, redactions, every observation disposition/reason/case ID, and every `SourceConflict` side.

Every factual Markdown block is emitted from a `FactSentence` and carries a visible `[근거: <source IDs>]` plus a machine-readable fact marker. Tests remove one citation from motivation, profile, project metadata, career, education, certificate, open-source, and case title blocks in turn and require failure.

Add 1,000 fact-candidate fixtures spanning every profile/project field. Author, verification, and adjudication manifests must partition the exact candidate/fact sets into complete canonical reviewer-visible artifacts at most 96,000 bytes with no count cap, truncation, or unmetered source body. Every author candidate occurs once; verification expected fact IDs equal all authored facts; adjudication expected fact IDs equal exactly the disputed subset; accepted plus adjudicated facts form the exact final union. A singleton overflow splits at a candidate/excerpt boundary and reruns the affected author→verification chain; if one candidate is still too large, split its underlying accepted observation at an exact structural range and rerun the affected primary review before fact authoring. Reject structural-reference citations, proposed/planned/target/expected-only profile achievements, uncorroborated AI/legacy-derived sole authority, trace command inputs promoted to results, stale fact hashes, role reuse, or missing conflict sides.

- [ ] **Step 2: Author source-backed profile and project records**

First derive `FactCandidate` rows mechanically from each effective observation's controlled fact keys and from verified cases' explicit deterministic motivation/introduction projection rule, preserving source/observation IDs, exact excerpts, scope/authority/stage, continuation boundaries, and destination/field key. Every effective observation must have either its complete fact-key set or one verified `no_fact_candidate_reason`, so builder eligibility is an exact set partition rather than semantic rescanning. Renewal-guide structural claims and record-only/unverified authority are never candidates. This queue is exhaustive for every eligible field-bearing observation; no author searches an unbounded ledger ad hoc. Pack complete candidate bytes plus metadata into author batches of at most 12 candidates and 96,000 bytes, splitting at exact candidate/excerpt boundaries as needed.

Review the bounded candidate batches and create:

- a generic backend motivation of exactly 2–3 factual lines, with no target company or job-posting claim;
- a measurement-led self-introduction;
- name/contact/new-graduate/career/education/GitHub/blog/portfolio facts only when cited;
- project title, monthly period, versioned technologies, participant counts by role, and one-line overview with field-level source IDs;
- open-source contribution, career, education, and certificate records without duplicating the header unnecessarily.

If a requested profile field is absent from evidence, omit it or record it in the review-needs ledger; never guess it.

Each fact author records a unique run ID and preserves both normalized status and byte-identical raw status label. Every `FactSentence` stores its exact nonempty `candidate_ids` and the exact union of those candidates' `accepted_observation_ids`; one candidate may feed multiple intentionally distinct destination fields only through separate manifest candidate IDs, never heuristic reuse. Every fresh author writes only its manifest-fixed `fact_author_parts/<batch-id>.jsonl`; no agent edits shared aggregates. If explicit relations prove two different values describe the same execution/field with no later correction, the author embeds a `SourceConflict` containing both; different runs stay separate facts. Validate the exact batch/input/candidate-to-fact union and deterministically merge part files into `profile_facts.jsonl` and `project_catalog.jsonl`. After exact candidate coverage passes, build a fresh verification manifest containing every authored fact, its complete accepted candidate/excerpts, and author hash within the same 96,000-byte cap. Each fresh verifier writes only `fact_verification_parts/<batch-id>.jsonl`, records each fact's canonical SHA-256, complete excerpt-hash/read evidence, every conflict side, and author run ID; validate/merge exact batch/candidate/fact unions into the two canonical verification JSONLs. Validate the full verification set and always write a typed canonical `FactDisputeManifest`; build the adjudication manifest from exactly that candidate/fact subset, with an empty canonical manifest when none exist. A third distinct run writes only `fact_adjudication_parts/<batch-id>.jsonl` and adjudicates each dispute through a complete bounded `correction_fact` with the same disputed candidate/observation identity and `reviewed_verification_sha256` pointing to the disputed verification; validate/merge these into `fact_adjudications.jsonl`, while primary facts remain untouched. `fact-review-status` derives progress only from validated per-batch file presence and hashes. Always write a typed `FactAdjudicationReceipt` proving the exact authored, accepted, adjudicated, and effective candidate/fact ID sets and hashes; accepted plus adjudicated candidate/fact unions must equal the author manifest. Only hash-chained accepted or adjudicated `FactSentence` values with exactly one effective verification may reach the writers; missing, duplicate, oversized, role-reused, disputed, stale-hash, raw-label-rewritten, authority/stage-invalid, conflict-side-dropped, conflict-invented, or uncited fields block generation.

- [ ] **Step 3: Execute the bounded fact author→verification→adjudication chain**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book build-fact-review-manifest \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --cases output/research/case_catalog.jsonl \
  --write-candidates output/research/fact_candidates.jsonl \
  --max-candidates 12 \
  --max-reviewer-bytes 96000 \
  --output output/research/fact_review_batches/author
python3 tools/run_portfolio_command.py -- uv run portfolio-book fact-review-status \
  --manifest output/research/fact_review_batches/author/manifest.json \
  --phase author
```

Dispatch fresh authors until `pending_batches=0`, then require exact candidate coverage and build verification batches:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-fact-author-parts \
  --manifest output/research/fact_review_batches/author/manifest.json \
  --parts output/research/fact_author_parts
python3 tools/run_portfolio_command.py -- uv run portfolio-book merge-fact-author-parts \
  --manifest output/research/fact_review_batches/author/manifest.json \
  --parts output/research/fact_author_parts \
  --profile output/research/profile_facts.jsonl \
  --projects output/research/project_catalog.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-fact-authors \
  --manifest output/research/fact_review_batches/author/manifest.json \
  --profile output/research/profile_facts.jsonl \
  --projects output/research/project_catalog.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-fact-phase-batches \
  --phase verification \
  --author-manifest output/research/fact_review_batches/author/manifest.json \
  --profile output/research/profile_facts.jsonl \
  --projects output/research/project_catalog.jsonl \
  --max-candidates 12 \
  --max-reviewer-bytes 96000 \
  --output output/research/fact_review_batches/verification
python3 tools/run_portfolio_command.py -- uv run portfolio-book fact-review-status \
  --manifest output/research/fact_review_batches/verification/manifest.json \
  --phase verification
```

Dispatch fresh verifiers until zero pending, then validate them, write the canonical dispute set, and create the exact disputed-subset adjudication queue:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-fact-verification-parts \
  --manifest output/research/fact_review_batches/verification/manifest.json \
  --author-parts output/research/fact_author_parts \
  --parts output/research/fact_verification_parts
python3 tools/run_portfolio_command.py -- uv run portfolio-book merge-fact-verification-parts \
  --manifest output/research/fact_review_batches/verification/manifest.json \
  --parts output/research/fact_verification_parts \
  --profile-output output/research/profile_fact_verifications.jsonl \
  --project-output output/research/project_fact_verifications.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-fact-verifications \
  --author-manifest output/research/fact_review_batches/author/manifest.json \
  --verification-manifest output/research/fact_review_batches/verification/manifest.json \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --write-disputes output/research/fact_disputes.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-fact-phase-batches \
  --phase adjudication \
  --author-manifest output/research/fact_review_batches/author/manifest.json \
  --verification-manifest output/research/fact_review_batches/verification/manifest.json \
  --disputes output/research/fact_disputes.json \
  --max-candidates 12 \
  --max-reviewer-bytes 96000 \
  --output output/research/fact_review_batches/adjudication
python3 tools/run_portfolio_command.py -- uv run portfolio-book fact-review-status \
  --manifest output/research/fact_review_batches/adjudication/manifest.json \
  --phase adjudication
```

An empty dispute set produces an empty adjudication manifest and zero pending immediately. In that path, `validate-fact-adjudication-parts` requires the parts directory to be absent or empty, and `merge-fact-adjudication-parts` still writes the canonical empty `fact_adjudications.jsonl`; neither command treats the absent optional directory as an error. Otherwise dispatch third-role adjudicators until zero pending. Then always create the effective-union receipt:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-fact-adjudication-parts \
  --manifest output/research/fact_review_batches/adjudication/manifest.json \
  --disputes output/research/fact_disputes.json \
  --parts output/research/fact_adjudication_parts
python3 tools/run_portfolio_command.py -- uv run portfolio-book merge-fact-adjudication-parts \
  --manifest output/research/fact_review_batches/adjudication/manifest.json \
  --disputes output/research/fact_disputes.json \
  --parts output/research/fact_adjudication_parts \
  --output output/research/fact_adjudications.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate-fact-adjudications \
  --author-manifest output/research/fact_review_batches/author/manifest.json \
  --verification-manifest output/research/fact_review_batches/verification/manifest.json \
  --adjudication-manifest output/research/fact_review_batches/adjudication/manifest.json \
  --disputes output/research/fact_disputes.json \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --adjudications output/research/fact_adjudications.jsonl \
  --write-receipt output/research/fact_adjudication_receipt.json
```

- [ ] **Step 4: Implement the resume writer and CLI commands**

For every case, render exactly one sentence/title in the approved form, preserving `not-recorded` text and every unresolved `source conflict` where applicable. Do not abbreviate source ID lists into ranges. Wrap every resume case with `render_case_markers(case.case_id, "### " + rendered_case_title(case), "")`; the portfolio uses the same `rendered_case_title(case)`. `collect-source-conflicts` deduplicates only byte-identical conflict objects with the same deterministic ID, rejects ID/hash collisions, and writes the complete registry. The renderer iterates the entire catalog and asserts the emitted case-ID/conflict-ID sets equal the input sets before writing atomically. Register `collect-source-conflicts`, `render-resume`, `render-evidence-book`, and `verify-resume-and-ledger` in `cli.py` in this task.

- [ ] **Step 5: Implement the human-readable evidence book**

Write snapshot boundary and coverage summary, then every source record, document claim, classified observation, and source conflict. Store full patch bodies only in the split archives; the book links source ID, locator, safe excerpt/summary, raw/stored hashes, observation-level disposition/reason, status, case links, conflict sides, and archive member. Include confirmed-unavailable and malformed records with their exact limitations.

- [ ] **Step 6: Generate, verify, and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- \
  uv run pytest tests/test_content_writer.py tests/test_content_verifier.py -q
python3 tools/run_portfolio_command.py -- uv run portfolio-book collect-source-conflicts \
  --catalog output/research/case_catalog.jsonl \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --fact-adjudications output/research/fact_adjudications.jsonl \
  --fact-receipt output/research/fact_adjudication_receipt.json \
  --output output/research/source_conflicts.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book render-resume \
  --snapshot output/research/snapshot_manifest.json \
  --catalog output/research/case_catalog.jsonl \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --fact-adjudications output/research/fact_adjudications.jsonl \
  --fact-receipt output/research/fact_adjudication_receipt.json \
  --source-conflicts output/research/source_conflicts.jsonl \
  --output output/final/이력서_완성본.md
python3 tools/run_portfolio_command.py -- uv run portfolio-book render-evidence-book \
  --snapshot output/research/snapshot_manifest.json \
  --research output/research \
  --source-conflicts output/research/source_conflicts.jsonl \
  --output output/final/전수증거장부.md
python3 tools/run_portfolio_command.py -- \
  uv run portfolio-book verify-resume-and-ledger --root .
```

Expected: all cases, evidence targets, observations, and source-conflict sides present; `uncited_factual_sentences=0`, `source_conflict_side_loss=0`, `source_conflict_inventions=0`, and `secret_findings=0`.

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add docs/Portfolio_Book/tools/portfolio_builder/content_writer.py \
  docs/Portfolio_Book/tools/portfolio_builder/content_verifier.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_content_writer.py \
  docs/Portfolio_Book/tests/test_content_verifier.py \
  docs/Portfolio_Book/output/research/profile_facts.jsonl \
  docs/Portfolio_Book/output/research/profile_fact_verifications.jsonl \
  docs/Portfolio_Book/output/research/project_catalog.jsonl \
  docs/Portfolio_Book/output/research/project_fact_verifications.jsonl \
  docs/Portfolio_Book/output/research/fact_candidates.jsonl \
  docs/Portfolio_Book/output/research/fact_review_batches \
  docs/Portfolio_Book/output/research/fact_author_parts \
  docs/Portfolio_Book/output/research/fact_verification_parts \
  docs/Portfolio_Book/output/research/fact_disputes.json \
  docs/Portfolio_Book/output/research/fact_adjudications.jsonl \
  docs/Portfolio_Book/output/research/fact_adjudication_receipt.json \
  docs/Portfolio_Book/output/research/source_conflicts.jsonl \
  docs/Portfolio_Book/output/final/이력서_완성본.md \
  docs/Portfolio_Book/output/final/전수증거장부.md
if test -d docs/Portfolio_Book/output/research/fact_adjudication_parts; then
  git add -- docs/Portfolio_Book/output/research/fact_adjudication_parts
fi
git diff --cached --check
git commit -m "docs(portfolio): generate exhaustive resume and ledger"
```

---

### Task 8: Generate the exhaustive portfolio Markdown and cross-document gate

**Files:**

- Modify: `docs/Portfolio_Book/tools/portfolio_builder/content_writer.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/content_verifier.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/coverage.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Modify: `docs/Portfolio_Book/tests/test_content_writer.py`
- Modify: `docs/Portfolio_Book/tests/test_content_verifier.py`
- Generate: `docs/Portfolio_Book/output/research/classified_source_records.jsonl`
- Generate: `docs/Portfolio_Book/output/research/release_coverage_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/release_coverage_manifest.md`
- Replace: `docs/Portfolio_Book/output/final/포트폴리오_완성본.md`

**Interfaces:**

```python
def render_portfolio(
    snapshot: SnapshotManifest,
    projects: Sequence[ProjectRecord],
    project_verifications: Sequence[FactVerification],
    fact_adjudications: Sequence[FactVerification],
    fact_adjudication_receipt: Path,
    cases: Sequence[CaseRecord],
    record_only_links: Sequence[CaseSourceLink],
    classified_sources: Sequence[ClassifiedSourceRecord],
    source_conflicts: Sequence[SourceConflict],
    observation_relations: Sequence[ObservationRelationEdge],
    capture_coverage: Path,
) -> str

def verify_cross_document(
    resume: Path,
    portfolio: Path,
    evidence_book: Path,
    catalog: Path,
    source_map: Path,
    source_conflicts: Path,
    observation_relations: Path,
    profile_facts: Path,
    profile_fact_verifications: Path,
    project_catalog: Path,
    project_fact_verifications: Path,
    fact_adjudications: Path,
    fact_adjudication_receipt: Path,
) -> ContentVerificationReport

def build_classified_source_records(
    snapshot: SnapshotManifest,
    sources: Sequence[SourceRecord],
    claims: Sequence[DocumentClaim],
    decisions: Sequence[ReviewAnnotation],
    source_map: Sequence[CaseSourceLink],
) -> tuple[ClassifiedSourceRecord, ...]

def build_release_coverage(
    capture_coverage: Path,
    classification_decisions: Path,
    classified: Sequence[ClassifiedSourceRecord],
    cases: Sequence[CaseRecord],
    source_map: Sequence[CaseSourceLink],
    source_conflicts: Path,
    observation_relations: Path,
    profile_facts: Path,
    profile_fact_verifications: Path,
    project_catalog: Path,
    project_fact_verifications: Path,
    fact_adjudications: Path,
    fact_adjudication_receipt: Path,
    documents: Sequence[Path],
) -> ReleaseCoverageManifest
```

- [ ] **Step 1: Write failing section, equality, and status tests**

Each case block must contain, in order: the byte-identical canonical title, Mermaid image path, 문제, 해결, 결과와 측정 조건, 관련 commit과 parent별 diff, PR과 issue, AI trace와 문서 근거, and only source-required target/expected/estimated/unverified/failed/rolled-back boundaries. Assert all case IDs occur once in both documents and every record-only observation occurs once in the source index under its target. A mixed target fixture containing one case observation and one record-only observation must render both. Remove or normalize one raw source status label and require failure even when the normalized enum remains unchanged. The complete portfolio Markdown image-reference set must equal the catalog-derived Mermaid SVG set; any photo, icon, screenshot, remote image, data URI, or extra local image fails.

Inject one disputed project fact with an adjudicated correction and prove resume, portfolio, source-conflict registry, cross-document verification, and release coverage all resolve the same single effective corrected value from `fact_adjudications.jsonl` plus its receipt; rendering the immutable disputed primary value or applying the overlay in only one document fails.

```markdown
<!-- CASE:<case-id>:START -->
## {rendered_case_title(case)}

![{case.case_id}](../diagrams/rendered/{case.case_id}.svg)

### 문제
{render_evidence_text(case.problem)}
### 해결
{render_evidence_text(case.solution)}
### 결과와 측정 조건
{render_results_and_conditions(case)}
### 관련 commit과 parent별 diff
{render_sources(case, source_type="git")}
### PR과 issue
{render_sources(case, source_type="github")}
### AI trace와 문서 근거
{render_sources(case, source_type="ai-or-document")}
<!-- CASE:<case-id>:END -->
```

- [ ] **Step 2: Implement full-catalog portfolio rendering**

Build the joined classified overlay without mutating capture inputs, preserving every effective review observation as its own `ClassifiedObservation`. Resolve project facts through the same verified accepted/adjudicated overlay and receipt used by the resume; never overwrite primaries or render a disputed stale value. Then render a cover, all-result index (not a curated highlight list), project TOCs, every case block, every record-only observation index grouped under its target, every applicable source-conflict side, and a release-coverage section/link. Image paths are derived from `case_id`; the global image-reference set must contain exactly those Mermaid SVG paths and nothing else. This step checks the `DiagramSpec` contract but the rendering plan creates and compiles `.mmd`/SVG files. The immutable capture coverage and classified overlay are distinct inputs; an `unreviewed` capture record is never presented as final classification.

- [ ] **Step 3: Implement the content/release coverage gate**

Add `build-classified-ledger`, `build-release-coverage`, and `verify-content`. The release manifest hash-links immutable capture coverage, decisions, catalog, source map, every final Markdown document, and their required unit sets. Validate:

- source/document target sets equal final classification target sets;
- the effective accepted/adjudicated observation-ID set equals the classified overlay set and the source-map set exactly, with no missing or duplicate observation;
- case and record-only observation sets are disjoint and their union equals every classified observation; each case observation has exactly one case ID and no record-only reason, while each record-only observation has no case ID and exactly one preserved reason;
- resume case set = portfolio case set = catalog case set;
- every corresponding title is byte-for-byte identical;
- every resume title is the verified lossless projection of that case's domain, problem, solution, complete result tuple, required conditions, and status;
- all structured factual sentences have existing source IDs and verified excerpts;
- resume and portfolio project/profile facts resolve through one identical accepted/adjudicated fact union and receipt; disputed primary values never render;
- every source-conflict ID/value/source/excerpt set is byte-identical across its registry, case/fact, resume, portfolio, and evidence-book occurrences; no side is selected, averaged, recalculated, omitted, or invented;
- every case has a structured Mermaid specification;
- the complete portfolio Markdown image-reference set equals the catalog-derived Mermaid SVG set, with no other visual asset;
- all required documented claims and status distinctions survive;
- every normalized status maps to a byte-identical source `raw_status_label`, including estimated/rolled-back/unverified variants;
- no target/expected promotion, forbidden conflation, derived metric, secret, or third-party contact appears.

- [ ] **Step 4: Generate and run the full content gate**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book build-classified-ledger \
  --snapshot output/research/snapshot_manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --source-map output/research/case_source_map.csv \
  --output output/research/classified_source_records.jsonl
python3 tools/run_portfolio_command.py -- uv run portfolio-book render-portfolio \
  --snapshot output/research/snapshot_manifest.json \
  --catalog output/research/case_catalog.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --fact-adjudications output/research/fact_adjudications.jsonl \
  --fact-receipt output/research/fact_adjudication_receipt.json \
  --decisions output/research/classification_decisions.jsonl \
  --classified-sources output/research/classified_source_records.jsonl \
  --source-conflicts output/research/source_conflicts.jsonl \
  --observation-relations output/research/observation_relations.jsonl \
  --capture-coverage output/research/capture_coverage_manifest.json \
  --source-map output/research/case_source_map.csv \
  --output output/final/포트폴리오_완성본.md
python3 tools/run_portfolio_command.py -- uv run portfolio-book build-release-coverage \
  --snapshot output/research/snapshot_manifest.json \
  --capture-coverage output/research/capture_coverage_manifest.json \
  --classified-sources output/research/classified_source_records.jsonl \
  --source-conflicts output/research/source_conflicts.jsonl \
  --observation-relations output/research/observation_relations.jsonl \
  --fact-adjudications output/research/fact_adjudications.jsonl \
  --fact-receipt output/research/fact_adjudication_receipt.json \
  --decisions output/research/classification_decisions.jsonl \
  --cases output/research/case_catalog.jsonl \
  --source-map output/research/case_source_map.csv \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --resume output/final/이력서_완성본.md \
  --portfolio output/final/포트폴리오_완성본.md \
  --evidence-book output/final/전수증거장부.md \
  --json output/research/release_coverage_manifest.json \
  --markdown output/research/release_coverage_manifest.md
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-content \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --classified-sources output/research/classified_source_records.jsonl \
  --source-conflicts output/research/source_conflicts.jsonl \
  --capture-coverage output/research/capture_coverage_manifest.json \
  --release-coverage output/research/release_coverage_manifest.json \
  --cases output/research/case_catalog.jsonl \
  --source-map output/research/case_source_map.csv \
  --observation-relations output/research/observation_relations.jsonl \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --fact-adjudications output/research/fact_adjudications.jsonl \
  --fact-receipt output/research/fact_adjudication_receipt.json \
  --resume output/final/이력서_완성본.md \
  --portfolio output/final/포트폴리오_완성본.md \
  --evidence-book output/final/전수증거장부.md
python3 tools/run_portfolio_command.py -- uv run pytest -q
```

Expected:

```text
missing_targets=0 duplicate_targets=0 unassigned_targets=0
missing_observations=0 duplicate_observations=0 unassigned_observations=0 mixed_target_loss=0
case_set_delta=0 case_title_mismatch=0 uncited_factual_sentences=0
missing_case_sections=0 missing_diagram_specs=0 status_promotions=0
portfolio_non_mermaid_images=0 portfolio_image_set_delta=0
source_conflict_side_loss=0 source_conflict_inventions=0
raw_status_label_mismatch=0 required_claim_missing=0 forbidden_conflations=0
release_coverage_delta=0 secret_findings=0
```

- [ ] **Step 5: Commit**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add docs/Portfolio_Book/tools/portfolio_builder/content_writer.py \
  docs/Portfolio_Book/tools/portfolio_builder/content_verifier.py \
  docs/Portfolio_Book/tools/portfolio_builder/coverage.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_content_writer.py \
  docs/Portfolio_Book/tests/test_content_verifier.py \
  docs/Portfolio_Book/output/research/classified_source_records.jsonl \
  docs/Portfolio_Book/output/research/release_coverage_manifest.json \
  docs/Portfolio_Book/output/research/release_coverage_manifest.md \
  docs/Portfolio_Book/output/final/포트폴리오_완성본.md
git diff --cached --check
git commit -m "docs(portfolio): generate exhaustive portfolio content"
```
