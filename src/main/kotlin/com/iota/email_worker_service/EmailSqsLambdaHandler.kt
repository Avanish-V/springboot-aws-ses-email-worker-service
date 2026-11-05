package com.iota.email_worker_service

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.iota.email_worker_service.dto.EmailJobMessage
import com.iota.email_worker_service.service.EmailProcessingService
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * AWS Lambda Worker Handler for SQS FIFO Queue events.
 *
 * Configured in AWS Lambda as:
 * Handler: com.iota.email_worker_service.EmailSqsLambdaHandler
 *      or: com.iota.email_worker_service.EmailSqsLambdaHandler::handleRequest
 */
class EmailSqsLambdaHandler : RequestHandler<SQSEvent, SQSBatchResponse> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailSqsLambdaHandler::class.java)

        @Volatile
        private var applicationContext: ConfigurableApplicationContext? = null

        fun getOrCreateContext(): ConfigurableApplicationContext {
            return applicationContext ?: synchronized(this) {
                applicationContext ?: try {
                    log.info("Initializing Spring Boot application context for EmailSqsLambdaHandler...")
                    SpringApplicationBuilder(EmailWorkerServiceApplication::class.java)
                        .web(WebApplicationType.NONE)
                        .run()
                        .also { applicationContext = it }
                } catch (e: Throwable) {
                    log.error("FATAL: Failed to initialize Spring Boot application for EmailSqsLambdaHandler", e)
                    throw IllegalStateException("Could not initialize Spring Boot application", e)
                }
            }
        }
    }

    override fun handleRequest(event: SQSEvent, context: Context): SQSBatchResponse {
        val springContext = getOrCreateContext()
        val emailProcessingService = springContext.getBean(EmailProcessingService::class.java)
        val objectMapper = springContext.getBean(ObjectMapper::class.java)

        val batchItemFailures = mutableListOf<SQSBatchResponse.BatchItemFailure>()
        val records = event.records ?: emptyList()
        log.info("EmailSqsLambdaHandler received {} SQS records", records.size)

        for ((index, record) in records.withIndex()) {
            val messageId = record.messageId
            try {
                val body = record.body
                if (body.isNullOrBlank()) {
                    log.error("SQS record messageId={} has empty or blank body. Treating as failure.", messageId)
                    addFailureAndRemaining(records, index, batchItemFailures)
                    break
                }

                val job = objectMapper.readValue(body, EmailJobMessage::class.java)
                log.info("Processing SQS message: messageId={}, campaignId={}, batchIndex={}, recipients={}",
                    messageId, job.campaignId, job.batchIndex, job.recipients.size)

                emailProcessingService.processJob(job)
                log.info("Successfully processed SQS message: messageId={}, campaignId={}, batchIndex={}",
                    messageId, job.campaignId, job.batchIndex)
            } catch (e: Exception) {
                log.error("Failed to process SQS record messageId={}: {}. Stopping FIFO batch processing.",
                    messageId, e.message, e)
                addFailureAndRemaining(records, index, batchItemFailures)
                break
            }
        }

        return SQSBatchResponse(batchItemFailures)
    }

    private fun addFailureAndRemaining(
        records: List<SQSEvent.SQSMessage>,
        failedIndex: Int,
        batchItemFailures: MutableList<SQSBatchResponse.BatchItemFailure>
    ) {
        for (i in failedIndex until records.size) {
            val record = records[i]
            batchItemFailures.add(SQSBatchResponse.BatchItemFailure(record.messageId))
        }
    }
}
