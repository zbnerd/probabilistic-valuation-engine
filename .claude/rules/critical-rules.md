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

## 환경 설정 (Environment Configuration)

**서버 IP 주소는 .env 파일에서 관리합니다.**
- APP_SERVER_IP: 애플리케이션 서버 IP
- DB_SERVER_IP: PostgreSQL 데이터베이스 서버 IP

**모든 서버 관련 작업 전에 반드시 .env 파일을 먼저 읽어야 합니다.**
- IP 주소를 코드나 문서에 하드코딩하지 마세요
- .env 파일만 수정하면 서버 변경이 완료됩니다

```bash
# 서버 작업 전 필수 선행 작업
source .env  # 또는 Read 도구로 .env 파일 읽기
echo $APP_SERVER_IP
echo $DB_SERVER_IP
```
