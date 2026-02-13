# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

- **Build**: `./gradlew build`
- **Run**: `./gradlew bootRun`
- **Test (all)**: `./gradlew test`
- **Test (single class)**: `./gradlew test --tests "com.settlement.SomeTestClass"`
- **Test (single method)**: `./gradlew test --tests "com.settlement.SomeTestClass.methodName"`
- **Clean**: `./gradlew clean`

## Tech Stack

- Java 21 / Spring Boot 4.0.2
- Gradle 9.3.0 (use `./gradlew`, not system Gradle)
- JUnit 5 (Jupiter) for testing

## Project Structure

- Entry point: `src/main/java/com/settlement/DailySettlementBatchApplication.java`
- Config: `src/main/resources/application.yaml`
- Base package: `com.settlement`
