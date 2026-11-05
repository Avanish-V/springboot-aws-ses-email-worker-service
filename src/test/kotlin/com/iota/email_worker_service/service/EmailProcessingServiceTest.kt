package com.iota.email_worker_service.service

import com.iota.email_worker_service.dto.EmailJobMessage
import com.iota.email_worker_service.dto.EmailJobRecipient
import com.iota.email_worker_service.model.CampaignStatus
import com.iota.email_worker_service.model.EmailCampaign
import com.iota.email_worker_service.model.EmailRecipient
import com.iota.email_worker_service.model.RecipientStatus
import com.iota.email_worker_service.repository.EmailCampaignRepository
import com.iota.email_worker_service.repository.EmailRecipientRepository
import com.iota.email_worker_service.service.impl.EmailProcessingServiceImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.util.Optional
import java.util.UUID

class EmailProcessingServiceTest {

    private lateinit var campaignRepository: EmailCampaignRepository
    private lateinit var recipientRepository: EmailRecipientRepository
    private lateinit var sesEmailService: SesEmailService
    private lateinit var service: EmailProcessingServiceImpl

    @BeforeEach
    fun setUp() {
        campaignRepository = mock()
        recipientRepository = mock()
        sesEmailService = mock()

        service = EmailProcessingServiceImpl(campaignRepository, recipientRepository, sesEmailService)
    }

    @Test
    fun `should successfully process email job and personalize tokens`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val campaign = EmailCampaign(
            id = campaignId,
            title = "Engineering Outreach",
            status = CampaignStatus.PROCESSING,
            totalRecipients = 1
        )
        whenever(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign))
        whenever(recipientRepository.claimForProcessing(eq(recipientId), any(), any(), any())).thenReturn(1)

        val sendResult = SendEmailResult(isSuccess = true, sesMessageId = "ses-msg-abc")
        whenever(sesEmailService.sendEmail(any())).thenReturn(sendResult)

        val job = EmailJobMessage(
            campaignId = campaignId,
            batchIndex = 0,
            recipients = listOf(
                EmailJobRecipient(
                    recipientId = recipientId,
                    email = "alex@example.com",
                    name = "Alex Smith",
                    metadata = mapOf("role" to "Tech Lead")
                )
            ),
            subject = "Opportunity for {{role}}",
            htmlContent = "<p>Hi {{name}}, we have a role: {{role}}</p>",
            fromName = "Recruiter",
            fromEmail = "recruiter@example.com"
        )

        service.processJob(job)

        verify(sesEmailService).sendEmail(check {
            assertEquals("Opportunity for Tech Lead", it.subject)
            assertEquals("<p>Hi Alex Smith, we have a role: Tech Lead</p>", it.htmlBody)
            assertEquals("alex@example.com", it.toEmail)
        })

        verify(recipientRepository).markAsSent(eq(recipientId), eq(RecipientStatus.SENT), eq("ses-msg-abc"), any(), any())
        verify(campaignRepository).incrementSentCount(eq(campaignId), any())
    }

    @Test
    fun `should skip sending if recipient is already SENT or DELIVERED (idempotency)`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val campaign = EmailCampaign(
            id = campaignId,
            status = CampaignStatus.PROCESSING,
            totalRecipients = 1
        )
        whenever(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign))

        // Claim returns 0 because row is not in PENDING/QUEUED
        whenever(recipientRepository.claimForProcessing(eq(recipientId), any(), any(), any())).thenReturn(0)

        // Existing recipient is already SENT
        val existingRecipient = EmailRecipient(
            id = recipientId,
            campaign = campaign,
            email = "already_sent@example.com",
            status = RecipientStatus.SENT
        )
        whenever(recipientRepository.findById(recipientId)).thenReturn(Optional.of(existingRecipient))

        val job = EmailJobMessage(
            campaignId = campaignId,
            batchIndex = 0,
            recipients = listOf(
                EmailJobRecipient(
                    recipientId = recipientId,
                    email = "already_sent@example.com",
                    name = "Alex"
                )
            )
        )

        service.processJob(job)

        // SES should NOT be called
        verify(sesEmailService, never()).sendEmail(any())
        verify(recipientRepository, never()).markAsSent(any(), any(), any(), any(), any())
    }

    @Test
    fun `should abort and not send emails if campaign is CANCELLED`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val campaign = EmailCampaign(
            id = campaignId,
            status = CampaignStatus.CANCELLED,
            totalRecipients = 5
        )
        whenever(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign))

        val job = EmailJobMessage(
            campaignId = campaignId,
            batchIndex = 0,
            recipients = listOf(
                EmailJobRecipient(
                    recipientId = recipientId,
                    email = "test@example.com"
                )
            )
        )

        service.processJob(job)

        verify(sesEmailService, never()).sendEmail(any())
        verify(recipientRepository, never()).claimForProcessing(any(), any(), any(), any())
    }

    @Test
    fun `should throw exception on transient failure so SQS message is redelivered`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val campaign = EmailCampaign(id = campaignId, status = CampaignStatus.PROCESSING, totalRecipients = 1)
        whenever(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign))
        whenever(recipientRepository.claimForProcessing(eq(recipientId), any(), any(), any())).thenReturn(1)

        val transientFail = SendEmailResult(isSuccess = false, isTransientError = true, errorMessage = "Rate exceeded")
        whenever(sesEmailService.sendEmail(any())).thenReturn(transientFail)

        val job = EmailJobMessage(
            campaignId = campaignId,
            batchIndex = 0,
            recipients = listOf(EmailJobRecipient(recipientId = recipientId, email = "fail@example.com"))
        )

        assertThrows(IllegalStateException::class.java) {
            service.processJob(job)
        }

        // Reverts status back to QUEUED for next delivery attempt
        verify(recipientRepository).claimForProcessing(
            eq(recipientId),
            eq(RecipientStatus.QUEUED),
            eq(listOf(RecipientStatus.PROCESSING)),
            any()
        )
    }

    @Test
    fun `should complete campaign when all recipients reach terminal status across batches`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val campaign = EmailCampaign(
            id = campaignId,
            status = CampaignStatus.PROCESSING,
            totalRecipients = 5
        )
        whenever(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign))
        whenever(recipientRepository.claimForProcessing(eq(recipientId), any(), any(), any())).thenReturn(1)
        whenever(sesEmailService.sendEmail(any())).thenReturn(SendEmailResult(isSuccess = true, sesMessageId = "ses-1"))

        // Completed count reaches totalRecipients
        whenever(recipientRepository.countByCampaignIdAndStatusIn(eq(campaignId), any())).thenReturn(5L)
        whenever(recipientRepository.countByCampaignIdAndStatus(eq(campaignId), eq(RecipientStatus.FAILED))).thenReturn(0L)
        whenever(recipientRepository.countByCampaignIdAndStatus(eq(campaignId), eq(RecipientStatus.BOUNCED))).thenReturn(0L)

        val job = EmailJobMessage(
            campaignId = campaignId,
            batchIndex = 0,
            recipients = listOf(EmailJobRecipient(recipientId = recipientId, email = "done@example.com"))
        )

        service.processJob(job)

        verify(campaignRepository).updateCampaignCompletion(
            eq(campaignId),
            eq(CampaignStatus.COMPLETED),
            any(),
            any()
        )
    }

    @Test
    fun `should mark campaign as COMPLETED_WITH_ERRORS when some recipients failed permanently`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val campaign = EmailCampaign(
            id = campaignId,
            status = CampaignStatus.PROCESSING,
            totalRecipients = 5
        )
        whenever(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign))
        whenever(recipientRepository.claimForProcessing(eq(recipientId), any(), any(), any())).thenReturn(1)
        whenever(sesEmailService.sendEmail(any())).thenReturn(SendEmailResult(isSuccess = false, isTransientError = false, errorMessage = "Mailbox full"))

        // Completed count reaches totalRecipients
        whenever(recipientRepository.countByCampaignIdAndStatusIn(eq(campaignId), any())).thenReturn(5L)
        whenever(recipientRepository.countByCampaignIdAndStatus(eq(campaignId), eq(RecipientStatus.FAILED))).thenReturn(1L)
        whenever(recipientRepository.countByCampaignIdAndStatus(eq(campaignId), eq(RecipientStatus.BOUNCED))).thenReturn(0L)

        val job = EmailJobMessage(
            campaignId = campaignId,
            batchIndex = 0,
            recipients = listOf(EmailJobRecipient(recipientId = recipientId, email = "fail@example.com"))
        )

        service.processJob(job)

        verify(recipientRepository).markAsFailed(eq(recipientId), eq(RecipientStatus.FAILED), eq("Mailbox full"), any())
        verify(campaignRepository).incrementFailedCount(eq(campaignId), any())
        verify(campaignRepository).updateCampaignCompletion(
            eq(campaignId),
            eq(CampaignStatus.COMPLETED_WITH_ERRORS),
            any(),
            any()
        )
    }
}
