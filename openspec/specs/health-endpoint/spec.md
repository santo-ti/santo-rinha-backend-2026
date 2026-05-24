# Especificação: health-endpoint

## Purpose
Define o endpoint de readiness `GET /ready` da API: retorna status HTTP 2xx com corpo vazio somente quando o índice de busca vetorial está carregado e consultável (instância pronta para `POST /fraud-score`), sinaliza indisponibilidade (não-2xx) enquanto o índice carrega, e é coberto por teste automatizado.

## Requirements

### Requirement: Endpoint GET /ready responde com status 2xx
A API SHALL expor `GET /ready` que MUST retornar um status HTTP na faixa `200-299` somente quando o índice de busca vetorial estiver carregado e consultável — ou seja, quando a instância está de fato pronta para atender `POST /fraud-score`. Não basta o `rootModule` ter sido aplicado.

#### Scenario: serviço operacional com índice carregado responde 200
- **WHEN** o servidor Ktor terminou a inicialização (`rootModule` aplicado)
- **AND** o índice de busca vetorial terminou de carregar e está consultável
- **AND** um cliente faz `GET /ready` direto na instância (porta interna 8080)
- **THEN** a resposta HTTP é `200 OK`

#### Scenario: requisição via load balancer também responde 200
- **WHEN** o stack completo está rodando via `docker compose up`
- **AND** o índice de busca vetorial está carregado e consultável
- **AND** um cliente faz `GET http://localhost:9999/ready`
- **THEN** a resposta HTTP é `200 OK`
- **AND** o tempo de resposta é inferior a 100 ms em ambiente local

### Requirement: /ready sinaliza indisponibilidade enquanto o índice carrega
A API SHALL retornar um status HTTP fora da faixa `2xx` (por exemplo, `503 Service Unavailable`) em `GET /ready` enquanto o índice de busca vetorial ainda não terminou de carregar, para que o load balancer não roteie tráfego de `POST /fraud-score` para uma instância que responderia com erro.

#### Scenario: índice ainda carregando responde não-2xx
- **WHEN** a instância iniciou o processo mas o índice ainda não terminou de carregar
- **AND** um cliente faz `GET /ready`
- **THEN** a resposta HTTP é um status fora da faixa `200-299` (ex.: `503`)

### Requirement: Resposta do /ready é minimalista
A resposta de `GET /ready` SHALL ter corpo vazio (zero bytes) e MUST NOT incluir headers `Content-Type` que indiquem JSON ou outros formatos serializados.

#### Scenario: resposta tem Content-Length 0
- **WHEN** um cliente faz `GET /ready`
- **THEN** o header `Content-Length` da resposta é `0`
- **AND** o corpo da resposta tem comprimento zero

### Requirement: /ready é coberto por teste automatizado
O arquivo `src/test/kotlin/ServerTest.kt` SHALL conter ao menos um teste que invoca `GET /ready` via `testApplication` e MUST verificar o status `200 OK`.

#### Scenario: suite de testes valida o endpoint
- **WHEN** `./gradlew test` é executado
- **THEN** existe ao menos um teste cujo nome referencia `/ready` ou `ready endpoint`
- **AND** o teste passa
