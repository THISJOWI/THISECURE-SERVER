<div align="center">

<img src="https://pub-9030d6e053cc40b380e0f63662daf8ed.r2.dev/logo.png" alt="THISJOWI Logo" width="150"/>

# THISJOWI Server

**Modern and Secure Microservices Backend**

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.x-6DB33F?style=flat-square&logo=spring)](https://spring.io/)
[![NestJS](https://img.shields.io/badge/NestJS-10.x-E0234E?style=flat-square&logo=nestjs)](https://nestjs.com/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=flat-square&logo=docker)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-Proprietary-red?style=flat-square)](./LICENCE.md)

</div>

---

## 📖 Overview

THISJOWI Server is a scalable, microservice-oriented backend designed to power the THISJOWI ecosystem. It provides robust authentication, secure communication, and encrypted data storage with a focus on privacy and performance.

## 📦 Core Services

| Service | Technology | Description |
|:--- |:--- |:--- |
| 🔐 **Auth** | Spring Boot | Identity management, JWT, OAuth2, and multi-tenant LDAP support. |
| 📝 **Note** | Spring Boot | Encrypted note management with tagging and versioning. |
| 💬 **Messages** | NestJS | Real-time messaging hub with WebSocket support. |
| 🔑 **Password** | Spring Boot | Secure credential vault for password management. |
| 🔔 **OTP** | Spring Boot | One-Time Password generation and verification engine. |
| ⚙️ **Config** | Spring Boot | Centralized configuration management for all services. |

## 🛠 Tech Stack

- **Languages:** Java 21, TypeScript
- **Frameworks:** Spring Boot 3.x, NestJS 10.x, Spring Cloud
- **Databases:** PostgreSQL, Redis, Kafka, Cassandra
- **Infrastructure:** Docker, Kubernetes (K8s), Spring Cloud Config

## 🚀 Quick Start

### 1. Prerequisites
- Java 21+ & Node.js 18+
- Docker & Docker Compose
- Gradle 8.x

### 2. Infrastructure
Spin up the necessary databases and middleware:
```bash
# Using the provided helper script
./infrastructure/scripts/init.sh
```

### 3. Running Services
Each service is independent. To run the **Auth Service**:
```bash
cd auth
./gradlew bootRun
```
For the **Messages Service**:
```bash
cd messages
npm install && npm run start:dev
```

## ☸️ Containerization & Orchestration

The project is ready for cloud deployment using Docker and Kubernetes.

- **Docker:** Each service contains its own `Dockerfile` and `compose.yaml`.
- **Kubernetes:** Manifests are located in `infrastructure/k8s/` for scalable deployments.

## � Security & Privacy

- **E2EE Ready:** Designed to support end-to-end encryption.
- **Data Security:** AES-256 encryption for sensitive data at rest.
- **Access Control:** Fine-grained JWT-based authorization.
- **Isolated Config:** Centralized secrets and configuration management.

---

<div align="center">

**Crafted with ❤️ by THISJOWI**

[🌐 Website](https://thisjowi.uk) • [🐛 Issues](../../issues) • [🤝 Contributing](./CONTRIBUTING.md)

</div>
