-- V121: Split external API, CPU calculation, and result persistence pipeline stages.
--
-- External API workers now publish calculation work after input/snapshot staging.
-- Calculation workers publish completed compressed results for DB-bound persistence.

SELECT pgmq.create('calculation_requested_queue');
SELECT pgmq.create('calculation_completed_queue');
