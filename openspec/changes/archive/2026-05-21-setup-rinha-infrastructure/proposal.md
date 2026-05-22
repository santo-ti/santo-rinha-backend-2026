## Why

A Rinha de Backend 2026 exige uma topologia mínima de **load balancer + 2 instâncias de API** escutando na porta `9999`, dentro do envelope total de **1 CPU e 350 MB de RAM**, entregue como `docker-compose.yml` em modo de rede `bridge`. O projeto atualmente é um único processo Ktor sem containerização, sem balanceador e ainda na JVM 21. Esta change estabelece o esqueleto operacional para que as próximas changes (loader de dataset, scoring vetorial, otimizações) tenham um alvo de execução estável e mensurável.

## What Changes

- Adicionar `docker-compose.yml` com:
  - 1 serviço `lb` (Nginx) escutando em `9999`, distribuindo via round-robin entre `api-1` e `api-2`.
  - 2 serviços `api-1` e `api-2` rodando a mesma imagem do app Kotlin/Ktor.
  - `deploy.resources.limits` somando ≤ `1.0` CPU e ≤ `350MB` de memória.
  - Rede `bridge` única, sem `host` e sem `privileged`.
- Adicionar `Dockerfile` (multi-stage) que produz uma imagem `linux/amd64` enxuta a partir do fat jar do Ktor.
- Adicionar `nginx.conf` com `upstream` round-robin simples para `api-1:8080` e `api-2:8080`, sem inspeção de payload nem lógica condicional.
- Adicionar endpoint `GET /ready` retornando `200 OK` para healthcheck.
- **BREAKING (build)**: atualizar `jvmToolchain` de `21` para `25` em `build.gradle.kts` e na imagem base do Dockerfile.
- Documentar comandos `docker compose up` / `down` no `CLAUDE.md`.

## Capabilities

### New Capabilities

- `container-deployment`: Topologia de implantação local via Docker Compose — load balancer Nginx, 2 instâncias da API, limites de CPU/memória, modo de rede `bridge` e porta pública `9999`.
- `health-endpoint`: Endpoint `GET /ready` usado pelo orquestrador da Rinha para detectar prontidão da API.
- `runtime-platform`: Plataforma de execução JVM (toolchain de build, imagem base do container, flags de runtime) atualizada para JDK 25.

### Modified Capabilities

<!-- Nenhuma capability existente é modificada — openspec/specs/ está vazio. -->

## Impact

- **Código afetado**: `build.gradle.kts` (toolchain), `src/main/kotlin/dev/santo/Routing.kt` (rota `/ready`), `src/main/kotlin/dev/santo/Application.kt` (sem mudança de assinatura, apenas inclusão indireta via rota).
- **Novos arquivos na raiz**: `Dockerfile`, `docker-compose.yml`, `nginx/nginx.conf`, `.dockerignore`.
- **Dependências**: nenhuma dependência Gradle nova; Ktor plugin `buildFatJar` já está disponível.
- **Imagens externas**: `nginx:1.27-alpine` e `eclipse-temurin:25-jre-alpine` (ou equivalente `linux/amd64` publicamente acessível).
- **Testes**: `ServerTest` ganha cobertura para `GET /ready`; nenhum teste existente quebra.
- **Operação**: novo fluxo `./gradlew buildFatJar && docker compose up --build` substitui `./gradlew run` como forma canônica de subir o ambiente de submissão.
- **Branch de submissão**: artefatos prontos para a branch `submission` exigida pelo regulamento.
