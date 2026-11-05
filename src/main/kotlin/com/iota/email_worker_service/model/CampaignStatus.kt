package com.iota.email_worker_service.model

enum class CampaignStatus {
    DRAFT,
    VALIDATING,
    READY,
    QUEUED,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED
}

