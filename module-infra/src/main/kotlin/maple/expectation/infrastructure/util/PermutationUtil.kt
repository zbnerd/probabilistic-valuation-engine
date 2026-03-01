package maple.expectation.infrastructure.util

object PermutationUtil {
    @JvmStatic
    fun generateUniquePermutations(input: List<String>): Set<List<String>> {
        val result = HashSet<List<String>>()
        permute(input.toMutableList(), 0, result)
        return result
    }

    private fun permute(arr: MutableList<String>, k: Int, result: MutableSet<List<String>>) {
        if (k == arr.size) {
            result.add(ArrayList(arr))
        } else {
            for (i in k until arr.size) {
                val temp = arr[k]
                arr[k] = arr[i]
                arr[i] = temp
                permute(arr, k + 1, result)
                arr[i] = arr[k]
                arr[k] = temp
            }
        }
    }
}
