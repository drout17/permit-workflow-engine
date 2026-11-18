# 05 — System Architecture, Non-Functional Requirements & Roadmap

**Service:** `permit-workflow-engine`  
**Runtime Stack:** Java 21, Spring Boot, Spring Data JPA / Hibernate, Flyway, Springdoc OpenAPI  
**Status:** Baseline v1.0

---

## 1. Layered Package Architecture

The service follows a strict domain-centric layered layout under `ai.blitzpermits.permitworkflowengine`:

```text
ai.blitzpermits.permitworkflowengine/
├── controller/      # REST endpoints, request/response DTO mapping, OpenAPI annotations
├── dto/             # Immutable Java records for API input/output contracts
├── service/         # @Transactional orchestration, pessimistic locking, audit recording
├── engine/          # Strategy pattern policy evaluators (Submission, Approval, Resubmission)
├── entity/          # JPA entities mapped to Flyway-managed tables
├── repository/      # Spring Data JPA repositories & PESSIMISTIC_WRITE queries
└── exception/       # Custom domain exceptions & GlobalExceptionHandler (@RestControllerAdvice)