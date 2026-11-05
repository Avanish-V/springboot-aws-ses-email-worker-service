package com.iota.email_worker_service.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sqs.SqsClient

@Configuration
class AwsConfig {

    @Value("\${aws.region:ap-south-1}")
    val regionString: String = "ap-south-1"

    @Value("\${aws.s3.bucket-name:campuscircle}")
    val bucketName: String = "campuscircle"

    @Value("\${aws.ses.configuration-set:}")
    val configurationSet: String = ""

    @Value("\${aws.ses.max-send-rate:14}")
    val maxSendRate: Int = 14

    private fun region(): Region = Region.of(regionString)

    @Bean
    fun credentialsProvider(): DefaultCredentialsProvider {
        return DefaultCredentialsProvider.create()
    }

    @Bean
    fun sesV2Client(credentialsProvider: DefaultCredentialsProvider): SesV2Client {
        return SesV2Client.builder()
            .region(region())
            .credentialsProvider(credentialsProvider)
            .build()
    }

    @Bean
    fun s3Client(credentialsProvider: DefaultCredentialsProvider): S3Client {
        return S3Client.builder()
            .region(region())
            .credentialsProvider(credentialsProvider)
            .build()
    }

    @Bean
    fun sqsClient(credentialsProvider: DefaultCredentialsProvider): SqsClient {
        return SqsClient.builder()
            .region(region())
            .credentialsProvider(credentialsProvider)
            .build()
    }

    @Bean
    fun objectMapper(): ObjectMapper {
        return jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
}

