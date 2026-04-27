package maple.expectation.core.port.out

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import java.util.UUID

interface CalculationJobPort {
    fun createJob(ocid: String, userIgn: String, presetNo: Int): CalculationJob
    fun findJobById(jobId: UUID): CalculationJob?
    fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean
    fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean
    fun incrementRetry(jobId: UUID, errorCode: String): Boolean
    fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean
    fun unlock(jobId: UUID): Boolean
    fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob>
    fun findActiveJobByOcid(ocid: String, presetNo: Int): CalculationJob?
}
