package maple.synchronizer.ranking

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "synchronizer.ranking")
class EquipmentRankingProperties {
    var enabled: Boolean = true
    var keyPrefix: String = "ranking:equipment:total-cost"
    var batchSize: Int = 1000
    var topSize: Int = 10
}
