# claude.md

이 파일은 Claude Code(claude.ai/code)가 이 프로젝트에서 작업할 때 따라야 할 핵심 규칙을 정의합니다.

---

# Environment Setup (CRITICAL)

**모든 작업 시작 전 반드시 실행:**
```bash
source .env
```

Environment file location: `/home/maple/MapleExpectation/.env`

Required env vars:
- `DB_ROOT_PASSWORD`
- `NEXON_API_KEY`
- `JWT_SECRET`
- `FINGERPRINT_SECRET`
- `ADMIN_FINGERPRINTS`
- `ALERT_DISCORD_WEBHOOK_URL`

**앱 시작 실패 시 체크리스트:**
1. `echo $NEXON_API_KEY` → 비어있으면 `source .env` 후 재시도
2. PostgreSQL 실행 중인지 확인: `docker ps | grep postgres`
3. 포트 충돌 확인: `lsof -i :8080`

---

# CRITICAL RULES

## .env Protection
**NEVER modify .env without explicit approval**
- NEVER overwrite .env file
- NEVER delete existing variables from .env
- ONLY append new variables if absolutely needed
- Before any .env modification, show diff first and wait for approval
- .env contains production credentials - treat as READ-ONLY

## Server IPs
- Read APP_SERVER_IP and DB_SERVER_IP from .env before any server-related tasks
- Never hardcode IPs in any file (code, documentation, configs)
- Always use environment variables from .env

---

# Dev Workflow (Hot Reload ~2s)

**터미널 2개 유지:**

```bash
# Terminal 1: Continuous compilation
./gradlew compileKotlin --continuous

# Terminal 2: App 실행 (한 번만)
source .env && export DB_ROOT_PASSWORD NEXON_API_KEY JWT_SECRET FINGERPRINT_SECRET ADMIN_FINGERPRINTS ALERT_DISCORD_WEBHOOK_URL
./gradlew :module-app:bootRun
```

**코드 수정 후:**
- Terminal 1이 자동 컴파일 (~1s)
- DevTools가 감지 → Spring 재시작 (~1-2s)
- **Total: ~2초 피드백 루프**

**주의사항:**
- 앱 수동 재시작 금지 (DB migration 추가 시에만)
- Cold start: 40s, Warm restart: 2s
- FastAPI 수준의 개발 경험

---
