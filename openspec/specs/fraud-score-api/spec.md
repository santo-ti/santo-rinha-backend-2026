# Especificação: fraud-score-api

## Purpose
Define o endpoint `POST /fraud-score`: aceita o payload de uma transação em JSON, classifica via k-NN (k=5, threshold fixo 0.6) e responde sempre `HTTP 200` com `{ approved, fraud_score }`, nunca propagando `5xx`, dentro do orçamento de latência da carga.

## Requirements

### Requirement: Endpoint POST /fraud-score classifica a transação
A API SHALL expor `POST /fraud-score` que aceita o payload de uma transação em JSON e MUST responder `HTTP 200` com um corpo JSON contendo os campos `approved` (boolean) e `fraud_score` (number).

#### Scenario: transação legítima é aprovada
- **WHEN** um cliente envia `POST /fraud-score` com uma transação cujos 5 vizinhos mais próximos são majoritariamente `legit`
- **THEN** a resposta é `HTTP 200`
- **AND** o corpo é `{ "approved": true, "fraud_score": <number < 0.6> }`

#### Scenario: transação fraudulenta é negada
- **WHEN** um cliente envia `POST /fraud-score` com uma transação cujos 5 vizinhos mais próximos são majoritariamente `fraud`
- **THEN** a resposta é `HTTP 200`
- **AND** o corpo é `{ "approved": false, "fraud_score": <number >= 0.6> }`

### Requirement: Decisão segue k-NN com k=5 e threshold fixo 0.6
A API SHALL calcular `fraud_score` como a fração de vizinhos rotulados como fraude entre os 5 mais próximos (`n_fraudes / 5`) e MUST definir `approved = (fraud_score < 0.6)`. O threshold `0.6` é fixo.

#### Scenario: nenhum vizinho fraudulento
- **WHEN** os 5 vizinhos mais próximos são todos `legit`
- **THEN** `fraud_score` é `0.0`
- **AND** `approved` é `true`

#### Scenario: limiar exato de 3 em 5 nega a transação
- **WHEN** exatamente 3 dos 5 vizinhos mais próximos são `fraud`
- **THEN** `fraud_score` é `0.6`
- **AND** `approved` é `false` (porque `0.6 < 0.6` é falso)

#### Scenario: 2 em 5 vizinhos fraudulentos aprova
- **WHEN** exatamente 2 dos 5 vizinhos mais próximos são `fraud`
- **THEN** `fraud_score` é `0.4`
- **AND** `approved` é `true`

### Requirement: Handler nunca propaga HTTP 5xx
A API SHALL responder com um fallback rápido em caso de qualquer erro interno no processamento de `POST /fraud-score`, MUST retornar `HTTP 200` com `{ "approved": true, "fraud_score": 0.0 }` e MUST NOT propagar status `5xx`. Isso evita o peso `Err = 5` e a contribuição para a taxa de falhas (corte rígido em 15%).

#### Scenario: erro interno vira fallback 200
- **WHEN** ocorre uma exceção não esperada ao vetorizar ou consultar o índice
- **THEN** a resposta é `HTTP 200`
- **AND** o corpo é `{ "approved": true, "fraud_score": 0.0 }`
- **AND** nenhum status `5xx` é retornado

### Requirement: Resposta é JSON com latência adequada à carga
A API SHALL serializar a resposta como `application/json` e MUST manter o processamento de cada requisição dentro do orçamento de CPU compatível com a carga de até 900 req/s em 1 CPU (alvo de tempo de serviço bem abaixo de ~1,1 ms por requisição), de modo que o p99 não dispare o corte de 2000 ms.

#### Scenario: corpo da resposta tem o formato do contrato
- **WHEN** um cliente faz `POST /fraud-score` com um payload válido
- **THEN** o header `Content-Type` indica JSON
- **AND** o corpo contém apenas os campos `approved` (boolean) e `fraud_score` (number)
