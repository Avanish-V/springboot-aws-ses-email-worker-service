package com.iota.email_worker_service

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.iota.email_worker_service.dto.EmailJobMessage
import com.iota.email_worker_service.dto.EmailJobRecipient
import com.iota.email_worker_service.service.EmailProcessingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.context.ConfigurableApplicationContext
import java.lang.reflect.Field
import java.util.UUID

class EmailSqsLambdaHandlerTest {

    private lateinit var handler: EmailSqsLambdaHandler
    private lateinit var mockProcessingService: EmailProcessingService
    private lateinit var mockSpringContext: ConfigurableApplicationContext
    private lateinit var mockLambdaContext: Context
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        handler = EmailSqsLambdaHandler()
        mockProcessingService = mock()
        mockSpringContext = mock()
        mockLambdaContext = mock()

        whenever(mockSpringContext.getBean(EmailProcessingService::class.java)).thenReturn(mockProcessingService)
        whenever(mockSpringContext.getBean(ObjectMapper::class.java)).thenReturn(objectMapper)

        // Inject mock Spring context via reflection
        val contextField: Field = EmailSqsLambdaHandler::class.java.getDeclaredField("applicationContext")
        contextField.isAccessible = true
        contextField.set(null, mockSpringContext)
    }

    @AfterEach
    fun tearDown() {
        val contextField: Field = EmailSqsLambdaHandler::class.java.getDeclaredField("applicationContext")
        contextField.isAccessible = true
        contextField.set(null, null)
    }

    @Test
    fun `should process all SQS records successfully without batch item failures`() {
        val campaignId = UUID.randomUUID()
        val job = EmailJobMessage(campaignId = campaignId, batchIndex = 0, recipients = listOf(EmailJobRecipient(UUID.randomUUID(), "a@b.com")))
        val record = SQSEvent.SQSMessage().apply {
            messageId = "msg-001"
            body = objectMapper.writeValueAsString(job)
        }

        val event = SQSEvent().apply {
            records = listOf(record)
        }

        val response: SQSBatchResponse = handler.handleRequest(event, mockLambdaContext)

        assertTrue(response.batchItemFailures.isEmpty())
        verify(mockProcessingService, times(1)).processJob(check {
            assertEquals(campaignId, it.campaignId)
            assertEquals(0, it.batchIndex)
        })
    }

    @Test
    fun `should stop immediately on failure and add failed record plus all remaining records to batchItemFailures`() {
        val job1 = EmailJobMessage(campaignId = UUID.randomUUID(), batchIndex = 0, recipients = listOf(EmailJobRecipient(UUID.randomUUID(), "ok@b.com")))
        val job2 = EmailJobMessage(campaignId = UUID.randomUUID(), batchIndex = 1, recipients = listOf(EmailJobRecipient(UUID.randomUUID(), "fail@b.com")))
        val job3 = EmailJobMessage(campaignId = UUID.randomUUID(), batchIndex = 2, recipients = listOf(EmailJobRecipient(UUID.randomUUID(), "unprocessed@b.com")))

        val record1 = SQSEvent.SQSMessage().apply {
            messageId = "msg-success"
            body = objectMapper.writeValueAsString(job1)
        }
        val record2 = SQSEvent.SQSMessage().apply {
            messageId = "msg-fail"
            body = objectMapper.writeValueAsString(job2)
        }
        val record3 = SQSEvent.SQSMessage().apply {
            messageId = "msg-unprocessed"
            body = objectMapper.writeValueAsString(job3)
        }

        doNothing().whenever(mockProcessingService).processJob(argThat { batchIndex == 0 })
        doThrow(IllegalStateException("Transient rate limit")).whenever(mockProcessingService).processJob(argThat { batchIndex == 1 })

        val event = SQSEvent().apply {
            records = listOf(record1, record2, record3)
        }

        val response = handler.handleRequest(event, mockLambdaContext)

        // Must fail record 2 AND record 3 to preserve strict FIFO ordering
        assertEquals(2, response.batchItemFailures.size)
        assertEquals("msg-fail", response.batchItemFailures[0].itemIdentifier)
        assertEquals("msg-unprocessed", response.batchItemFailures[1].itemIdentifier)

        // Job 3 must NEVER have been called
        verify(mockProcessingService, never()).processJob(argThat { batchIndex == 2 })
    }

    @Test
    fun `should treat blank message body as failure and stop processing remaining records`() {
        val record1 = SQSEvent.SQSMessage().apply {
            messageId = "msg-blank"
            body = "   "
        }
        val record2 = SQSEvent.SQSMessage().apply {
            messageId = "msg-after-blank"
            body = "{}"
        }

        val event = SQSEvent().apply {
            records = listOf(record1, record2)
        }

        val response = handler.handleRequest(event, mockLambdaContext)

        assertEquals(2, response.batchItemFailures.size)
        assertEquals("msg-blank", response.batchItemFailures[0].itemIdentifier)
        assertEquals("msg-after-blank", response.batchItemFailures[1].itemIdentifier)

        verify(mockProcessingService, never()).processJob(any())
    }
}
