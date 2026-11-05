package com.iota.email_worker_service.model

enum class RecipientStatus {
    PENDING,
    QUEUED,
    PROCESSING,
    SENT,
    DELIVERED,
    FAILED,
    BOUNCED,
    COMPLAINED,
    DELIVERY_DELAYED
}

