-- Phase 4: Drop outbox tables replaced by PGMQ
-- donation_outbox: Phase 2에서 PGMQ donation_queue로 대체
-- event_outbox, nexon_api_outbox: MySQL 잔여 테이블 (PostgreSQL에 존재할 수도 있음)
DROP TABLE IF EXISTS donation_outbox;
DROP TABLE IF EXISTS event_outbox;
DROP TABLE IF EXISTS nexon_api_outbox;
