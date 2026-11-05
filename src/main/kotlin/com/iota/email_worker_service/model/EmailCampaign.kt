package com.iota.email_worker_service.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "email_campaigns",
    indexes = [
        Index(name = "idx_email_campaigns_status", columnList = "status"),
        Index(name = "idx_email_campaigns_created_by", columnList = "created_by"),
        Index(name = "idx_email_campaigns_created_at", columnList = "created_at")
    ]
)
class EmailCampaign(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "subject", nullable = false, length = 500)
    var subject: String = "",

    @Column(name = "html_content", nullable = false, columnDefinition = "TEXT")
    var htmlContent: String = "",

    @Column(name = "from_name", nullable = false)
    var fromName: String = "",

    @Column(name = "from_email", nullable = false)
    var fromEmail: String = "",

    @Column(name = "reply_to")
    var replyTo: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: CampaignStatus = CampaignStatus.DRAFT,

    @Column(name = "total_recipients", nullable = false)
    var totalRecipients: Int = 0,

    @Column(name = "sent_count", nullable = false)
    var sentCount: Int = 0,

    @Column(name = "delivered_count", nullable = false)
    var deliveredCount: Int = 0,

    @Column(name = "failed_count", nullable = false)
    var failedCount: Int = 0,

    @Column(name = "bounced_count", nullable = false)
    var bouncedCount: Int = 0,

    @Column(name = "complained_count", nullable = false)
    var complainedCount: Int = 0,

    @Column(name = "csv_file_url", length = 1000)
    var csvFileUrl: String? = null,

    @Column(name = "csv_s3_key", length = 500)
    var csvS3Key: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "created_by", nullable = false)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }
}

