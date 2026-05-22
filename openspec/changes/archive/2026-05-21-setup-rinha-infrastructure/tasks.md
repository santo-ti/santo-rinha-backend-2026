## 1. Bump da plataforma JVM

- [x] 1.1 Alterar `kotlin { jvmToolchain(21) }` para `kotlin { jvmToolchain(25) }` em `build.gradle.kts`
- [x] 1.2 Rodar `./gradlew --refresh-dependencies clean assemble` e confirmar `BUILD SUCCESSFUL` com JDK 25 instalado via Gradle toolchains
- [x] 1.3 Rodar `./gradlew test` e confirmar que `ServerTest.test root endpoint` continua passando

## 2. Endpoint GET /ready

- [x] 2.1 Em `src/main/kotlin/Routing.kt`, adicionar `get("/ready") { call.respond(HttpStatusCode.OK) }` dentro do bloco `routing { ... }`
- [x] 2.2 Importar `io.ktor.http.HttpStatusCode` no topo de `Routing.kt`
- [x] 2.3 Em `src/test/kotlin/ServerTest.kt`, adicionar teste `test ready endpoint returns 200` usando `testApplication { application { rootModule() } }` e `client.get("/ready")`
- [x] 2.4 Verificar via teste que `Content-Length` da resposta é `0` (corpo vazio)
- [x] 2.5 Rodar `./gradlew test` e confirmar que os dois testes passam

## 3. Dockerfile multi-stage

- [x] 3.1 Criar `.dockerignore` na raiz excluindo `build/`, `.gradle/`, `.idea/`, `.git/`, `*.md`, `openspec/`, `src/test/`
- [x] 3.2 Criar `Dockerfile` na raiz com estágio `builder` que executa `./gradlew --no-daemon buildFatJar` (imagem builder: `eclipse-temurin:25-jdk-alpine` em vez de `gradle:8-jdk25-alpine` — o wrapper já fixa o Gradle 9.1.0, evitando dependência de uma tag de imagem instável)
- [x] 3.3 No estágio final do `Dockerfile`, usar `FROM --platform=linux/amd64 eclipse-temurin:25-jre-alpine` (fallback documentado: `eclipse-temurin:25-jre-noble` caso a tag alpine não esteja disponível)
- [x] 3.4 No estágio final, copiar `build/libs/*-all.jar` do builder para `/app/app.jar`, expor porta `8080` e definir `ENTRYPOINT ["java","-jar","/app/app.jar"]`
- [x] 3.5 Validar localmente: `docker build --platform=linux/amd64 -t rinha-api:dev .` deve concluir com sucesso (build do fat jar em ~27s; warning benigno `FromPlatformFlagConstDisallowed`)
- [x] 3.6 Validar tamanho: `docker image inspect rinha-api:dev --format='{{.Size}}'` deve retornar valor inferior a 200 MB → retornou `86294117` bytes (~82 MiB). Nota: `docker images` reporta ~328 MB descompactado (esperado para JRE Alpine 25); reduzir além disso exigiria `jlink` (change futura)
- [x] 3.7 Validar que `javac` não existe na imagem final: `docker run --rm --entrypoint sh rinha-api:dev -c 'command -v javac; echo exit=$?'` → `exit=127` (javac ausente). `java -version` confirma Temurin 25.0.3

## 4. Configuração do Nginx

- [x] 4.1 Criar diretório `nginx/` na raiz do projeto
- [x] 4.2 Criar `nginx/nginx.conf` com `worker_processes 1;` e `events { worker_connections 2048; }`
- [x] 4.3 No bloco `http`, declarar `upstream rinha_api { server api-1:8080; server api-2:8080; }` (sem `weight`, `hash`, `ip_hash`, `least_conn`)
- [x] 4.4 Configurar `server { listen 9999; location / { proxy_pass http://rinha_api; proxy_http_version 1.1; proxy_set_header Connection ""; } }` — sem `if`, sem reescrita de corpo
- [x] 4.5 Desabilitar `access_log` ou direcionar para `/dev/null` para reduzir I/O sob carga

## 5. docker-compose.yml

- [x] 5.1 Criar `docker-compose.yml` na raiz com versão de compose moderna (sem `version:` no topo — formato Compose v2)
- [x] 5.2 Declarar rede `rinha` com `driver: bridge`
- [x] 5.3 Declarar serviço `api-1` usando `build: .`, sem `ports`, com `networks: [rinha]` e `deploy.resources.limits: { cpus: "0.425", memory: "160MB" }`
- [x] 5.4 Declarar serviço `api-2` idêntico ao `api-1` (âncora YAML `&api` compartilha build/image/limites; Compose builda a tag `rinha-api:latest` uma vez)
- [x] 5.5 Declarar serviço `lb` usando `image: nginx:1.27-alpine`, montando `./nginx/nginx.conf:/etc/nginx/nginx.conf:ro`, publicando `ports: ["9999:9999"]`, com `depends_on: [api-1, api-2]` e `deploy.resources.limits: { cpus: "0.15", memory: "30MB" }`
- [x] 5.6 Confirmar que nenhum serviço usa `network_mode: host` nem `privileged: true` (verificado via `docker compose config`)
- [x] 5.7 Confirmar que a soma dos `cpus` é exatamente `1.00` e a soma de `memory` é exatamente `350MB` (0.425+0.425+0.15=1.00; 160+160+30=350 MiB)

## 6. Validação end-to-end

- [x] 6.1 Executar `./gradlew buildFatJar` e confirmar que `build/libs/*-all.jar` foi gerado (`rinha-backend-2026-all.jar`, 11.5 MB)
- [x] 6.2 Executar `docker compose up --build -d` e aguardar todos os serviços ficarem `running` (`docker compose ps`) → 3 serviços `Up` (api-1, api-2, lb)
- [x] 6.3 Executar `curl -i http://localhost:9999/ready` 1x e confirmar `HTTP/1.1 200 OK` com `Content-Length: 0` → confirmado (servido por nginx/1.27.5)
- [x] 6.4 Executar `for i in $(seq 1 10); do curl -s http://localhost:9999/; done` e confirmar distribuição aproximada 5/5 (±1) → via `$upstream_addr` temporário no nginx: 5 / 6 entre `172.20.0.2:8080` e `172.20.0.3:8080`. Config revertida para `access_log off`
- [x] 6.5 Confirmar que `curl -i http://localhost:8080/ready` (porta da API direta no host) **falha** — APIs não devem estar expostas → connection refused (`exit=7`)
- [x] 6.6 Executar `docker compose down` e confirmar parada limpa → containers e rede removidos (`exit=0`)

## 7. Documentação

- [x] 7.1 Atualizar `CLAUDE.md` na seção "Commands" adicionando subseção "Docker / Submission" com `docker compose up --build`, `docker compose down`, `docker compose logs -f lb`
- [x] 7.2 Atualizar `CLAUDE.md` na seção "Architecture" mencionando que a topologia de submissão (LB Nginx + 2 APIs) está descrita em `docker-compose.yml` e que a porta pública é `9999`
- [x] 7.3 Atualizar `CLAUDE.md` na seção "Conventions" registrando que a toolchain JVM é `25`

## 8. Encerramento

- [x] 8.1 Rodar `openspec validate setup-rinha-infrastructure --strict` e confirmar sem erros
- [x] 8.2 Confirmar que `./gradlew test` continua verde (2 testes, 0 falhas)
- [x] 8.3 Confirmar que `docker compose up --build` continua subindo limpo após todas as mudanças aplicadas → up --build do zero subiu os 3 serviços, `/ready`=200, `down` limpo

## 9. Correções descobertas durante a validação

- [x] 9.1 Fix do entry point: o fat jar tinha `Main-Class: io.ktor.server.cio.EngineMain`, que exige `application.conf` (inexistente) e falhava com `Neither port nor sslPort specified`. Alterado `application { mainClass }` de `io.ktor.server.cio.EngineMain` para `dev.santo.MainKt` em `build.gradle.kts`, usando o entry point programático já existente em `main.kt` (porta 8080, host 0.0.0.0, módulo `rootModule`). Testes seguem verdes (usam `testApplication`, independem do `main`)
