# 작업 규칙 (Workflow Rules)

## 10. Definition of Done

- [ ] ADR 문서 작성 (구현 작업만)
- [ ] Unit 테스트 통과 (`./gradlew test`)
- [ ] 서버 구동 후 API 런타임 검증 (커밋/푸시 전 필수)
- [ ] CLAUDE.md 원칙 준수
- [ ] 통합테스트 금지 (Testcontainers 포함) - Issue #207
- [ ] 브랜치 생성

**서버 런타임 검증 절차 (커밋 전 필수):**
1. `set -a && source .env && set +a && ./gradlew :module-app:bootRun` 으로 서버 구동
2. 관련 엔드포인트 curl로 호출하여 정상 응답 확인
3. 서버 로그에서 에러/예외 없음 확인
4. **컴파일 + 테스트 통과 ≠ 런타임 정상 동작 보장** — 반드시 실제 서버에서 검증

## 11. 검증 명령어

```bash
./gradlew compileKotlin compileJava --continue  # 컴파일 확인
./gradlew test                        # 전체 테스트
```
- 컴파일검증시 --continue 반드시 사용할것.
- 컴파일, 테스트 검증시 처음부터 실패하는경우, 에러나는경우만 메시지 나타나도록 할것. 없으면 성공.

## 12. Flaky Test Prevention
- kotlin `delay()` 사용금지
- `Thread.sleep()` 금지 → `Awaitility` 사용
- 테스트 간 상태 공유 금지
- `@DirtiesContext` 남용 금지
