# Especificação: transaction-vectorization

## Purpose
Define como o payload de uma transação é convertido em um vetor de 14 dimensões na ordem canônica de `REGRAS_DE_DETECCAO.md`: normalização com `clamp` em `[0.0, 1.0]`, sentinela `-1` para `last_transaction` ausente, tabela `mcc_risk` com default `0.5` e a relação invertida de `unknown_merchant`.

## Requirements

### Requirement: Transação é vetorizada em 14 dimensões na ordem canônica
A API SHALL transformar o payload da transação em um vetor de 14 posições, na ordem e com as fórmulas definidas em `REGRAS_DE_DETECCAO.md`: `[0]` `amount`, `[1]` `installments`, `[2]` `amount_vs_avg`, `[3]` `hour_of_day`, `[4]` `day_of_week`, `[5]` `minutes_since_last_tx`, `[6]` `km_from_last_tx`, `[7]` `km_from_home`, `[8]` `tx_count_24h`, `[9]` `is_online`, `[10]` `card_present`, `[11]` `unknown_merchant`, `[12]` `mcc_risk`, `[13]` `merchant_avg_amount`.

#### Scenario: exemplo legítimo da especificação produz o vetor esperado
- **WHEN** o payload é `{ id: "tx-1329056812", transaction: { amount: 41.12, installments: 2, requested_at: "2026-03-11T18:45:53Z" }, customer: { avg_amount: 82.24, tx_count_24h: 3, known_merchants: ["MERC-003", "MERC-016"] }, merchant: { id: "MERC-016", mcc: "5411", avg_amount: 60.25 }, terminal: { is_online: false, card_present: true, km_from_home: 29.23 }, last_transaction: null }`
- **THEN** o vetor resultante é `[0.0041, 0.1667, 0.05, 0.7826, 0.3333, -1, -1, 0.0292, 0.15, 0, 1, 0, 0.15, 0.006]` (com tolerância de arredondamento)

### Requirement: Valores normalizados são limitados ao intervalo [0.0, 1.0]
A API SHALL aplicar `clamp` ao intervalo `[0.0, 1.0]` em todas as dimensões normalizadas por divisão, MUST usar as constantes de `normalization.json` (`max_amount`, `max_installments`, `amount_vs_avg_ratio`, `max_minutes`, `max_km`, `max_tx_count_24h`, `max_merchant_avg_amount`) e MUST tratar `hour_of_day` como `hora_UTC / 23` e `day_of_week` como `(seg=0..dom=6) / 6`.

#### Scenario: valor acima do teto é cortado em 1.0
- **WHEN** `transaction.amount` é `12500.00` e `max_amount` é `10000`
- **THEN** a dimensão `amount` (índice 0) é `1.0` (e não `1.25`)

### Requirement: last_transaction null usa o sentinela -1 nos índices 5 e 6
A API SHALL preencher os índices `5` (`minutes_since_last_tx`) e `6` (`km_from_last_tx`) com o valor sentinela `-1` quando `last_transaction` for `null`, e MUST normalizar esses índices em `[0.0, 1.0]` quando `last_transaction` estiver presente. O `-1` é o único valor permitido fora de `[0.0, 1.0]`.

#### Scenario: sem transação anterior
- **WHEN** o payload tem `last_transaction: null`
- **THEN** os índices `5` e `6` do vetor são `-1`

#### Scenario: com transação anterior
- **WHEN** o payload tem `last_transaction` com `timestamp` e `km_from_current` preenchidos
- **THEN** o índice `5` é `clamp(minutos_desde_a_anterior / max_minutes)`
- **AND** o índice `6` é `clamp(km_from_current / max_km)`

### Requirement: mcc_risk usa a tabela com default 0.5
A API SHALL preencher o índice `12` (`mcc_risk`) com o valor de `mcc_risk.json` para `merchant.mcc` e MUST usar `0.5` quando o MCC não estiver presente na tabela.

#### Scenario: MCC conhecido
- **WHEN** `merchant.mcc` é `"5411"`
- **THEN** o índice `12` é `0.15`

#### Scenario: MCC desconhecido
- **WHEN** `merchant.mcc` não está em `mcc_risk.json`
- **THEN** o índice `12` é `0.5`

### Requirement: unknown_merchant inverte a relação com known_merchants
A API SHALL preencher o índice `11` (`unknown_merchant`) com `1` quando `merchant.id` NÃO estiver em `customer.known_merchants`, e com `0` quando estiver presente.

#### Scenario: comerciante desconhecido
- **WHEN** `merchant.id` é `"MERC-068"` e não consta em `customer.known_merchants`
- **THEN** o índice `11` é `1`

#### Scenario: comerciante conhecido
- **WHEN** `merchant.id` é `"MERC-016"` e consta em `customer.known_merchants`
- **THEN** o índice `11` é `0`
