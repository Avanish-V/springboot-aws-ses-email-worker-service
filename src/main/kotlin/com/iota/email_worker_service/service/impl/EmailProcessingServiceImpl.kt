package com.iota.email_worker_service.service.impl

import com.iota.email_worker_service.dto.EmailJobMessage
import com.iota.email_worker_service.dto.EmailJobRecipient
import com.iota.email_worker_service.model.CampaignStatus
import com.iota.email_worker_service.model.RecipientStatus
import com.iota.email_worker_service.repository.EmailCampaignRepository
import com.iota.email_worker_service.repository.EmailRecipientRepository
import com.iota.email_worker_service.service.EmailProcessingService
import com.iota.email_worker_service.service.SendEmailDto
import com.iota.email_worker_service.service.SesEmailService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class EmailProcessingServiceImpl(
    private val campaignRepository: EmailCampaignRepository,
    private val recipientRepository: EmailRecipientRepository,
    private val sesEmailService: SesEmailService
) : EmailProcessingService {

    private val log = LoggerFactory.getLogger(EmailProcessingServiceImpl::class.java)

    companion object {
        private val TERMINAL_STATUSES = setOf(
            RecipientStatus.SENT,
            RecipientStatus.DELIVERED,
            RecipientStatus.FAILED,
            RecipientStatus.BOUNCED,
            RecipientStatus.COMPLAINED
        )
    }

    override fun processJob(job: EmailJobMessage) {
        val campaignId = job.campaignId
        log.info("Processing email job: campaignId={}, batchIndex={}, recipients={}",
            campaignId, job.batchIndex, job.recipients.size)

        val campaign = findCampaignWithRetry(campaignId)
        if (campaign == null) {
            log.error("Campaign [{}] not found after retries. Discarding batch index [{}]", campaignId, job.batchIndex)
            return
        }

        if (campaign.status == CampaignStatus.CANCELLED) {
            log.warn("Campaign [{}] is CANCELLED. Discarding remaining jobs for batch [{}]", campaignId, job.batchIndex)
            return
        }

        var hasTransientFailure = false
        var transientErrorMessage: String? = null

        for (recipient in job.recipients) {
            try {
                val shouldContinue = processSingleRecipient(job, campaignId, recipient)
                if (!shouldContinue) {
                    hasTransientFailure = true
                    transientErrorMessage = "Transient failure encountered for recipient ${recipient.email}"
                }
            } catch (e: Exception) {
                log.error("Unhandled error processing recipient [{}] in campaign [{}]: {}",
                    recipient.email, campaignId, e.message, e)
                hasTransientFailure = true
                transientErrorMessage = e.message
            }
        }

        checkAndCompleteCampaign(campaignId)

        if (hasTransientFailure) {
            throw IllegalStateException("Transient failure during batch processing for campaign $campaignId: $transientErrorMessage")
        }
    }

    /**
     * Attempts to claim the recipient row atomically and sends the email via SES.
     * Database updates are executed in short, dedicated transactions to avoid holding
     * DB connections open during SES network calls and throttling backoff.
     * Returns true if processing was completed (either successfully sent or permanently failed),
     * or false if a transient failure occurred and needs SQS redelivery.
     */
    fun processSingleRecipient(
        job: EmailJobMessage,
        campaignId: UUID,
        recipientDto: EmailJobRecipient
    ): Boolean {
        val recipientId = recipientDto.recipientId

        val claimedRows = recipientRepository.claimForProcessing(recipientId)
        if (claimedRows == 0) {
            val existing = recipientRepository.findById(recipientId).orElse(null)
            if (existing != null) {
                if (existing.status in TERMINAL_STATUSES) {
                    log.info("Recipient [{}] already in terminal status [{}]. Skipping duplicate send.",
                        recipientDto.email, existing.status)
                    return true
                }
                if (existing.status == RecipientStatus.PROCESSING) {
                    log.warn("Recipient [{}] is already being processed by another worker. Skipping.", recipientDto.email)
                    return true
                }
            } else {
                log.warn("Recipient [{}] not found in database. Skipping.", recipientId)
                return true
            }
        }

        val personalizedSubject = personalizeText(job.subject, recipientDto)
        val personalizedHtml = personalizeText(job.htmlContent, recipientDto)

        val sendDto = SendEmailDto(
            campaignId = campaignId,
            recipientId = recipientId,
            toEmail = recipientDto.email,
            fromName = job.fromName,
            fromEmail = job.fromEmail,
            replyTo = job.replyTo,
            subject = personalizedSubject,
            htmlBody = personalizedHtml,
            attachments = job.attachments
        )

        val result = sesEmailService.sendEmail(sendDto)

        return if (result.isSuccess && result.sesMessageId != null) {
            recipientRepository.markAsSent(
                recipientId = recipientId,
                sesMessageId = result.sesMessageId,
                sentAt = Instant.now()
            )
            campaignRepository.incrementSentCount(campaignId)
            true
        } else if (result.isTransientError) {
            // Revert status to QUEUED so next redelivery can reclaim and send
            recipientRepository.claimForProcessing(
                recipientId = recipientId,
                processingStatus = RecipientStatus.QUEUED,
                eligibleStatuses = listOf(RecipientStatus.PROCESSING)
            )
            false
        } else {
            // Permanent failure
            val errMsg = result.errorMessage ?: "Unknown permanent error from SES"
            recipientRepository.markAsFailed(
                recipientId = recipientId,
                errorMessage = errMsg
            )
            campaignRepository.incrementFailedCount(campaignId)
            true
        }
    }

    private fun personalizeText(template: String, recipient: EmailJobRecipient): String {
        if (template.isBlank()) return ""
        var text = template
        val fallbackName = recipient.name?.takeIf { it.isNotBlank() } ?: "there"
        text = text.replace("{{name}}", fallbackName, ignoreCase = true)
        text = text.replace("{{email}}", recipient.email, ignoreCase = true)

        for ((key, value) in recipient.metadata) {
            text = text.replace("{{$key}}", value, ignoreCase = true)
        }
        return text
    }

    private fun findCampaignWithRetry(campaignId: UUID): com.iota.email_worker_service.model.EmailCampaign? {
        var campaign = campaignRepository.findById(campaignId).orElse(null)
        if (campaign != null) return campaign

        // If producer published before DB commit completed, wait briefly and retry
        for (i in 1..3) {
            try {
                Thread.sleep(200L * i)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            campaign = campaignRepository.findById(campaignId).orElse(null)
            if (campaign != null) {
                log.info("Campaign [{}] resolved after {} retries", campaignId, i)
                return campaign
            }
        }
        return null
    }

    private fun checkAndCompleteCampaign(campaignId: UUID) {
        val campaign = campaignRepository.findById(campaignId).orElse(null) ?: return

        val total = campaign.totalRecipients
        if (total <= 0) return

        val completedCount = recipientRepository.countByCampaignIdAndStatusIn(
            campaignId,
            listOf(
                RecipientStatus.SENT,
                RecipientStatus.DELIVERED,
                RecipientStatus.FAILED,
                RecipientStatus.BOUNCED,
                RecipientStatus.COMPLAINED
            )
        )

        if (completedCount >= total) {
            val failedCount = recipientRepository.countByCampaignIdAndStatus(campaignId, RecipientStatus.FAILED)
            val bouncedCount = recipientRepository.countByCampaignIdAndStatus(campaignId, RecipientStatus.BOUNCED)

            val finalStatus = if (failedCount > 0 || bouncedCount > 0) {
                CampaignStatus.COMPLETED_WITH_ERRORS
            } else {
                CampaignStatus.COMPLETED
            }

            campaignRepository.updateCampaignCompletion(
                campaignId = campaignId,
                status = finalStatus,
                completedAt = Instant.now()
            )

            log.info("Campaign [{}] reached completion: status={}, total={}, completedCount={}, failed={}",
                campaignId, finalStatus, total, completedCount, failedCount)
        }
    }
}

