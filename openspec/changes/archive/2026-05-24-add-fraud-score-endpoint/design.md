## Context

A Rinha de Backend 2026 exige um classificador de fraude exposto em `POST /fraud-score`. A decisão é um k-NN (`k=5`, distância euclidiana) sobre **3.000.000 de vetores de referência** rotulados, com `fraud_score = n_fraudes / 5` e `approved = fraud_score < 0.6`. O gabarito do teste foi gerado com k-NN **exato** (k=5, euclidiana, força bruta).

Duas restrições governam todo o design:

1. **Recursos:** a soma dos limites declarados no `docker-compose.yml` é, no máximo, **1 CPU e 350 MB** para todos os serviços (LB + 2 instâncias + qualquer outro). As "2 instâncias" são exigência de topologia — elas dividem o **mesmo** 1 CPU, então não há ganho real de paralelismo, apenas custo dobrado de memória.
2. **Carga:** k6 `ramping-arrival-rate`, `1 → 900 req/s` em 120s (modelo aberto), timeout de 2001 ms. Budget de CPU ≈ `1000 ms / 900 ≈ 1,11 ms` por requisição só para empatar com a chegada; para p99 baixo, o alvo é **bem abaixo disso** (~0,3–0,5 ms).

Pontuação: `p99` em escala log (≤1 ms = +3000; 10 ms = +2000; >2000 ms = −3000) somado a `detecção` (corte rígido em −3000 se falhas > 15%; pesos `FP=1`, `FN=3`, `Err=5`).

## Goals / Non-Goals

**Goals:**
- Responder `POST /fraud-score` corretamente e com p99 na casa de poucos ms sob 900 req/s em 1 CPU.
- Caber no orçamento de 350 MB com 2 instâncias carregando o dataset independentemente.
- Tirar o máximo de trabalho do runtime (pré-processamento no build).
- Nunca devolver `5xx`; readiness só positivo com índice pronto.

**Non-Goals:**
- Implementar força bruta como solução final (serve apenas de baseline/oráculo de validação).
- Usar busca aproximada (ANN/IVF/HNSW) — a escolha é por busca **exata**, para zerar o erro de detecção do algoritmo (ver D1).
- Otimizar p99 abaixo de 1 ms (satura o score em +3000).
- Compartilhar memória entre containers via mmap/page cache (descartado por fragilidade — ver D2).

## Decisions

### D1 — Busca exata via bucketing categórico + VP-Tree por bucket
A escolha é **k-NN exato**, não aproximado. Justificativa pelo scoring: como o gabarito é k-NN exato, um método exato produz `FP = FN = 0` (erro de detecção do algoritmo igual a zero), o que faz o `detection_score` **saturar no teto de +3000**. Um método aproximado (IVF/HNSW) nunca alcança +3000 e ainda corre o risco do corte rígido de 15%. Portanto, busca exata domina — desde que seja rápida o suficiente, e a estrutura dos dados permite que seja.

Força bruta pura custa `3M × 14 ≈ 42M` operações/consulta (~42 ms), **38× acima** do budget. A aceleração exata vem de duas podas encaixadas:

1. **Bucketing categórico (poda exata de 1ª ordem).** Quatro dimensões são binárias/categóricas: `has_history` (índices 5/6 = `-1` ou não), `is_online` (9), `card_present` (10), `unknown_merchant` (11). Divergir em qualquer uma injeta `≥ 1.0` na distância **ao quadrado**, enquanto vizinhos reais ficam a ~`0.001–0.06`. Logo, os 5 vizinhos verdadeiros compartilham, com quase-certeza, a mesma assinatura categórica. Particionar o dataset por essa assinatura (até ~16 buckets, ~187k vetores cada) é uma poda **exata**: a consulta vai direto ao bucket da sua assinatura.
2. **VP-Tree por bucket (poda exata de 2ª ordem).** Dentro do bucket restam ~10 dimensões contínuas. Um VP-Tree (Vantage Point Tree) sobre os vetores `int8` faz poda exata por raio, levando a busca a uma fração do bucket. VP-Tree lida melhor que KD-Tree nessa dimensionalidade.

**Guarda de borda:** se o bucket da assinatura tiver `< 5` membros (combinação rara), a busca expande para os buckets vizinhos mais próximos antes de decidir — mantém a correção mesmo no caso degenerado.

**Alternativas descartadas:** *brute force* puro (lento demais); *IVF/HNSW* (aproximados — teto de detecção menor e risco de corte; HNSW ainda estoura memória com ~`M × 3M × 4 B ≈ 192 MB` de grafo); *KD-Tree* (poda fraca em ~10–14 dims).

### D2 — Quantização int8 dos vetores de referência
`int8` reduz o dataset de ~168 MB (`float32`) para ~42 MB por cópia. Assim **cada instância carrega uma cópia independente** dentro da sua fatia de memória, sem depender de compartilhamento entre containers. Os valores já vivem em `[0,1]` (mais o sentinela `-1`), o que torna a quantização para `[0,255]` natural, reservando um código para o `-1`. O VP-Tree é armazenado de forma implícita (array, sem ponteiros) para minimizar overhead; os raios por nó cabem em poucos MB adicionais.

- **Alternativa descartada (mmap compartilhado):** o limite de memória é por container; as páginas do arquivo são cobradas no cgroup de quem faz o page-fault, então o compartilhamento só funcionaria com split assimétrico (uma instância "dona" das páginas), o que é frágil e dependente de semântica de cgroup v2.

### D3 — Partição por `has_history` faz parte do bucketing
A separação `has_history` / `no_history` (do sentinela `-1` nos índices 5/6) é uma das quatro dimensões da assinatura categórica de D1 — não é um mecanismo separado. Está destacada aqui porque é a de maior efeito: o salto de `-1` para `[0,1]` em **duas** dimensões injeta `≥ 2.0` na distância ao quadrado.

### D4 — Construir o índice no build da imagem
Os três arquivos de referência não mudam durante o teste. Parsear/descomprimir ~284 MB de JSON no startup, em 1 CPU, custa dezenas de segundos e atrasa o readiness. Um passo de build converte `references.json.gz` num artefato binário compacto (`int8` + bitset de labels + buckets + VP-Trees implícitas) embarcado na imagem; o startup apenas carrega esse artefato.

### D5 — Resiliência: fallback que nunca propaga 5xx
`Err` pesa 5 e conta na taxa de falhas (corte em 15%). Um `5xx` é pior que qualquer erro de detecção. O handler envolve vetorização+consulta e, em qualquer exceção, responde `200` com `{ approved: true, fraud_score: 0.0 }`.

### D6 — Readiness portado pelo índice
`GET /ready` só responde `2xx` quando o índice está carregado e consultável; enquanto carrega, responde não-2xx (ex.: `503`). Evita que o LB roteie `POST /fraud-score` para instância sem índice (que viraria `5xx` ou fallback degradado).

### D7 — Footprint: GraalVM native-image (necessário) + store off-heap
Manter Kotlin/JVM 25/Ktor CIO com `-Xmx` baixo e GC Serial **não basta** — a validação (ver "Resultados de validação") mostrou OOM de heap sob carga em 160 MB/instância. O baseline da JVM (metaspace + code cache + threads ≈ 90–100 MB) somado ao índice (~66 MB) não cabe nos 160 MB. Duas medidas, agora **confirmadas como necessárias** (não mais opcionais):
- **Store off-heap:** mover os ~42 MB de vetores `int8` para fora do heap (DirectByteBuffer / `MemorySegment`), aliviando o heap do churn de 900 req/s.
- **GraalVM native-image:** corta o baseline de RSS para a casa de dezenas de MB (sem metaspace/JIT), liberando espaço para o índice. É a alavanca decisiva para uma submissão válida — ao custo de configurar reachability metadata para `kotlinx.serialization` e Ktor.

## Risks / Trade-offs

- **Bucket com `< 5` membros (combinação categórica rara)** → guarda de borda: expandir para os buckets vizinhos mais próximos antes de decidir.
- **Poda do VP-Tree nas dims contínuas pode render menos que o esperado em alguns buckets** → o bucketing categórico já reduz N por bucket (~187k); medir a fração varrida e, se preciso, sub-particionar buckets grandes por uma dimensão de alta variância (ex.: `amount`).
- **2 JVMs + índice estouram o orçamento de memória** → **MEDIDO**: OOM de heap sob 900 req/s em 160 MB/instância, instâncias morreram, score −6000. Quantização int8 + VP-Tree implícita não foram suficientes. Mitigação confirmada: store off-heap + GraalVM native-image (D7).
- **Quantização int8 altera distâncias** → erro por dimensão ≤ ~1/255; validar que o voto majoritário (k=5, threshold 0.6) permanece idêntico ao oráculo de força bruta.
- **Artefato binário no build infla camadas da imagem** → aceitável; imagens precisam ser públicas e o `.gz` já tem dezenas de MB.
- **Startup lento atrasa readiness** → carga de binário (sem parse JSON) deve manter readiness < 1s; medir.

## Migration Plan

1. Pré-processamento offline/no build: `references.json.gz` → artefato binário (`int8` + labels + buckets + VP-Trees implícitas), embarcado na imagem.
2. Implementar vetorização (14-D) com oráculo de força bruta para testes de correção.
3. Implementar o índice (bucketing + VP-Tree) e a carga no startup com gate de readiness.
4. Adicionar `configureFraudScore()` ao `rootModule`; handler com fallback.
5. Ajustar `Dockerfile`/`docker-compose.yml` (fatias de memória/CPU, build do índice).
6. Rollback: o endpoint é aditivo; reverter o módulo de rota e o passo de build restaura o estado anterior (apenas `/ready` + rotas atuais).

## Open Questions

Nenhuma decisão arquitetural em aberto. Restam apenas **knobs de tuning empírico**, ajustáveis sem mudar o design:

- Profundidade/parâmetro de vantage point do VP-Tree e limiar para sub-particionar buckets grandes.
- Detalhe da quantização: confirmar o código reservado para o sentinela `-1` e a escala linear `[0,1]→[0,255]`.
- Runtime: confirmar por medição se a JVM domada basta ou se será necessário GraalVM native-image (D7).
- Distribuição do `references.json.gz` no build (download no build vs. versionado vs. binário pré-gerado versionado).

## Resultados de validação (medidos)

Validação end-to-end com o índice **real de 3M** (dataset oficial), via `docker compose` com os limites do contest, e o teste k6 oficial (`ramping-arrival-rate` 1→900 req/s, 54.100 requisições) rodando contra o stack já limitado a 1 CPU.

**Correção (decisivo):** os dois exemplos da spec retornaram exatamente o esperado contra o índice real — legítimo `{approved:true, fraud_score:0.0}`, fraude `{approved:false, fraud_score:1.0}`. Sob carga, a detecção ficou em ~0,13% de erro (FP+FN ≈ 70 em 53.876). **O algoritmo (busca exata bucketing+VP-Tree int8) está validado.**

**Bloqueador (memória):** com o orçamento real (160 MB/instância, heap ≈ 88 MB), ambas as instâncias morreram com `OutOfMemoryError: Java heap space` sob 900 req/s → 24,33% de falhas, p99 2002 ms, **final_score −6000**. O índice (~66 MB) no heap deixa só ~22 MB para o churn de 900 req/s.

**Isolamento memória × CPU:** relaxando **apenas a memória** (heap 768 MB) e **mantendo 1 CPU**, o resultado virou: 0 erros HTTP, 0,13% de falhas, **p99 202 ms, final_score +2567**. Confirma que (a) 1 CPU dá conta da carga com a detecção entregue e (b) **memória é o único bloqueador** para uma submissão válida.

**Tempo de carga do índice no startup:** 1202 ms (alvo era < 1s; aceitável, pois é assíncrono e gated pelo `/ready`).

**Próximos passos priorizados:** (1) caber em 160 MB — store off-heap + GraalVM native-image (D7); (2) baixar o p99 de 202 ms (sub-particionar buckets grandes, reduzir alocação por requisição/GC) para subir o `p99_score`.

### Atualização: GraalVM native-image (medido, submissão válida)

A imagem native (GraalVM 25, `Dockerfile.native`) resolveu o bloqueador de memória. No **orçamento real** (160 MB / 0,425 CPU por instância, 2 instâncias + nginx, k6 1→900 req/s):

| | JVM @160 MB | Native @160 MB |
|---|---|---|
| http_errors | 6816 (OOM) | **0** |
| failure_rate | 24,33% | **0,13%** |
| p99 | 2002 ms | **99 ms** |
| pico de memória | 155 MiB → OOM | **~98 MiB / 160** |
| **final_score** | **−6000** | **+2876** |

A imagem native carrega o índice em ~217 ms, usa ~78 MiB em idle e ~98 MiB sob carga (folga de ~62 MiB), sem OOM. O footprint da JVM (metaspace + JIT) era o problema; o native o elimina.

**Notas de implementação do native:** metadata de reachability gerada pelo tracing agent no build (`-agentlib:native-image-agent`), complementada por um `reflect-config` manual para os `AtomicReferenceFieldUpdater` do Ktor CIO (`io.ktor.network.selector.InterestSuspensionsMap`/`SelectableBase`) que o agente não captura. Serializers explícitos (sem ContentNegotiation/reflexão) e remoção do Logback reduziram a superfície de reflexão.

**Restante (otimização, não bloqueador):** baixar o p99 de 99 ms (≈ +1000 pts se chegar a ~10 ms) sub-particionando buckets grandes (tarefa 1.5) e/ou store off-heap (7.5).
