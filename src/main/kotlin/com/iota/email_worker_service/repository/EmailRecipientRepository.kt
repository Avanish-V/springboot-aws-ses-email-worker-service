package com.iota.email_worker_service.repository

import com.iota.email_worker_service.model.EmailRecipient
import com.iota.email_worker_service.model.RecipientStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
interface EmailRecipientRepository : JpaRepository<EmailRecipient, UUID> {

    @Transactional
    @Modifying
    @Query("""
        UPDATE EmailRecipient r 
        SET r.status = :processingStatus,
            r.updatedAt = :now
        WHERE r.id = :recipientId 
        AND r.status IN (:eligibleStatuses)
    """)
    fun claimForProcessing(
        @Param("recipientId") recipientId: UUID,
        @Param("processingStatus") processingStatus: RecipientStatus = RecipientStatus.PROCESSING,
        @Param("eligibleStatuses") eligibleStatuses: Collection<RecipientStatus> = listOf(RecipientStatus.PENDING, RecipientStatus.QUEUED),
        @Param("now") now: Instant = Instant.now()
    ): Int

    @Transactional
    @Modifying
    @Query("""
        UPDATE EmailRecipient r 
        SET r.status = :status,
            r.sesMessageId = :sesMessageId,
            r.sentAt = :sentAt,
            r.errorMessage = NULL,
            r.updatedAt = :now
        WHERE r.id = :recipientId
    """)
    fun markAsSent(
        @Param("recipientId") recipientId: UUID,
        @Param("status") status: RecipientStatus = RecipientStatus.SENT,
        @Param("sesMessageId") sesMessageId: String,
        @Param("sentAt") sentAt: Instant = Instant.now(),
        @Param("now") now: Instant = Instant.now()
    ): Int

    @Transactional
    @Modifying
    @Query("""
        UPDATE EmailRecipient r 
        SET r.status = :status,
            r.errorMessage = :errorMessage,
            r.updatedAt = :now
        WHERE r.id = :recipientId
    """)
    fun markAsFailed(
        @Param("recipientId") recipientId: UUID,
        @Param("status") status: RecipientStatus = RecipientStatus.FAILED,
        @Param("errorMessage") errorMessage: String,
        @Param("now") now: Instant = Instant.now()
    ): Int

    fun countByCampaignId(campaignId: UUID): Long

    fun countByCampaignIdAndStatus(campaignId: UUID, status: RecipientStatus): Long

    fun countByCampaignIdAndStatusIn(campaignId: UUID, statuses: Collection<RecipientStatus>): Long
}
