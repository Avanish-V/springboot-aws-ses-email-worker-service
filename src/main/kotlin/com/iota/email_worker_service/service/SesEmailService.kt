package com.iota.email_worker_service.service

import com.iota.email_worker_service.dto.EmailJobAttachment
import java.util.UUID

data class SendEmailDto(
    val campaignId: UUID,
    val recipientId: UUID,
    val toEmail: String,
    val fromName: String,
    val fromEmail: String,
    val replyTo: String?,
    val subject: String,
    val htmlBody: String,
    val attachments: List<EmailJobAttachment> = emptyList()
)

data class SendEmailResult(
    val isSuccess: Boolean,
    val sesMessageId: String? = null,
    val isTransientError: Boolean = false,
    val errorMessage: String? = null
)

interface SesEmailService {
    fun sendEmail(dto: SendEmailDto): SendEmailResult
}

