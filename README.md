<div align="center">

<img src="https://pub-9030d6e053cc40b380e0f63662daf8ed.r2.dev/logo.png" alt="THISJOWI Logo" width="150"/>

# THISJOWI Server

**Modern and Secure Microservices Backend**

[![Go](https://img.shields.io/badge/Go-1.25-00ADD8?style=flat-square&logo=go)](https://go.dev/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=flat-square&logo=docker)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-Proprietary-red?style=flat-square)](./LICENCE.md)

</div>

---

## Overview

THISJOWI Server is a scalable, microservice-oriented backend designed to power the THISJOWI ecosystem. It provides robust authentication, secure communication, and encrypted data storage with a focus on privacy and performance.

## Core Services

| Service | Language | Description |
|:--- |:--- |:--- |
| **Note** | Go | Encrypted note management with tagging and versioning. |
| **Password** | Go | Secure credential vault for password management. |
| **OTP** | Go | One-Time Password generation and verification engine. |

## Tech Stack

- **Language:** Go 1.25
- **Database:** CockroachDB (PostgreSQL-compatible)
- **Messaging:** Kafka
- **Infrastructure:** Docker

## Quick Start

### 1. Prerequisites
- Go 1.25+
- Docker & Docker Compose

### 2. Infrastructure
```bash
docker compose up -d
```

### 3. Run all services
```bash
make dev
```

Or run individually:
```bash
go run ./services/note/cmd/server/
go run ./services/otp/cmd/server/
go run ./services/password/cmd/server/
```

## Containerization

Each service contains its own `Dockerfile`. Multi-arch images (`linux/amd64`, `linux/arm64`) are built and pushed to Docker Hub via CI.

## Security & Privacy

- **E2EE Ready:** Designed to support end-to-end encryption.
- **Data Security:** AES-256 encryption for sensitive data at rest.
- **Access Control:** Fine-grained JWT-based authorization.

---

<div align="center">

**Crafted with ❤️ by THISJOWI**

[🌐 Website](https://thisjowi.uk) • [🐛 Issues](../../issues) • [🤝 Contributing](./CONTRIBUTING.md)

</div>
