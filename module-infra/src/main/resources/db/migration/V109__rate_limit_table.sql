-- Issue #652: Rate Limiting 테이블 생성
-- PostgreSQL 기반 Sliding Window Counter Rate Limiter용 테이블
CREATE TABLE IF NOT EXISTS rate_limit (
    key VARCHAR(255) PRIMARY KEY,
    count BIGINT NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 만료된 엔트리 정리를 위한 인덱스
CREATE INDEX IF NOT EXISTS idx_rate_limit_expires_at ON rate_limit(expires_at);
