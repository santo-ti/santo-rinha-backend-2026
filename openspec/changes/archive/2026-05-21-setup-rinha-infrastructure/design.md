## Context

O projeto hoje é um único processo Ktor 3.4.0 (engine CIO) iniciado em `src/main/kotlin/main.kt` ouvindo em `0.0.0.0:8080`, sem container, sem load balancer e sem endpoint de prontidão. O regulamento da Rinha de Backend 2026 (`docs/br/ARQUITETURA.md`) impõe:

- Entrega como `docker-compose.yml`, imagens públicas, arquitetura `linux/amd64`.
- Load balancer obrigatório distribuindo via **round-robin simples** entre **no mínimo 2 instâncias de API**, sem inspeção de payload nem lógica condicional.
- Porta pública **9999** no load balancer.
- Rede `bridge`, sem `privileged`, sem `host`.
- Soma total ≤ **1 CPU e 350 MB de RAM** entre todos os serviços.

Esta change é a **primeira fatia da migração**: monta o esqueleto operacional sem ainda implementar o `POST /fraud-score` nem carregar o dataset de 3M vetores. Implementações posteriores assumem a topologia aqui definida.

## Goals / Non-Goals

**Goals:**
- Subir `docker compose up` e ver o load balancer em `localhost:9999` repassando para 2 instâncias Ktor saudáveis.
- Cumprir o limite de 1 CPU / 350 MB com folga visível para a próxima change (que carregará ~168 MB de vetores em memória).
- Manter os testes existentes (`./gradlew test`) verdes e adicionar cobertura de `GET /ready`.
- Atualizar a toolchain JVM de 21 para 25 sem regressões.

**Non-Goals:**
- Implementar `POST /fraud-score`, loader de `references.json.gz`, k-NN ou normalização (próximas changes).
- Otimizações finas de JVM (GC, compressed oops, AOT) — ficam para a change de tuning.
- Métricas, tracing, logs estruturados.
- CI/CD, push automático de imagem para registry.
- Branch `submission` (será materializada no momento do envio, fora desta change).

## Decisions

### Decisão 1: Nginx 1.27-alpine como load balancer

**Escolhido**: `nginx:1.27-alpine` com bloco `upstream` round-robin padrão (sem `least_conn`, `ip_hash` ou `hash`).

**Alternativas consideradas**:
- HAProxy: igualmente competente, configuração mais verbosa, menor familiaridade no ecossistema Rinha.
- Caddy: sintaxe agradável, mas ~30 MB de runtime contra ~10 MB do Nginx alpine — desperdício no envelope de 350 MB.

**Racional**: Nginx alpine consome ~5–10 MB em runtime, é o padrão de facto nas edições anteriores da Rinha, e o `proxy_pass http://upstream` sem `header`/`if` honra a restrição de "não inspecionar payload nem aplicar lógica condicional".

### Decisão 2: Divisão de recursos (1 CPU / 350 MB)

| Serviço | CPU  | Memória |
|---------|------|---------|
| `lb`    | 0.15 | 30 MB   |
| `api-1` | 0.425| 160 MB  |
| `api-2` | 0.425| 160 MB  |
| **Total** | **1.00** | **350 MB** |

**Racional**: Nginx é I/O-bound e barato; a maior parte do CPU vai para as APIs onde o k-NN futuro vai consumir. 160 MB por instância deixa ~80 MB de heap útil após RSS da JVM (~70–80 MB de overhead em Temurin 25 com `-XX:+UseSerialGC` ou `ZGC` generational) — suficiente para a fatia atual; a change de scoring vetorial revisará esse split (provavelmente concentrando vetores num serviço sidecar compartilhado via shared memory ou aumentando memória das APIs e reduzindo do LB).

**Não definitivo**: este split é o ponto de partida. A change de scoring vetorial irá medir e reajustar.

### Decisão 3: Dockerfile multi-stage com fat jar

**Escolhido**: estágio `builder` com `gradle:8-jdk25-alpine` rodando `./gradlew buildFatJar`, estágio final `eclipse-temurin:25-jre-alpine` copiando apenas o jar.

**Alternativas consideradas**:
- `jib` (plugin Gradle): camadas reproduzíveis sem Dockerfile, mas adiciona dependência e foge do padrão Rinha.
- `application` distribution (`./gradlew installDist`): produz `bin/` + `lib/`, mais arquivos para copiar.

**Racional**: o plugin Ktor já provê `buildFatJar` (visto em `CLAUDE.md`); um único arquivo `app.jar` no estágio final mantém a imagem ≤ ~80 MB e o `ENTRYPOINT` trivial.

### Decisão 4: Bump JVM 21 → 25 nesta change

**Escolhido**: alterar `kotlin { jvmToolchain(25) }` em `build.gradle.kts` e usar `eclipse-temurin:25-jre-alpine` na imagem.

**Alternativas consideradas**:
- Manter JVM 21 nesta change e fazer o bump separado: mais seguro, mais churn (duas mudanças em `build.gradle.kts` em sequência).
- JVM 24 (último LTS-ish antes do 25): sem ganho relevante para este projeto.

**Racional**: o usuário pediu o upgrade explicitamente. JVM 25 traz ZGC generational maduro e melhorias de startup que beneficiam o cenário futuro de carregar 168 MB de vetores. Como o app ainda é trivial, o risco de regressão é mínimo e o custo de fazer separado é alto (Dockerfile teria que ser editado duas vezes).

### Decisão 5: `GET /ready` retorna 200 sem corpo

**Escolhido**: `get("/ready") { call.respond(HttpStatusCode.OK) }` — resposta vazia, header `Content-Length: 0`.

**Alternativas consideradas**:
- Retornar JSON `{"status":"ready"}`: gasta serialização desnecessária no health check.
- Verificar prontidão real (dataset carregado etc.) antes de responder: nesta change não há dataset; viraria mock.

**Racional**: o regulamento (`API.md`) só exige "HTTP 2xx quando o serviço estiver operacional". Quanto mais leve, melhor para o load balancer e para o sniffer do orquestrador. Quando a próxima change carregar o dataset, esta rota será modificada para responder 503 enquanto `isReady.get() == false`.

### Decisão 6: Rede `bridge` única chamada `rinha`

**Escolhido**: definir `networks: { rinha: { driver: bridge } }` no compose e anexar todos os 3 serviços nela.

**Racional**: cumpre a exigência de `bridge`; DNS interno do Docker resolve `api-1`/`api-2` no `upstream` do Nginx; sem necessidade de portas expostas nas APIs (só o `lb` publica 9999).

## Risks / Trade-offs

- **Risco**: 160 MB por API pode não ser suficiente após a change do dataset (3M × 14 floats = ~168 MB só para os vetores, sem contar índice/JIT/Metaspace). → **Mitigação**: esta change deixa o split explícito em `docker-compose.yml`, e o design.md da próxima change recomendará revisitar (provável caminho: subir RAM das APIs cortando 5–10 MB do LB e parte da CPU, ou introduzir um serviço sidecar `embeddings` que serve vetores via socket UNIX). 
- **Risco**: JVM 25 alpine images podem não estar disponíveis no Docker Hub no momento da execução. → **Mitigação**: fallback documentado para `eclipse-temurin:25-jre-noble` (Ubuntu, ~30 MB maior); checar `docker pull` antes do PR.
- **Risco**: configuração padrão do Nginx em alpine usa `worker_processes auto`, que com 0.15 CPU pode criar mais workers do que cabem. → **Mitigação**: fixar `worker_processes 1; worker_connections 2048;` no `nginx.conf`.
- **Risco**: `./gradlew buildFatJar` em ambiente sem cache pode levar minutos no estágio builder, tornando o ciclo `docker compose up --build` lento. → **Mitigação**: incluir `.dockerignore` que exclui `build/`, `.gradle/`, `.idea/`, `*.md`; documentar no `CLAUDE.md` que o fluxo iterativo de dev continua sendo `./gradlew run`.
- **Trade-off**: bump JVM acoplado a setup de infra mistura duas decisões em uma change. Aceito porque ambas tocam o Dockerfile e o usuário pediu junto. Documentado como decisão explícita acima.

## Migration Plan

1. Aplicar bump JVM no `build.gradle.kts`, rodar `./gradlew clean build` localmente para validar que nada quebra (testes existentes + compilação Kotlin).
2. Adicionar `GET /ready` em `Routing.kt` + teste em `ServerTest.kt`.
3. Criar `Dockerfile`, `.dockerignore`, `nginx/nginx.conf`.
4. Criar `docker-compose.yml`.
5. Validar fluxo end-to-end: `./gradlew buildFatJar && docker compose up --build`, depois `curl http://localhost:9999/ready` (esperando 200) e `curl http://localhost:9999/` repetido 10× observando que ambos `api-1` e `api-2` recebem requisições (via `docker compose logs`).
6. Atualizar `CLAUDE.md` com a nova seção de comandos Docker.

**Rollback**: a change é puramente aditiva ao runtime (não altera comportamento de `./gradlew run`). Reverter o commit restaura JVM 21 e remove os arquivos de infra; nenhum dado é persistido.

## Open Questions

- Confirmação no momento da implementação: a imagem `eclipse-temurin:25-jre-alpine` já está publicada? Caso negativo, cair para `eclipse-temurin:25-jre-noble` sem novo design.
- A próxima change (loader do dataset) precisará revisitar o split de memória — não bloqueia esta change, mas será o primeiro item a reavaliar.
