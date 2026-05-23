## Why

A infraestrutura (load balancer, 2 instâncias, containerização) já está pronta, mas a API ainda não faz o que o desafio exige: classificar transações como fraude ou legítimas. O endpoint `POST /fraud-score` é o núcleo da Rinha de Backend 2026 — sem ele não há nada a pontuar. O problema central não é o HTTP (trivial), e sim executar uma busca k-NN sobre **3.000.000 de vetores** dentro de um orçamento de **1 CPU e 350 MB para todos os serviços somados**, mantendo p99 na casa de milissegundos sob carga de até 900 req/s.

## What Changes

- Adiciona o endpoint `POST /fraud-score` que recebe o payload de uma transação e responde `{ "approved": boolean, "fraud_score": number }`.
- Adiciona a **vetorização** da transação em 14 dimensões normalizadas (clamp `[0,1]`, sentinela `-1` para `last_transaction: null` nos índices 5 e 6), conforme `REGRAS_DE_DETECCAO.md`.
- Adiciona um **índice de busca vetorial exato e sublinear** (bucketing categórico + VP-Tree por bucket) sobre o dataset de referência, com decisão por k-NN (`k=5`, distância euclidiana): `fraud_score = n_fraudes / 5` e `approved = fraud_score < 0.6`.
- Adiciona um passo de **pré-processamento no build da imagem**: converte `references.json.gz` (~284 MB / 3M vetores) num artefato binário compacto (vetores quantizados em `int8` + bitset de labels + índice), eliminando parse/descompressão no startup.
- Adiciona **resiliência no handler**: nenhum erro interno pode virar HTTP 5xx — em falha, responde rápido com um fallback (`approved: true, fraud_score: 0.0`), porque `Err` pesa 5 e conta na taxa de falhas (corte rígido em 15%).
- **BREAKING (contrato interno):** o readiness `GET /ready` passa a só responder `2xx` **depois** que o índice estiver carregado e consultável — antes, respondia assim que o `rootModule` era aplicado.

## Capabilities

### New Capabilities
- `fraud-score-api`: contrato HTTP do `POST /fraud-score` (formato de request/response, status codes, decisão k-NN com `k=5` e threshold fixo `0.6`, e o fallback que nunca propaga 5xx).
- `transaction-vectorization`: regras determinísticas de transformação do payload nas 14 dimensões normalizadas, incluindo as constantes de `normalization.json`, o `mcc_risk.json` (default `0.5`) e o sentinela `-1`.
- `vector-index`: pré-processamento do dataset de referência, quantização `int8`, estrutura de busca sublinear e construção do índice no build da imagem com carga no startup.

### Modified Capabilities
- `health-endpoint`: o `GET /ready` passa a sinalizar prontidão apenas quando o índice de busca está carregado e pronto para consultas, evitando que o load balancer roteie tráfego para uma instância que responderia com erro.

## Impact

- **Código:** novo `configureFraudScore()` (módulo de rota) agregado em `rootModule`; novos componentes de vetorização, índice e carga do dataset no pacote `dev.santo`.
- **Build/imagem:** novo passo de build que gera o artefato binário do índice a partir de `resources/references.json.gz`; impacto no `Dockerfile` (multi-stage) e no tamanho das camadas.
- **Recursos de referência:** consumo de `normalization.json`, `mcc_risk.json` e `references.json.gz`.
- **docker-compose / runtime:** tuning de memória por instância (heap baixo, GC enxuto) para caber no rateio de 350 MB; readiness gate antes de receber tráfego.
- **Specs existentes:** evolução de `health-endpoint`. Sem mudança em `container-deployment` e `runtime-platform`.
- **Decisão de algoritmo:** busca **exata** via bucketing categórico (`has_history` × `is_online` × `card_present` × `unknown_merchant`) + VP-Tree por bucket sobre vetores `int8`. Escolhida para zerar o erro de detecção do algoritmo — respostas idênticas à força bruta do gabarito fazem o `detection_score` saturar no teto de +3000. **GraalVM native-image** fica como alavanca de runtime caso a memória da JVM aperte.
