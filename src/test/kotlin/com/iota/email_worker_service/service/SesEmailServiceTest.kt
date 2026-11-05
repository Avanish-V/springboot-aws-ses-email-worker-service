package com.iota.email_worker_service.service

import com.iota.email_worker_service.config.AwsConfig
import com.iota.email_worker_service.dto.EmailJobAttachment
import com.iota.email_worker_service.service.impl.SesEmailServiceImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.*
import java.io.ByteArrayInputStream
import java.util.UUID

class SesEmailServiceTest {

    private lateinit var sesV2Client: SesV2Client
    private lateinit var s3Client: S3Client
    private lateinit var awsConfig: AwsConfig
    private lateinit var service: SesEmailServiceImpl

    @BeforeEach
    fun setUp() {
        sesV2Client = mock(SesV2Client::class.java)
        s3Client = mock(S3Client::class.java)
        awsConfig = AwsConfig()

        service = SesEmailServiceImpl(sesV2Client, s3Client, awsConfig)
    }

    @Test
    fun `should send simple email without attachments successfully`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val mockResponse = SendEmailResponse.builder().messageId("ses-msg-12345").build()
        `when`(sesV2Client.sendEmail(any(SendEmailRequest::class.java))).thenReturn(mockResponse)

        val dto = SendEmailDto(
            campaignId = campaignId,
            recipientId = recipientId,
            toEmail = "jane@example.com",
            fromName = "HireWithFinder",
            fromEmail = "noreply@hirewithfinder.com",
            replyTo = "contact@hirewithfinder.com",
            subject = "Hello Candidate",
            htmlBody = "<h1>Welcome</h1>"
        )

        val result = service.sendEmail(dto)

        assertTrue(result.isSuccess)
        assertEquals("ses-msg-12345", result.sesMessageId)

        val captor = ArgumentCaptor.forClass(SendEmailRequest::class.java)
        verify(sesV2Client).sendEmail(captor.capture())
        val req = captor.value

        assertEquals("\"HireWithFinder\" <noreply@hirewithfinder.com>", req.fromEmailAddress())
        assertEquals(listOf("contact@hirewithfinder.com"), req.replyToAddresses())
        assertEquals(listOf("jane@example.com"), req.destination().toAddresses())
    }

    @Test
    fun `should send raw email with attachments fetched from S3`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        val attachmentBytes = "Sample PDF content".toByteArray()
        val s3Response = GetObjectResponse.builder().contentLength(attachmentBytes.size.toLong()).build()
        val responseInputStream = ResponseInputStream(s3Response, AbortableInputStream.create(ByteArrayInputStream(attachmentBytes)))

        `when`(s3Client.getObject(any(GetObjectRequest::class.java))).thenReturn(responseInputStream)

        val mockResponse = SendEmailResponse.builder().messageId("ses-raw-123").build()
        `when`(sesV2Client.sendEmail(any(SendEmailRequest::class.java))).thenReturn(mockResponse)

        val dto = SendEmailDto(
            campaignId = campaignId,
            recipientId = recipientId,
            toEmail = "jane@example.com",
            fromName = "HireWithFinder",
            fromEmail = "noreply@hirewithfinder.com",
            replyTo = null,
            subject = "Offer Letter",
            htmlBody = "<p>Please find attached your offer.</p>",
            attachments = listOf(
                EmailJobAttachment(
                    fileName = "OfferLetter.pdf",
                    s3Key = "campaigns/123/OfferLetter.pdf",
                    contentType = "application/pdf"
                )
            )
        )

        val result = service.sendEmail(dto)

        assertTrue(result.isSuccess)
        assertEquals("ses-raw-123", result.sesMessageId)
        verify(s3Client).getObject(any(GetObjectRequest::class.java))
        verify(sesV2Client).sendEmail(any(SendEmailRequest::class.java))
    }

    @Test
    fun `should retry on SES throttling and return transient error if exhausted`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        `when`(sesV2Client.sendEmail(any(SendEmailRequest::class.java)))
            .thenThrow(TooManyRequestsException.builder().message("Rate exceeded").build())

        val dto = SendEmailDto(
            campaignId = campaignId,
            recipientId = recipientId,
            toEmail = "jane@example.com",
            fromName = "HireWithFinder",
            fromEmail = "noreply@hirewithfinder.com",
            replyTo = null,
            subject = "Test",
            htmlBody = "<p>Test</p>"
        )

        val result = service.sendEmail(dto)

        assertFalse(result.isSuccess)
        assertTrue(result.isTransientError)
        verify(sesV2Client, times(3)).sendEmail(any(SendEmailRequest::class.java))
    }

    @Test
    fun `should not retry on permanent SES error like AccountSuspendedException`() {
        val campaignId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()

        `when`(sesV2Client.sendEmail(any(SendEmailRequest::class.java)))
            .thenThrow(AccountSuspendedException.builder().message("Suspended").build())

        val dto = SendEmailDto(
            campaignId = campaignId,
            recipientId = recipientId,
            toEmail = "jane@example.com",
            fromName = "HireWithFinder",
            fromEmail = "noreply@hirewithfinder.com",
            replyTo = null,
            subject = "Test",
            htmlBody = "<p>Test</p>"
        )

        val result = service.sendEmail(dto)

        assertFalse(result.isSuccess)
        assertFalse(result.isTransientError)
        // Should only be attempted once, no retries
        verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest::class.java))
    }
}

