package maple.restcontroller.read

interface RequestBuffer {
    fun offer(request: ReadRequest): Boolean
    fun drain(maxItems: Int): List<ReadRequest>
    fun size(): Int
    fun isEmpty(): Boolean
    fun stopAccepting()
    fun failAllPending()
}
