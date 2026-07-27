# AGENTS.md

## Project Overview

This repository is `ai-faultlab`.

AI FaultLab is a Java backend fault simulation and intelligent diagnosis platform.

The system simulates backend failure scenarios such as MQ backlog, thread pool saturation, and idempotency conflicts. It collects Trace spans, metrics, and rule-based diagnosis results, then calls a Python AI diagnosis service to generate structured reports based on Runbook RAG.

## Architecture

The project contains:

- `faultlab-backend`: Java 17 + Spring Boot 3.x backend service
- `faultlab-ai-service`: Python FastAPI AI diagnosis service
- `faultlab-frontend`: frontend dashboard
- `faultlab-trace-sdk`: lightweight Java Trace SDK
- `faultlab-runbook`: Runbook documents
- `deploy`: Docker Compose and Nginx deployment files
- `docs`: architecture and design docs

## Development Rules

1. Make small, focused changes.
2. Do not implement unrelated features.
3. Do not refactor unrelated code unless explicitly requested.
4. Do not introduce new dependencies without explaining why.
5. Do not commit secrets, API keys, passwords, private tokens, or real credentials.
6. Keep Java code compatible with Java 17.
7. Keep Spring Boot code simple and readable.
8. Prefer clear domain names over generic names.
9. Add or update tests for non-trivial logic.
10. Update docs when behavior, API contracts, or setup steps change.

## Java Backend Rules

Use:

- Java 17
- Spring Boot 3.x
- MyBatis-Plus
- MySQL
- Redis
- RabbitMQ

Package naming should follow:

- `scenario`: fault scenario logic
- `trace`: Trace context and span logic
- `metric`: metric collection
- `rule`: rule diagnosis
- `diagnosis`: diagnosis orchestration
- `task`: long task engine
- `common`: shared response, error code, exceptions

Do not put business logic in controllers.

Controller -> Service -> Repository/Mapper.

Use DTOs for request and response objects.

Use unified response objects.

Use unified error codes.

## Trace Rules

The Trace model must include:

- traceId
- spanId
- parentSpanId
- experimentId
- operationName
- component
- durationMs
- status
- tags

ThreadLocal context must be cleared after request completion or async task execution.

Trace failures must not affect the main business flow.

## AI Service Rules

The Python AI service is responsible for:

- Runbook retrieval
- Prompt construction
- LLM invocation
- JSON schema validation
- Diagnosis report generation

The Java backend calls the AI service through HTTP.

The request must include:

- requestId
- traceId
- experimentId
- faultType
- metrics
- traceSummary
- ruleDiagnosis

The AI service must return structured JSON.

If AI diagnosis fails, the Java backend must fall back to rule-based diagnosis.

## Testing Rules

For Java:

- Unit test rule diagnosis logic
- Unit test Trace span tree building
- Unit test idempotency status flow
- Integration test key scenario APIs when possible

For Python:

- Unit test Runbook retrieval
- Unit test prompt builder
- Unit test JSON schema validation
- Mock LLM calls in tests

Before finishing a task, run the relevant test command and report the result.

## Code Review Checklist

Before considering a task complete, check:

- Does the code compile?
- Are tests added or updated?
- Are error cases handled?
- Are names clear?
- Is unrelated code untouched?
- Are secrets excluded?
- Is the API contract stable?
- Is fallback behavior implemented where needed?

## Response Format

After completing a task, summarize:

1. What changed
2. Files modified
3. Tests run
4. Risks or TODOs