package com.iota.email_worker_service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.iota.email_worker_service.dto.EmailJobAttachment
import com.iota.email_worker_service.dto.EmailJobMessage
import com.iota.email_worker_service.dto.EmailJobRecipient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ContractCompatibilityTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `should deserialize exact JSON contract produced by hirewithfinder backend`() {
        val sampleJson = """
        {
          "campaignId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
          "batchIndex": 0,
          "recipients": [
            {
              "recipientId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
              "email": "candidate@example.com",
              "name": "Jane Candidate",
              "metadata": {
                "role": "Lead Architect",
                "team": "Core Platform"
              }
            }
          ],
          "subject": "Invitation for {{role}}",
          "htmlContent": "<p>Hello {{name}}, join our {{team}}!</p>",
          "fromName": "HireWithFinder Recruitment",
          "fromEmail": "recruiter@hirewithfinder.com",
          "replyTo": "careers@hirewithfinder.com",
          "attachments": [
            {
              "fileName": "JobDescription.pdf",
              "s3Key": "campaigns/9b1deb4d/JobDescription.pdf",
              "contentType": "application/pdf"
            }
          ]
        }
        """.trimIndent()

        val message = objectMapper.readValue(sampleJson, EmailJobMessage::class.java)

        assertEquals(UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"), message.campaignId)
        assertEquals(0, message.batchIndex)
        assertEquals(1, message.recipients.size)

        val recipient = message.recipients[0]
        assertEquals(UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"), recipient.recipientId)
        assertEquals("candidate@example.com", recipient.email)
        assertEquals("Jane Candidate", recipient.name)
        assertEquals("Lead Architect", recipient.metadata["role"])
        assertEquals("Core Platform", recipient.metadata["team"])

        assertEquals("Invitation for {{role}}", message.subject)
        assertEquals("<p>Hello {{name}}, join our {{team}}!</p>", message.htmlContent)
        assertEquals("HireWithFinder Recruitment", message.fromName)
        assertEquals("recruiter@hirewithfinder.com", message.fromEmail)
        assertEquals("careers@hirewithfinder.com", message.replyTo)

        assertEquals(1, message.attachments.size)
        val attachment = message.attachments[0]
        assertEquals("JobDescription.pdf", attachment.fileName)
        assertEquals("campaigns/9b1deb4d/JobDescription.pdf", attachment.s3Key)
        assertEquals("application/pdf", attachment.contentType)
    }

    @Test
    fun `should tolerate extra unknown fields in JSON for forward compatibility`() {
        val jsonWithExtraFields = """
        {
          "campaignId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
          "batchIndex": 1,
          "recipients": [],
          "subject": "Test",
          "htmlContent": "<p>Test</p>",
          "fromName": "Sender",
          "fromEmail": "sender@test.com",
          "unknownFutureField": "futureValue"
        }
        """.trimIndent()

        val message = objectMapper.readValue(jsonWithExtraFields, EmailJobMessage::class.java)
        assertNotNull(message)
        assertEquals(1, message.batchIndex)
    }
}

