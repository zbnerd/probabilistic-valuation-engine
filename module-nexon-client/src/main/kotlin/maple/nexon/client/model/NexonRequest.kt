package maple.nexon.client.model

data class NexonRequest(
    val purpose: NexonEndpointPurpose,
    val path: String,
    val query: Map<String, String>,
    val endpointTemplate: String,
) {
    init {
        require(path.startsWith('/')) { "Nexon request path must be absolute" }
        require(endpointTemplate.startsWith('/')) { "Nexon endpoint template must be absolute" }
        require('?' !in endpointTemplate && '#' !in endpointTemplate) {
            "Nexon endpoint template must not contain query or fragment data"
        }
        require(query.keys.none(String::isBlank)) { "Nexon query names must not be blank" }
    }
}
