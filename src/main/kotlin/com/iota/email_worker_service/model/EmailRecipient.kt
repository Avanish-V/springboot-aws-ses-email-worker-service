package com.iota.email_worker_service.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "email_recipients",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_campaign_recipient_email", columnNames = ["campaign_id", "email"])
    ],
    indexes = [
        Index(name = "idx_email_recipients_campaign_id", columnList = "campaign_id"),
        Index(name = "idx_email_recipients_campaign_status", columnList = "campaign_id, status"),
        Index(name = "idx_email_recipients_ses_msg_id", columnList = "ses_message_id"),
        Index(name = "idx_email_recipients_email", columnList = "email")
    ]
)
class EmailRecipient(
    @Id
    var id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    var campaign: EmailCampaign,

    @Column(name = "email", nullable = false)
    var email: String,

    @Column(name = "name")
    var name: String? = null,

    @Column(name = "metadata", columnDefinition = "TEXT")
    var metadataJson: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: RecipientStatus = RecipientStatus.PENDING,

    @Column(name = "ses_message_id")
    var sesMessageId: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }
}

