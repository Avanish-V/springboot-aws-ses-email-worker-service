package com.iota.email_worker_service.repository

import com.iota.email_worker_service.model.CampaignStatus
import com.iota.email_worker_service.model.EmailCampaign
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
interface EmailCampaignRepository : JpaRepository<EmailCampaign, UUID> {

    @Transactional
    @Modifying
    @Query("""
        UPDATE EmailCampaign c 
        SET c.sentCount = c.sentCount + 1,
            c.updatedAt = :now
        WHERE c.id = :campaignId
    """)
    fun incrementSentCount(@Param("campaignId") campaignId: UUID, @Param("now") now: Instant = Instant.now()): Int

    @Transactional
    @Modifying
    @Query("""
        UPDATE EmailCampaign c 
        SET c.failedCount = c.failedCount + 1,
            c.updatedAt = :now
        WHERE c.id = :campaignId
    """)
    fun incrementFailedCount(@Param("campaignId") campaignId: UUID, @Param("now") now: Instant = Instant.now()): Int

    @Transactional
    @Modifying
    @Query("""
        UPDATE EmailCampaign c 
        SET c.status = :status,
            c.completedAt = :completedAt,
            c.updatedAt = :now
        WHERE c.id = :campaignId
    """)
    fun updateCampaignCompletion(
        @Param("campaignId") campaignId: UUID,
        @Param("status") status: CampaignStatus,
        @Param("completedAt") completedAt: Instant,
        @Param("now") now: Instant = Instant.now()
    ): Int
}
