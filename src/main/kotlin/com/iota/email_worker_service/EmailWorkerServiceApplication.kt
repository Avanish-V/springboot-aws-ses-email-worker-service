package com.iota.email_worker_service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EmailWorkerServiceApplication

fun main(args: Array<String>) {
	runApplication<EmailWorkerServiceApplication>(*args)
}
