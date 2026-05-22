## ADDED Requirements

### Requirement: Endpoint GET /ready responde com status 2xx
A API SHALL expor `GET /ready` que MUST retornar um status HTTP na faixa `200-299` quando o processo está pronto para receber tráfego.

#### Scenario: serviço operacional responde 200
- **WHEN** o servidor Ktor terminou a inicialização (`rootModule` aplicado)
- **AND** um cliente faz `GET /ready` direto na instância (porta interna 8080)
- **THEN** a resposta HTTP é `200 OK`

#### Scenario: requisição via load balancer também responde 200
- **WHEN** o stack completo está rodando via `docker compose up`
- **AND** um cliente faz `GET http://localhost:9999/ready`
- **THEN** a resposta HTTP é `200 OK`
- **AND** o tempo de resposta é inferior a 100 ms em ambiente local

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
