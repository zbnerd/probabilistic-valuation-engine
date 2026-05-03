# RPI Workflow (Research - Plan - Implement)

**핵심 원칙:** 코드를 작성하기 전에 반드시 철저한 분석과 계획을 세운다.

## Phase 1: 조사 (Research)
- 관련 코드, 파일 구조, 데이터를 먼저 탐색 (Glob, Grep, Read)
- 기존 시스템과 의존성 파악
- 발견한 제약 사항, 문제점 요약 보고

## Phase 2: 계획 (Plan)
- 구체적이고 명확한 작업 계획 수립
- '어떤 파일'의 '어떤 부분'을 '어떻게' 수정할지 Step-by-step 작성
- **ADR 선행:** 구현 작업은 반드시 ADR 문서 먼저 작성 (`docs/01_ADR/`)
- 계획 제시 후 **반드시 승인 대기**

## Phase 3: 실행 (Implement)
- 사용자가 계획을 승인했을 때만 코드 작성
- 합의된 계획에 따라서만 수정
- 작업전 반드시 브랜치생성할것
- 작업후 PR develop base로 생성할것.

## Context Optimization (100k+ LOC 필수)
**대규모 코드베이스에서 context 낭비를 방지하는 규칙:**

1. **repo-map.md 우선 참조**: `.claude/repo-map.md`를 먼저 읽고 프로젝트 구조를 파악합니다.
2. **전체 repo 스캔 금지**: `Glob **/*.kt` 같은 패턴은 피합니다. 특정 모듈만 탐색합니다.
3. **LSP 활용**: `lsp_workspace_symbols`로 클래스를 검색합니다. grep보다 정확하고 빠릅니다.
4. **모듈 스코프 제한**: 지정된 모듈 내에서만 작업합니다. 의존성 변경이 필요한 경우만 다른 모듈을 탐색합니다.
5. **기존 유틸리티 확인**: 새로운 유틸리티 생성 전 `module-common`에 이미 존재하는지 확인합니다.

**효과**: Context 사용량 50% 절약, Hallucination 감소, 응답 속도 향상
