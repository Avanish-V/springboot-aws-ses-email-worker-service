package com.iota.email_worker_service.service

import com.iota.email_worker_service.dto.EmailJobMessage

interface EmailProcessingService {
    /**
     * Processes an SQS email job message batch.
     * Throws an exception if any transient failure occurs so the SQS message
     * can be safely retried without duplicating already-sent emails.
     */
    fun processJob(job: EmailJobMessage)
}

