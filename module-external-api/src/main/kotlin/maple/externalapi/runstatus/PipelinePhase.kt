package maple.externalapi.runstatus

enum class PipelinePhase {
    IDLE,
    RANKING_FETCH,
    OCID_LOOKUP,
    OCID_CACHE_REFRESH,
    CHARACTER_BASIC,
    ITEM_EQUIPMENT,
    COMPLETED,
    FAILED,
}
