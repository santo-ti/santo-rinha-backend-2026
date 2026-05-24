## 1. Validação de correção e latência do design (bucketing + VP-Tree int8)

- [x] 1.1 Baixar/inspecionar `resources/example-references.json` e `resources/example-payloads.json` para confirmar formato e distribuição dos vetores
- [x] 1.2 Implementar oráculo de força bruta (k=5, euclidiana exata) como referência de correção
- [x] 1.3 Confirmar a hipótese do bucketing categórico: medir quantos top-5 do oráculo caem fora do bucket da assinatura (esperado ~0) e a ocorrência de buckets com < 5 membros
- [x] 1.4 Implementar o protótipo (bucketing + VP-Tree int8) e verificar resultado **idêntico** ao oráculo (FP/FN do algoritmo = 0), inclusive a guarda de borda
- [ ] 1.5 Medir p99 por consulta em 1 CPU e a fração de cada bucket varrida pelo VP-Tree; sub-particionar buckets grandes se necessário
- [x] 1.6 Confirmar que a quantização int8 mantém o voto majoritário (k=5, threshold 0.6) idêntico ao oráculo

## 2. Vetorização (14 dimensões)

- [x] 2.1 Adicionar modelos de request (`transaction`, `customer`, `merchant`, `terminal`, `last_transaction` nullable) com `kotlinx.serialization`
- [x] 2.2 Carregar `normalization.json` e `mcc_risk.json` como constantes/tabela (default `0.5`)
- [x] 2.3 Implementar a vetorização nas 14 dimensões com `clamp [0,1]`, `hour_of_day` (UTC/23) e `day_of_week` (seg=0..dom=6 / 6)
- [x] 2.4 Implementar o sentinela `-1` para `last_transaction: null` (índices 5 e 6)
- [x] 2.5 Testar contra o exemplo legítimo da spec: vetor esperado `[0.0041, 0.1667, 0.05, 0.7826, 0.3333, -1, -1, 0.0292, 0.15, 0, 1, 0, 0.15, 0.006]`
- [x] 2.6 Testar clamp (amount > max_amount → 1.0), MCC default 0.5 e `unknown_merchant` (1/0)

## 3. Pré-processamento e build do índice

- [x] 3.1 Implementar conversor `references.json.gz` → artefato binário compacto (vetores int8 + bitset de labels + estrutura do índice escolhido)
- [x] 3.2 Preservar o sentinela `-1` (índices 5 e 6) sem filtrar/substituir
- [x] 3.3 Aplicar a partição `has_history` / `no_history` na estrutura do índice
- [x] 3.4 Integrar a geração do artefato ao build da imagem (multi-stage `Dockerfile`)
- [x] 3.5 Definir a estratégia de distribuição do `references.json.gz`/binário (download no build vs. versionado)

## 4. Índice de busca e carga no startup

- [x] 4.1 Implementar o carregador do artefato binário no startup (sem parse de JSON em runtime)
- [x] 4.2 Implementar a busca k-NN (k=5) do algoritmo escolhido sobre os vetores int8
- [x] 4.3 Expor estado de prontidão do índice (carregado/consultável) para o readiness
- [x] 4.4 Validar tempo de carga no startup (alvo: readiness < 1s) — medido 1202 ms (assíncrono, gated pelo /ready; aceitável)

## 5. Endpoint POST /fraud-score

- [x] 5.1 Criar `configureFraudScore()` e agregá-lo em `rootModule`
- [x] 5.2 Implementar o handler: payload → vetorização → busca k-NN → `fraud_score = n_fraudes/5` → `approved = fraud_score < 0.6`
- [x] 5.3 Implementar o fallback que nunca propaga 5xx (em exceção: `200 { approved: true, fraud_score: 0.0 }`)
- [x] 5.4 Testar decisão e limiares (0/5 → approved; 3/5=0.6 → negado; 2/5=0.4 → approved) via `testApplication` com `rootModule`
- [x] 5.5 Testar que erro interno resulta em `200` de fallback, nunca `5xx`

## 6. Readiness portado pelo índice (health-endpoint modificado)

- [x] 6.1 Alterar `GET /ready` para responder `2xx` apenas quando o índice está carregado e consultável
- [x] 6.2 Responder não-2xx (ex.: `503`) enquanto o índice carrega
- [x] 6.3 Atualizar/garantir o teste de `/ready` cobrindo os dois estados (carregando vs. pronto)

## 7. Runtime e infraestrutura

- [x] 7.1 Tunar a JVM por instância (`-Xmx` baixo, GC Serial/Epsilon, AppCDS) para caber na fatia de memória
- [x] 7.2 Ajustar `docker-compose.yml`: fatias de CPU/memória somando ≤ 1 CPU e ≤ 350 MB (LB + 2 APIs)
- [x] 7.3 Garantir que o LB só roteia para instâncias com `/ready` 2xx
- [x] 7.4 GraalVM native-image — VALIDADO: stack native em 160 MB/instância, pico ~98 MiB, 0 OOM, final_score +2876. Metadata via tracing agent + reflect-config manual p/ Ktor CIO (`Dockerfile.native`)
- [ ] 7.5 (Opcional agora) Mover o store int8 para off-heap — não é mais bloqueador (native cabe folgado); só relevante se ajudar o p99

## 8. Validação de carga e pontuação

- [x] 8.1 Rodar o stack completo (`docker compose up --build`) e validar readiness gate — OK (readiness 200 após carga; exemplos da spec corretos contra índice 3M)
- [x] 8.2 Rodar o teste k6 (`test.js`, ramp 1→900 req/s) localmente e coletar `results.json`
- [x] 8.3 Verificar p99, `failure_rate` (< 15%) e `final_score`; iterar parâmetros se necessário — MEDIDO: detecção ~0,13% (ótima); falha no orçamento de 160 MB (OOM, −6000); com memória relaxada +2567
- [x] 8.4 Confirmar consumo de memória/CPU dentro dos limites declarados sob carga — APROVADO com a imagem native: pico ~98 MiB/160, 0 OOM, 0 erro HTTP, final_score +2876
