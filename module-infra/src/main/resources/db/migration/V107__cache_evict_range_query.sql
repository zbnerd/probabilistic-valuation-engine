-- evictAll()이 partial index의 WHERE 조건(expires_at > NOW())에 걸리지 않는
-- expired row도 삭제해야 하므로, cache_key 단일 컬럼 인덱스 추가
-- 기존 idx_cache_storage_key_expires는 partial index이므로 evictAll에 부적합
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cache_storage_key_prefix
    ON cache_storage (cache_key COLLATE "C");

COMMENT ON COLUMN cache_storage.cache_key IS 'Key format: {cacheName}:v1:{actualKey}. Range query: >= prefix AND < prefix~. Tilde (~) forbidden in key parts.';
COMMENT ON INDEX idx_cache_storage_key_prefix IS 'Index for cache key prefix range scans with C collation (evictAll). Query must use COLLATE "C" to match index.';
