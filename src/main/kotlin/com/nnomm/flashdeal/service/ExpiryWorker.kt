package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.ClaimStatus
import com.nnomm.flashdeal.repository.ClaimRepository
import com.nnomm.flashdeal.repository.DealRepository
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class ExpiryWorker(
    private val claimRepository: ClaimRepository,
    private val dealRepository: DealRepository,
    private val intervalMillis: Long = 1000L
) {
    private val logger = LoggerFactory.getLogger(ExpiryWorker::class.java)
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (job != null) return
        job = scope.launch {
            logger.info("Starting ExpiryWorker background sweeper (interval: {}ms)", intervalMillis)
            while (isActive) {
                try {
                    val expiredCount = processExpiredClaims()
                    if (expiredCount > 0) {
                        logger.info("ExpiryWorker processed and restored units for {} expired claims", expiredCount)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Error in ExpiryWorker execution: {}", e.message, e)
                }
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
        logger.info("ExpiryWorker stopped")
    }

    /**
     * Finds and expires overdue claims. Returns number of claims expired.
     * Can be invoked directly in integration tests or on schedule.
     */
    fun processExpiredClaims(): Int {
        return DatabaseFactory.withTransaction { conn ->
            val expiredClaims = claimRepository.findExpiredReservedClaimsForUpdate(conn, limit = 100)
            for (claim in expiredClaims) {
                // Restore units to stock
                dealRepository.restoreStock(conn, claim.dealId, claim.quantity)
                // Mark claim as EXPIRED
                claimRepository.updateStatus(conn, claim.id, ClaimStatus.EXPIRED.name)
            }
            expiredClaims.size
        }
    }
}
