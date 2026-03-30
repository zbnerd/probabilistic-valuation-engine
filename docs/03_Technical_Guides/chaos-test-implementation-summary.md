# Chaos Test Module - Implementation Plan Summary

> **Project**: probabilistic-valuation-engine Chaos Test Separation
> **Status**: Design Complete - Ready for Implementation
> **Date**: 2026-02-11
> **Total Deliverables**: 9 documents, 3 configuration files, 1,989 lines

---

## Executive Summary

Successfully designed and documented the separation of 22 chaos/nightmare tests from `module-app/src/test-legacy` into a dedicated `module-chaos-test` module. The design provides physical isolation, flexible execution, and CI/CD clarity while maintaining the fast PR validation pipeline (3-5 minutes).

---

## Deliverables Matrix

### 1. Core Module Structure

| File | Lines | Purpose | Status |
|------|-------|---------|--------|
| `module-chaos-test/build.gradle` | 257 | Gradle configuration with custom sourceSet | ✅ Created |
| `module-chaos-test/src/chaos-test/resources/application-chaos.yml` | 102 | Chaos test profile configuration | ✅ Created |
| `module-chaos-test/src/chaos-test/resources/junit-platform.properties` | 18 | JUnit platform settings | ✅ Created |
| **Total** | **377** | **Module Infrastructure** | **100%** |

### 2. Documentation (5 documents)

| Document | Lines | Purpose | Status |
|----------|-------|---------|--------|
| `chaos-test-module-architecture.md` | 499 | Full architecture design with migration checklist | ✅ Created |
| `chaos-test-quick-start.md` | 143 | Developer execution guide | ✅ Created |
| `chaos-test-cicd-patterns.md` | 568 | CI/CD integration patterns and workflows | ✅ Created |
| `MIGRATION_STATUS.md` | 252 | Progress tracking and migration checklist | ✅ Created |
| **Total** | **1,462** | **Technical Documentation** | **100%** |

### 3. Architecture Decision Record

| Document | Lines | Purpose | Status |
|----------|-------|---------|--------|
| `ADR-025-chaos-test-module-separation.md` | 270 | Formal ADR with alternatives and consequences | ✅ Created |
| **Total** | **270** | **Decision Record** | **100%** |

**Grand Total**: **9 files, 1,989 lines of documentation and configuration**

---

## Module Architecture

### Directory Structure Created

```
module-chaos-test/
├── build.gradle                            # Custom chaosTest sourceSet, tasks, JVM config
└── src/chaos-test/                         # Dedicated sourceSet (separate from test/)
    ├── java/maple/expectation/chaos/
    │   ├── network/                        # Network chaos (2 tests)
    │   ├── core/                           # Core chaos (1 test)
    │   ├── resource/                       # Resource chaos (3 tests)
    │   └── nightmare/                      # Nightmare scenarios (15 tests)
    └── resources/
        ├── application-chaos.yml           # Chaos test profile
        └── junit-platform.properties       # Test configuration
```

### Test Inventory

| Category | Count | Tests |
|----------|-------|-------|
| **Chaos - Network** | 2 | ClockDriftChaosTest, ThunderingHerdLockChaosTest |
| **Chaos - Core** | 1 | OOMChaosTest |
| **Chaos - Resource** | 3 | DiskFullChaosTest, GcPauseChaosTest, PoolExhaustionChaosTest |
| **Nightmare** | 15 | AopOrderNightmareTest, AsyncContextLossNightmareTest, ... |
| **Total** | **22** | **All tests identified for migration** |

---

## Gradle Configuration

### Custom SourceSet

```gradle
sourceSets {
	chaosTest {
		java {
			compileClasspath += main.sourceSets.main.output
			compileClasspath += test.sourceSets.test.output
			runtimeClasspath += main.sourceSets.main.output
			runtimeClasspath += test.sourceSets.test.output
			srcDir file('src/chaos-test/java')
		}
		resources {
			srcDir file('src/chaos-test/resources')
		}
	}
}
```

### Gradle Tasks

| Task | Description | Usage |
|------|-------------|-------|
| `chaosTest` | Run all 22 chaos/nightmare tests | `./gradlew :module-chaos-test:chaosTest` |
| `chaosTestNetwork` | Network chaos only | `./gradlew :module-chaos-test:chaosTestNetwork` |
| `chaosTestResource` | Resource chaos only | `./gradlew :module-chaos-test:chaosTestResource` |
| `chaosTestCore` | Core chaos only | `./gradlew :module-chaos-test:chaosTestCore` |
| `nightmareTest` | 15 nightmare scenarios only | `./gradlew :module-chaos-test:nightmareTest` |

### JVM Configuration

```gradle
jvmArgs = [
	'-Xms512m',
	'-Xmx2048m',      # 2GB heap for OOM simulation
	'-XX:+UseG1GC',    # Predictable GC pauses
	'-XX:MaxMetaspaceSize=512m'
]
```

---

## CI/CD Integration Strategy

### Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ PR Validation (CI) - NO CHANGES                             │
│ Trigger: Pull Request to master/develop                      │
│ Tests: Unit + Integration (-PfastTest)                      │
│ Duration: 3-5 minutes                                        │
│ Chaos Tests: EXCLUDED (already excludes via -PfastTest)     │
├─────────────────────────────────────────────────────────────┤
│ Nightly Chaos Tests (NEW WORKFLOW)                          │
│ Trigger: Schedule (KST 00:00) OR manual                     │
│ Tests: Chaos (7) + Nightmare (15)                           │
│ Duration: 30-45 minutes                                      │
│ Module: module-chaos-test ONLY                              │
└─────────────────────────────────────────────────────────────┘
```

### Workflow Files

| Workflow | Action | Status |
|----------|--------|--------|
| `.github/workflows/ci.yml` | NO CHANGES (already excludes chaos) | ✅ Documented |
| `.github/workflows/nightly-chaos.yml` | CREATE NEW (chaos + nightmare) | 📋 Specified |
| `.github/workflows/nightly.yml` | UPDATE (remove chaos steps) | 📋 Specified |

### CI/CD Features

**Manual Trigger Options**:
- Chaos category selection: `all`, `network`, `resource`, `core`
- Skip nightmare tests: `true`/`false`
- Custom timeout configuration

**Test Reports**:
- Chaos test reports: 30-day retention
- Nightmare test reports: 30-day retention
- Combined reports with summary
- Flaky test logs: 90-day retention

---

## Migration Checklist (6 Phases)

### Phase 1: Design & Structure ✅ COMPLETE
- [x] Create `module-chaos-test/build.gradle`
- [x] Create directory structure
- [x] Create configuration files
- [x] Write documentation
- [x] Create ADR-025

### Phase 2: File Migration ⏳ PENDING
- [ ] Move 22 test files from `module-app/src/test-legacy` to `module-chaos-test`
- [ ] Update package declarations
- [ ] Resolve support class dependencies

### Phase 3: Build Configuration ⏳ PENDING
- [ ] Update `settings.gradle`: Add `include 'module-chaos-test'`
- [ ] Update `module-app/build.gradle`: Remove `test-legacy` exclusion
- [ ] Verify dependencies

### Phase 4: CI/CD Integration ⏳ PENDING
- [ ] Create `.github/workflows/nightly-chaos.yml`
- [ ] Update `.github/workflows/nightly.yml`
- [ ] Test workflows in feature branch

### Phase 5: Validation ⏳ PENDING
- [ ] Local: `./gradlew :module-chaos-test:chaosTest`
- [ ] Categories: `./gradlew :module-chaos-test:chaosTestNetwork`
- [ ] Regular tests: `./gradlew test` (should exclude chaos)
- [ ] CI/CD: Manual trigger succeeds

### Phase 6: Cleanup ⏳ PENDING
- [ ] Remove `module-app/src/test-legacy`
- [ ] Update CLAUDE.md, README
- [ ] Archive old structure

---

## Benefits & Value

### 1. Physical Isolation
- **Before**: Chaos tests in `module-app/src/test-legacy` (accidental execution risk)
- **After**: Dedicated `module-chaos-test` module (impossible to accidentally run)

### 2. CI/CD Clarity
- **Before**: Tag filtering with `-PchaosTest` flag
- **After**: Explicit module execution with dedicated workflow

### 3. Flexible Execution
- **Before**: All-or-nothing chaos test execution
- **After**: Category-specific tasks (network, resource, core)

### 4. Developer Experience
- **Before**: Unclear where chaos tests live
- **After**: Clear module with dedicated documentation

### 5. Resource Configuration
- **Before**: Shared JVM config (512MB heap)
- **After**: Dedicated config (2GB heap for OOM tests)

---

## Risk Assessment

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Support class dependencies | Medium | Medium | Create module-test-utils or duplicate |
| Circular dependencies | Medium | Low | Careful dependency review |
| CI/CD workflow errors | High | Low | Test in feature branch first |
| Package import issues | Low | Low | Automated find-replace |
| Test execution failures | Medium | Low | Gradual migration with validation |

**Overall Risk Level**: **LOW** (well-documented, reversible)

---

## Rollback Plan

If migration fails:

1. **Revert files**:
   ```bash
   git checkout HEAD -- module-chaos-test/
   git checkout HEAD -- module-app/src/test-legacy/
   ```

2. **Remove module from settings.gradle**:
   ```gradle
   # Remove this line
   include 'module-chaos-test'
   ```

3. **Restore CI/CD workflows**:
   ```bash
   git checkout HEAD -- .github/workflows/nightly.yml
   rm .github/workflows/nightly-chaos.yml
   ```

**Rollback Time**: < 5 minutes

---

## Success Criteria

Migration is successful when ALL of the following are true:

- [x] **Criterion 1**: Module structure created with all configuration files
- [ ] **Criterion 2**: All 22 tests execute in `module-chaos-test`
- [ ] **Criterion 3**: Regular test suite (`./gradlew test`) excludes chaos tests
- [ ] **Criterion 4**: PR CI time remains ≤ 5 minutes
- [ ] **Criterion 5**: Nightly chaos workflow executes all tests successfully
- [ ] **Criterion 6**: No `test-legacy` references remain in codebase
- [ ] **Criterion 7**: Test coverage reports include chaos module separately

**Current Progress**: 1/7 criteria met (14%)

---

## Next Steps

### Immediate Actions (Design Phase Complete)

1. **Review Documentation**: 5-Agent Council reviews all documents
2. **Approve ADR-025**: Formal acceptance of chaos test module separation
3. **Create Feature Branch**: `feature/chaos-test-module`
4. **Begin Migration**: Execute Phase 2 (File Migration)

### Implementation Timeline Estimate

| Phase | Estimated Time | Dependencies |
|-------|---------------|--------------|
| Phase 2: File Migration | 2-3 hours | Design approval |
| Phase 3: Build Config | 1 hour | Phase 2 complete |
| Phase 4: CI/CD Integration | 2-3 hours | Phase 3 complete |
| Phase 5: Validation | 1-2 hours | Phase 4 complete |
| Phase 6: Cleanup | 1 hour | Phase 5 complete |
| **Total** | **7-10 hours** | **1-2 days** |

---

## References

### Documentation Created
1. [Chaos Test Module Architecture](/home/maple/probabilistic-valuation-engine/docs/03_Technical_Guides/chaos-test-module-architecture.md) - 499 lines
2. [Chaos Test Quick Start](/home/maple/probabilistic-valuation-engine/docs/03_Technical_Guides/chaos-test-quick-start.md) - 143 lines
3. [CI/CD Integration Patterns](/home/maple/probabilistic-valuation-engine/docs/03_Technical_Guides/chaos-test-cicd-patterns.md) - 568 lines
4. [Migration Status Tracking](/home/maple/probabilistic-valuation-engine/MIGRATION_STATUS.md) - 252 lines
5. [ADR-025: Chaos Test Module Separation](/home/maple/probabilistic-valuation-engine/docs/01_ADR/ADR-025-chaos-test-module-separation.md) - 270 lines

### Related Documents
- [ADR-014: Multi-module Cross-cutting Concerns](/home/maple/probabilistic-valuation-engine/docs/01_ADR/ADR-014-multi-module-cross-cutting-concerns.md)
- [Test Strategy](/home/maple/probabilistic-valuation-engine/docs/02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md)
- [Flaky Test Management](/home/maple/probabilistic-valuation-engine/docs/03_Technical_Guides/flaky-test-management.md)

---

## Appendix: File Manifest

### Files Created (9 total)

```
Configuration Files (3):
├── module-chaos-test/build.gradle (257 lines)
├── module-chaos-test/src/chaos-test/resources/application-chaos.yml (102 lines)
└── module-chaos-test/src/chaos-test/resources/junit-platform.properties (18 lines)

Documentation (5):
├── docs/03_Technical_Guides/chaos-test-module-architecture.md (499 lines)
├── docs/03_Technical_Guides/chaos-test-quick-start.md (143 lines)
├── docs/03_Technical_Guides/chaos-test-cicd-patterns.md (568 lines)
├── MIGRATION_STATUS.md (252 lines)
└── docs/01_ADR/ADR-025-chaos-test-module-separation.md (270 lines)

Directories Created (5):
├── module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/
├── module-chaos-test/src/chaos-test/java/maple/expectation/chaos/core/
├── module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/
├── module-chaos-test/src/chaos-test/java/maple/expectation/chaos/nightmare/
└── module-chaos-test/src/chaos-test/resources/
```

---

**Document Status**: ✅ Design Phase Complete
**Next Phase**: Implementation (File Migration)
**Owner**: SRE-Gatekeeper (Red Agent)
**Reviewers**: 5-Agent Council
**Approval**: Pending
