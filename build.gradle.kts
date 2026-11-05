plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	kotlin("plugin.jpa") version "1.9.25"

	id("org.springframework.boot") version "3.3.5"
	id("io.spring.dependency-management") version "1.1.6"
	id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.iota"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.postgresql:postgresql")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// AWS Lambda Core and Events
	implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
	implementation("com.amazonaws:aws-lambda-java-events:3.14.0")

	// AWS SDK (SES, S3, SQS)
	implementation(platform("software.amazon.awssdk:bom:2.29.26"))
	implementation("software.amazon.awssdk:sesv2")
	implementation("software.amazon.awssdk:s3")
	implementation("software.amazon.awssdk:sqs")

	// MIME handling for emails with attachments
	implementation("jakarta.mail:jakarta.mail-api:2.1.3")
	implementation("org.eclipse.angus:jakarta.mail:2.0.3")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("com.h2database:h2")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
	archiveClassifier.set("aws")
	archiveVersion.set("")

	mergeServiceFiles()

	transform(
		com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer::class.java
	) {
		paths.add("META-INF/spring.factories")
		mergeStrategy = "append"
	}

	append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
}

tasks.shadowJar {
	isZip64 = true
}

tasks.assemble {
	dependsOn(tasks.shadowJar)
}
