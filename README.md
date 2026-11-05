::: {align="center"}
# HireWithFinder --- Asynchronous Bulk Email System

**A serverless, queue-driven email processing architecture built with
Kotlin, Spring Boot, AWS SQS, AWS Lambda, and Amazon SES.**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Spring
Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![AWS
Lambda](https://img.shields.io/badge/AWS%20Lambda-FF9900?style=for-the-badge&logo=awslambda&logoColor=white)](https://aws.amazon.com/lambda/)
[![Amazon
SQS](https://img.shields.io/badge/Amazon%20SQS-FF4F8B?style=for-the-badge&logo=amazonsqs&logoColor=white)](https://aws.amazon.com/sqs/)
[![Amazon
SES](https://img.shields.io/badge/Amazon%20SES-DD344C?style=for-the-badge&logo=amazonaws&logoColor=white)](https://aws.amazon.com/ses/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
:::

------------------------------------------------------------------------

## Overview

This project implements an asynchronous bulk-email pipeline for
**HireWithFinder**.

Instead of sending campaign emails inside an API request, the backend
creates email jobs and publishes them to an **Amazon SQS FIFO queue**.
An independently deployed **AWS Lambda worker** consumes those jobs and
sends emails through **Amazon SES**.

This keeps campaign submission separate from email delivery and allows
the worker to process jobs independently.

## Architecture

``` mermaid
flowchart TD
    A[Admin Panel] --> B[HireWithFinder Backend API]
    B --> C[EmailCampaignService]
    C --> D[SqsEmailQueuePublisherService]
    D -->|EmailJobMessage JSON| E[Amazon SQS FIFO]
    E -->|SQS Event Source Mapping| F[AWS Lambda Worker]
    F --> G[EmailSqsLambdaHandler]
    G --> H[EmailProcessingService]
    H --> I[Amazon SES]
    I --> J[Recipient Inbox]
    H <--> K[(PostgreSQL / Supabase)]
```

## How it works

1.  **Campaign request** --- An administrator starts a campaign through
    the HireWithFinder API.
2.  **Job creation** --- `EmailCampaignService` selects queued
    recipients and groups them into jobs. The current application batch
    size is up to **25 recipients per job**.
3.  **Queue publishing** --- `SqsEmailQueuePublisherService` serializes
    each `EmailJobMessage` as JSON and publishes it to the FIFO queue.
4.  **Automatic Lambda invocation** --- An **SQS event source mapping**
    polls the queue. When messages are available, AWS invokes the
    `email-worker-service` Lambda and passes the SQS records to the
    handler. The backend does not directly invoke the worker Lambda.
5.  **Message processing** --- `EmailSqsLambdaHandler` deserializes each
    message and delegates it to `EmailProcessingService`.
6.  **Email delivery** --- The worker processes recipients, updates
    processing statuses in PostgreSQL/Supabase, and sends emails through
    Amazon SES.
7.  **Acknowledgement and retry** --- Successfully processed messages
    are acknowledged and removed from the queue. Failed records can be
    retried based on the event-source mapping and queue configuration.

## Key engineering decisions

  -----------------------------------------------------------------------
  Decision                            Purpose
  ----------------------------------- -----------------------------------
  Separate worker application         Keeps email processing
                                      independently deployable from the
                                      API backend

  SQS FIFO queue                      Provides message-group ordering for
                                      jobs in the same campaign

  Campaign ID as message group        Keeps messages for a campaign in
                                      the same FIFO message group

  SQS event source mapping            Lets AWS poll SQS and invoke the
                                      Lambda worker automatically

  Partial batch failure response      Allows failed SQS records to be
                                      retried without treating every
                                      record as successful

  PostgreSQL / Supabase               Persists campaign and recipient
                                      processing state

  Amazon SES                          Handles outbound email delivery
  -----------------------------------------------------------------------

### Important batch-size distinction

There are two levels of batching:

-   **Application batch:** up to 25 recipients are included in one
    `EmailJobMessage`.
-   **Lambda invocation batch:** the SQS event source mapping is
    configured to deliver up to 10 SQS messages in one invocation.

One SQS message can therefore represent a job containing multiple
recipients.

## Repositories / services

The solution is split into two independently deployable Spring Boot
applications:

  -----------------------------------------------------------------------
  Application                         Responsibility
  ----------------------------------- -----------------------------------
  `hirewithfinder-backend`            Campaign APIs, recipient selection,
                                      email-job creation, and publishing
                                      messages to SQS

  `email-worker-service`              Consuming SQS events, processing
                                      recipients, updating statuses, and
                                      sending email through SES
  -----------------------------------------------------------------------

## Technology stack

-   **Language:** Kotlin
-   **Framework:** Spring Boot
-   **Cloud:** AWS
-   **Queue:** Amazon SQS FIFO
-   **Compute:** AWS Lambda (Java 21 runtime)
-   **Email delivery:** Amazon Simple Email Service (SES)
-   **Database:** PostgreSQL / Supabase
-   **Build:** Gradle

## Configuration

Configure the worker through environment variables. Do not commit
credentials or production secrets.

  Variable                      Description
  ----------------------------- --------------------------------------
  `SPRING_PROFILES_ACTIVE`      Spring profile, for example `prod`
  `DB_URL`                      PostgreSQL JDBC connection URL
  `DB_USERNAME`                 Database username
  `DB_PASSWORD`                 Database password
  `AWS_REGION`                  AWS region, for example `ap-south-1`
  `AWS_SQS_QUEUE_URL`           URL of the SQS FIFO queue
  `AWS_SES_MAX_SEND_RATE`       Configured SES sending-rate limit
  `AWS_SES_CONFIGURATION_SET`   Optional SES configuration set

The Lambda execution role needs permissions for the configured SQS event
source mapping and the SES operations used by the worker. Database
connectivity must also be available from the Lambda runtime.

## Build

From the `email-worker-service` project directory:

``` bash
./gradlew clean build
```

Build the deployable AWS Lambda artifact using the project's configured
Shadow JAR task:

``` bash
./gradlew clean shadowJar
```

The generated artifact used in this setup is:

``` text
build/libs/email-worker-service-aws.jar
```

Deploy the artifact to the `email-worker-service` Lambda and configure
its handler as:

``` text
com.iota.email_worker_service.EmailSqsLambdaHandler
```

The handler class name and artifact name should match the actual project
configuration if these are changed.

## Reliability and operational considerations

-   Configure an **SQS Dead-Letter Queue (DLQ)** and a suitable maximum
    receive count for messages that repeatedly fail.
-   Keep recipient processing idempotent so a retried message does not
    send duplicate emails.
-   Monitor Lambda errors, duration, throttles, SQS queue depth,
    oldest-message age, and SES bounce/complaint metrics.
-   Keep SQS visibility timeout longer than the expected processing time
    for a message batch.
-   Protect database credentials and AWS configuration; never commit
    `.env` files, access keys, or passwords.
-   Validate recipient addresses and handle permanent delivery failures
    appropriately.

## What this demonstrates

-   Designing an asynchronous backend workflow.
-   Decoupling API operations from background processing.
-   Integrating Spring Boot and Kotlin with AWS managed services.
-   Building an independently deployable AWS Lambda worker.
-   Processing queue messages with failure-aware handling.
-   Persisting campaign and recipient state in PostgreSQL.

------------------------------------------------------------------------

::: {align="center"}
**Built as part of the HireWithFinder backend engineering project.**
:::
