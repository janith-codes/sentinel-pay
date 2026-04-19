# SentinelPay

**AI-Driven Fraud Detection & High-Performance Payment Gateway Backend**

[![Java 21](https://img.shields.io/badge/Java-21-%230076D6.svg?style=flat&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-%236DB33F.svg?style=flat&logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)
[![Hexagonal Architecture](https://img.shields.io/badge/Architecture-Hexagonal%20%2B%20DDD-blue.svg?style=flat)](https://github.com/janith-codes/sentinel-pay)
[![Virtual Threads](https://img.shields.io/badge/Java%20Virtual%20Threads-Enabled-brightgreen)](https://openjdk.org/jeps/444)

A production-grade, highly scalable payment gateway backend built with **Clean Architecture** and **Domain-Driven Design (DDD)** principles. Handles high-throughput transactions with real-time AI-powered fraud risk scoring.

---

## ✨ Key Features

- **Rich Domain Model** with embedded business rules (no anemic entities)
- **Hexagonal Architecture** (Ports & Adapters) – zero framework leakage in domain
- **Real-time AI Fraud Detection** using Spring AI (OpenAI / Ollama)
- **Java 21 Virtual Threads** for extreme concurrency and low memory footprint
- **Idempotency** support on payment endpoints (X-Idempotency-Key)
- **Optimistic Locking** + retry mechanism for account balance updates
- **Event-Driven** notifications via RabbitMQ
- **Distributed caching** with Redis
- **Full observability** – Actuator, Micrometer, Prometheus metrics
- **Database migrations** with Flyway
- **Production-ready** testing with Testcontainers

---

## 🏗️ Architecture

- **Domain Layer** (`sentinel-pay-domain`) – Pure Java, business rules only
- **Application Layer** (`sentinel-pay-application`) – Use cases & ports
- **Infrastructure Layer** (`sentinel-pay-infrastructure`) – Adapters (JPA, Redis, RabbitMQ, Spring AI)
- **API Layer** (`sentinel-pay-api`) – REST controllers + configuration

**Dependency direction**: `domain ← application ← infrastructure ← api`

This structure is exactly what senior engineering teams at **WSO2**, **IFS**, and **Codegen** expect in high-scale fintech systems.

---

## 🚀 Quick Start

### Prerequisites

- Java 21
- Docker & Docker Compose

### 1. Clone & Run

```bash
git clone https://github.com/janith-codes/sentinel-pay.git
cd sentinel-pay

# Start infrastructure (PostgreSQL, Redis, RabbitMQ)
docker compose up -d

# Build and run the application
./mvnw spring-boot:run -pl sentinel-pay-api -am
```

Application will be available at: `http://localhost:8080`

---

## 📁 Project Structure

```
sentinel-pay/
├── sentinel-pay-domain/          ← Rich Domain Entities + Value Objects
├── sentinel-pay-application/     ← Use Cases, Input/Output Ports
├── sentinel-pay-infrastructure/  ← JPA, Redis, RabbitMQ, Spring AI Adapters
├── sentinel-pay-api/             ← REST Controllers + Spring Boot config
├── docker-compose.yml
└── pom.xml                       ← Multi-module Maven parent
```

---

## 🔧 Tech Stack

| Layer              | Technology                          |
|--------------------|-------------------------------------|
| Language           | Java 21 + Virtual Threads           |
| Framework          | Spring Boot 3.3.x                   |
| Architecture       | Hexagonal + DDD                     |
| Database           | PostgreSQL + Flyway                 |
| Cache              | Redis                               |
| Messaging          | RabbitMQ                            |
| AI                 | Spring AI (OpenAI / Ollama)         |
| Observability      | Actuator + Micrometer + Prometheus  |
| Testing            | JUnit 5 + Mockito + Testcontainers  |

---

## 📌 Senior Concepts Implemented

- **Idempotent Payment API** with Redis-backed request deduplication
- **Optimistic Locking** on balance updates to prevent race conditions
- **Rich Domain Model** with invariants and business logic inside entities
- **Decoupled AI Fraud Service** (easily swappable between OpenAI and Ollama)
- **Virtual Threads** for handling thousands of concurrent transactions efficiently

---

## 📡 Sample API

```http
POST /api/v1/payments
X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "amount": 1250.75,
  "currency": "LKR"
}
```

---

## 📈 Observability

- Health check: `GET /actuator/health`
- Prometheus metrics: `GET /actuator/prometheus`
- Custom fraud metrics and transaction throughput exposed

---

## 🛠️ Future Enhancements (Roadmap)

- Distributed Saga pattern for cross-service transactions
- Rate limiting & circuit breaker with Resilience4j
- GraphQL endpoint alongside REST
- Event sourcing for audit trail

---

## 👨‍💻 Author

**Janith Sandaruwan**  
Senior Java Software Engineer | Passionate about High-Performance Fintech Systems

---

**⭐ If this project helped you or you liked the architecture, please give it a star!**
