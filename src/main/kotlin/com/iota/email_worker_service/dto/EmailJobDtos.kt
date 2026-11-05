package com.iota.email_worker_service.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmailJobMessage(
    val campaignId: UUID,
    val batchIndex: Int,
    val recipients: List<EmailJobRecipient> = emptyList(),
    val subject: String = "",
    val htmlContent: String = "",
    val fromName: String = "",
    val fromEmail: String = "",
    val replyTo: String? = null,
    val attachments: List<EmailJobAttachment> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmailJobRecipient(
    val recipientId: UUID,
    val email: String,
    val name: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmailJobAttachment(
    val fileName: String,
    val s3Key: String,
    val contentType: String
)

