# ADR-315: 4-Module Separation + Kotlin Migration

## Status
Accepted (2026-02-27)

## Context
- module-app contains 347 Java files (all code)
- Kotlin migration is planned anyway
- Doing both together reduces total refactoring cycles

## Decision
1. Split into 4 modules:
   - module-common: Pure utilities (Kotlin)
   - module-core: Business rules, ports (Kotlin + Java)
   - module-infra: External adapters (Java first, Kotlin later)
   - module-web: HTTP layer, DTOs (Kotlin data class)

2. Migration order per module:
   - DTO → Kotlin data class (immediate)
   - Models → Kotlin data class (immediate)
   - Business logic → Kotlin (gradual)
   - Infra adapters → Java (keep, convert later)

## Consequences
- Faster path to Kotlin codebase
- Higher risk per commit (rollback with git)
- More complex debugging

## Related
- Issues: #409-#443
- Design: docs/plans/2026-02-27-module-separation-design.md