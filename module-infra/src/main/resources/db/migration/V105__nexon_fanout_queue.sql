-- V105: Nexon FanOut Queue 생성 (429 재시도 전용)
-- Batch Lane에서 429 Rate Limit 발생 시 FanOutQueueProducer가 메시지를 발행
-- NexonFanOutWorker가 소비 후 EquipmentFetchProvider.fetchWithCache()로 재시도
SELECT pgmq.create('nexon_fanout_queue');
