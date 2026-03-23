# Skyjo Backend

Spring Boot WebSocket Server für das Skyjo Spiel – AAU SE2 Gruppe 5.

## Tech Stack

- Kotlin + Spring Boot 3.x
- WebSocket (STOMP)
- Gradle (Kotlin DSL)
- JUnit 5 + JaCoCo
- SonarCloud Quality Gates

## Setup

```bash
./gradlew build
./gradlew test
./gradlew bootRun
```

## CI/CD

GitHub Actions führt bei jedem Push/PR auf `main` automatisch aus:
1. Build
2. Tests + JaCoCo Coverage Report
3. SonarCloud Scan

## Branch-Workflow

- Feature-Branches: `feature/<beschreibung>`
- Commit-Convention: [Conventional Commits](https://www.conventionalcommits.org/)
- Merges nur via Pull Request (kein Squash/Rebase)
- `main` ist protected und muss jederzeit lauffähig sein
