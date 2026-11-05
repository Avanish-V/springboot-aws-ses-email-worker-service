package com.iota.email_worker_service.service.impl

import com.iota.email_worker_service.config.AwsConfig
import com.iota.email_worker_service.dto.EmailJobAttachment
import com.iota.email_worker_service.service.SendEmailDto
import com.iota.email_worker_service.service.SendEmailResult
import com.iota.email_worker_service.service.SesEmailService
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.*
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

@Service
class SesEmailServiceImpl(
    private val sesV2Client: SesV2Client,
    private val s3Client: S3Client,
    private val awsConfig: AwsConfig
) : SesEmailService {

    private val log = LoggerFactory.getLogger(SesEmailServiceImpl::class.java)

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_BACKOFF_MS = 500L
    }

    private val lastSendTimestamp = AtomicLong(0L)

    override fun sendEmail(dto: SendEmailDto): SendEmailResult {
        throttleRate()

        var attempt = 0
        var lastException: Exception? = null

        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                val messageId = if (dto.attachments.isEmpty()) {
                    sendSimpleEmail(dto)
                } else {
                    sendRawEmailWithAttachments(dto)
                }

                log.info("Sent email to [{}] for campaign [{}], messageId={}", dto.toEmail, dto.campaignId, messageId)
                return SendEmailResult(isSuccess = true, sesMessageId = messageId)
            } catch (e: TooManyRequestsException) {
                lastException = e
                log.warn("SES rate limit exceeded sending to [{}], attempt {}/{}", dto.toEmail, attempt, MAX_RETRIES)
                sleepBackoff(attempt)
            } catch (e: LimitExceededException) {
                lastException = e
                log.warn("SES daily sending quota exceeded sending to [{}], attempt {}/{}", dto.toEmail, attempt, MAX_RETRIES)
                sleepBackoff(attempt)
            } catch (e: SdkClientException) {
                lastException = e
                log.warn("Network/client timeout communicating with SES for [{}], attempt {}/{}", dto.toEmail, attempt, MAX_RETRIES)
                sleepBackoff(attempt)
            } catch (e: AccountSuspendedException) {
                log.error("SES Account suspended: {}", e.message)
                return SendEmailResult(isSuccess = false, isTransientError = false, errorMessage = "SES Account Suspended: ${e.message}")
            } catch (e: MailFromDomainNotVerifiedException) {
                log.error("SES MAIL FROM domain not verified: {}", e.message)
                return SendEmailResult(isSuccess = false, isTransientError = false, errorMessage = "MailFromDomainNotVerified: ${e.message}")
            } catch (e: BadRequestException) {
                log.error("SES Bad request for recipient [{}]: {}", dto.toEmail, e.message)
                return SendEmailResult(isSuccess = false, isTransientError = false, errorMessage = "BadRequest: ${e.message}")
            } catch (e: NotFoundException) {
                log.error("SES resource not found: {}", e.message)
                return SendEmailResult(isSuccess = false, isTransientError = false, errorMessage = "NotFound: ${e.message}")
            } catch (e: Exception) {
                log.error("Unexpected error sending email to [{}]: {}", dto.toEmail, e.message, e)
                return SendEmailResult(isSuccess = false, isTransientError = false, errorMessage = e.message ?: "Unknown SES error")
            }
        }

        val errMsg = "Exceeded max SES retries ($MAX_RETRIES): ${lastException?.message}"
        log.error("Transient error sending email to [{}]: {}", dto.toEmail, errMsg)
        return SendEmailResult(isSuccess = false, isTransientError = true, errorMessage = errMsg)
    }

    private fun sendSimpleEmail(dto: SendEmailDto): String {
        val sender = formatSender(dto.fromName, dto.fromEmail)
        val destination = Destination.builder().toAddresses(dto.toEmail.trim()).build()

        val content = EmailContent.builder()
            .simple(
                software.amazon.awssdk.services.sesv2.model.Message.builder()
                    .subject(Content.builder().data(dto.subject.ifBlank { "(No Subject)" }).charset("UTF-8").build())
                    .body(Body.builder().html(Content.builder().data(dto.htmlBody.ifBlank { "<p></p>" }).charset("UTF-8").build()).build())
                    .build()
            )
            .build()

        val requestBuilder = SendEmailRequest.builder()
            .fromEmailAddress(sender)
            .destination(destination)
            .content(content)
            .emailTags(
                listOf(
                    MessageTag.builder().name("CampaignId").value(dto.campaignId.toString()).build(),
                    MessageTag.builder().name("RecipientId").value(dto.recipientId.toString()).build()
                )
            )

        if (!dto.replyTo.isNullOrBlank()) {
            requestBuilder.replyToAddresses(dto.replyTo.trim())
        }

        if (awsConfig.configurationSet.isNotBlank()) {
            requestBuilder.configurationSetName(awsConfig.configurationSet.trim())
        }

        val response = sesV2Client.sendEmail(requestBuilder.build())
        return response.messageId()
    }

    private fun sendRawEmailWithAttachments(dto: SendEmailDto): String {
        val session = Session.getDefaultInstance(Properties())
        val mimeMessage = MimeMessage(session)

        val fromAddress = if (dto.fromName.isNotBlank()) {
            InternetAddress(dto.fromEmail.trim(), dto.fromName.trim(), "UTF-8")
        } else {
            InternetAddress(dto.fromEmail.trim())
        }
        mimeMessage.setFrom(fromAddress)
        mimeMessage.setRecipient(Message.RecipientType.TO, InternetAddress(dto.toEmail.trim()))
        mimeMessage.subject = dto.subject.ifBlank { "(No Subject)" }

        if (!dto.replyTo.isNullOrBlank()) {
            mimeMessage.replyTo = arrayOf(InternetAddress(dto.replyTo.trim()))
        }

        val rootMultipart = MimeMultipart("mixed")

        // HTML Part
        val htmlPart = MimeBodyPart()
        htmlPart.setContent(dto.htmlBody.ifBlank { "<p></p>" }, "text/html; charset=UTF-8")
        rootMultipart.addBodyPart(htmlPart)

        // Attachments
        for (attachment in dto.attachments) {
            val attachmentBytes = fetchAttachmentFromS3(attachment)
            val attachmentPart = MimeBodyPart()
            val dataSource = ByteArrayDataSource(attachmentBytes, attachment.contentType)
            attachmentPart.dataHandler = jakarta.activation.DataHandler(dataSource)
            attachmentPart.fileName = attachment.fileName
            attachmentPart.setHeader("Content-Disposition", "attachment; filename=\"${attachment.fileName}\"")
            rootMultipart.addBodyPart(attachmentPart)
        }

        mimeMessage.setContent(rootMultipart)

        val outputStream = ByteArrayOutputStream()
        mimeMessage.writeTo(outputStream)
        val rawMessageBytes = outputStream.toByteArray()

        val rawContent = EmailContent.builder()
            .raw(RawMessage.builder().data(SdkBytes.fromByteArray(rawMessageBytes)).build())
            .build()

        val requestBuilder = SendEmailRequest.builder()
            .content(rawContent)
            .emailTags(
                listOf(
                    MessageTag.builder().name("CampaignId").value(dto.campaignId.toString()).build(),
                    MessageTag.builder().name("RecipientId").value(dto.recipientId.toString()).build()
                )
            )

        if (awsConfig.configurationSet.isNotBlank()) {
            requestBuilder.configurationSetName(awsConfig.configurationSet.trim())
        }

        val response = sesV2Client.sendEmail(requestBuilder.build())
        return response.messageId()
    }

    private fun fetchAttachmentFromS3(attachment: EmailJobAttachment): ByteArray {
        val bucket = awsConfig.bucketName
        log.info("Fetching attachment [{}] from bucket [{}] key [{}]", attachment.fileName, bucket, attachment.s3Key)
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(attachment.s3Key)
            .build()
        val s3Object = s3Client.getObject(getObjectRequest)
        return s3Object.readAllBytes()
    }

    private fun formatSender(fromName: String, fromEmail: String): String {
        return if (fromName.isNotBlank()) {
            "\"${fromName.trim()}\" <${fromEmail.trim()}>"
        } else {
            fromEmail.trim()
        }
    }

    private fun throttleRate() {
        val rate = awsConfig.maxSendRate
        if (rate <= 0) return

        val minIntervalMs = 1000L / rate
        val now = System.currentTimeMillis()
        val last = lastSendTimestamp.get()
        val elapsed = now - last

        if (elapsed < minIntervalMs) {
            try {
                Thread.sleep(minIntervalMs - elapsed)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastSendTimestamp.set(System.currentTimeMillis())
    }

    private fun sleepBackoff(attempt: Int) {
        val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1))
        val jitter = ThreadLocalRandom.current().nextLong(50, 200)
        try {
            Thread.sleep(backoff + jitter)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

