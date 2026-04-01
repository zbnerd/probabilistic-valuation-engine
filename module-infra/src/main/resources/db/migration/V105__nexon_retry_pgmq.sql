-- V105: Nexon API Retry PGMQ Queue (Phase 3 - Outbox to PGMQ Migration)
-- Creates nexon_retry_queue for PGMQ-based Nexon API retry processing
-- Replaces NexonApiOutbox table polling with PGMQ message queue

SELECT pgmq.create('nexon_retry_queue');
