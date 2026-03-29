-- Expectation Calculation Queue: PGMQ migration
-- Replace in-memory LinkedBlockingQueue with durable PGMQ queues
SELECT pgmq.create('expectation_calc_high');
SELECT pgmq.create('expectation_calc_low');
