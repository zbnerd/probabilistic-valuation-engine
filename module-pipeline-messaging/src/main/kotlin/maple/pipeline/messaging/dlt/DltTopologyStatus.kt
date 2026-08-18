package maple.pipeline.messaging.dlt

class DltTopologyStatus private constructor(
    val subscriptions: Int,
    sourcePartitions: Map<String, Int>,
    dltPartitions: Map<String, Int>,
    missingSources: Collection<String>,
    actions: Collection<DltTopologyAction>,
    val failureCategory: String?,
) {
    val sourcePartitions: Map<String, Int> = sourcePartitions.toSortedMap()
    val dltPartitions: Map<String, Int> = dltPartitions.toSortedMap()
    val missingSources: List<String> = missingSources.sorted()
    val actions: List<DltTopologyAction> = actions.toList()
    val healthy: Boolean = failureCategory == null && this.missingSources.isEmpty() && this.actions.isEmpty()

    init {
        require(subscriptions >= 0)
        this.sourcePartitions.forEach { (topic, partitions) ->
            requireBoundedTopic(topic)
            require(partitions > 0)
        }
        this.dltPartitions.forEach { (topic, partitions) ->
            requireBoundedTopic(topic)
            require(partitions > 0)
        }
        this.missingSources.forEach(::requireBoundedTopic)
    }

    companion object {
        fun evaluate(
            sourceTopics: Collection<String>,
            sourcePartitions: Map<String, Int>,
            dltPartitions: Map<String, Int>,
            subscriptions: Int = sourceTopics.size,
        ): DltTopologyStatus {
            val expectedSources = sourceTopics.map(::requireBoundedTopic).distinct().sorted()
            val missingSources = expectedSources.filterNot(sourcePartitions::containsKey)
            val actions = if (missingSources.isNotEmpty()) {
                emptyList()
            } else {
                expectedSources.mapNotNull { sourceTopic ->
                    val sourceCount = requireNotNull(sourcePartitions[sourceTopic])
                    val dltTopic = requireBoundedTopic("$sourceTopic.DLT")
                    val dltCount = dltPartitions[dltTopic]
                    when {
                        dltCount == null -> DltTopologyAction.CreateDlt(dltTopic, sourceCount)
                        dltCount < sourceCount -> DltTopologyAction.ExpandDlt(dltTopic, sourceCount)
                        else -> null
                    }
                }
            }
            return DltTopologyStatus(
                subscriptions = subscriptions,
                sourcePartitions = sourcePartitions.filterKeys(expectedSources::contains),
                dltPartitions = dltPartitions.filterKeys { topic -> expectedSources.any { "$it.DLT" == topic } },
                missingSources = missingSources,
                actions = actions,
                failureCategory = null,
            )
        }

        internal fun failed(
            subscriptions: Int,
            failure: Throwable,
        ): DltTopologyStatus = DltTopologyStatus(
            subscriptions = subscriptions,
            sourcePartitions = emptyMap(),
            dltPartitions = emptyMap(),
            missingSources = emptyList(),
            actions = emptyList(),
            failureCategory = failure.javaClass.simpleName.take(MAX_FAILURE_CATEGORY_LENGTH),
        )

        private const val MAX_FAILURE_CATEGORY_LENGTH = 64
    }
}
