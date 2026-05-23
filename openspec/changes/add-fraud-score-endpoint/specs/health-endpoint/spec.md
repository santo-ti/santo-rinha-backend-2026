## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: /ready sinaliza indisponibilidade enquanto o índice carrega
A API SHALL retornar um status HTTP fora da faixa `2xx` (por exemplo, `503 Service Unavailable`) em `GET /ready` enquanto o índice de busca vetorial ainda não terminou de carregar, para que o load balancer não roteie tráfego de `POST /fraud-score` para uma instância que responderia com erro.

#### Scenario: índice ainda carregando responde não-2xx
- **WHEN** a instância iniciou o processo mas o índice ainda não terminou de carregar
- **AND** um cliente faz `GET /ready`
- **THEN** a resposta HTTP é um status fora da faixa `200-299` (ex.: `503`)
