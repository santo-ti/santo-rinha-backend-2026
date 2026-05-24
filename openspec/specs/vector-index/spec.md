# Especificação: vector-index

## Purpose
Define o índice de busca vetorial: retorna os 5 vizinhos exatos mais próximos por distância euclidiana de forma sublinear, é pré-processado no build da imagem a partir do dataset de referência, cabe no orçamento de memória por instância (quantização `int8`), preserva o sentinela `-1` e carrega no startup antes de a instância sinalizar prontidão.

## Requirements

### Requirement: Busca retorna os 5 vizinhos mais próximos exatos por distância euclidiana
O índice SHALL retornar os 5 vetores de referência mais próximos do vetor de consulta segundo a distância euclidiana sobre as 14 dimensões. A busca MUST ser **exata** (resultado idêntico ao da força bruta usada no gabarito) e MUST ser sublinear (não percorrer os 3M vetores por consulta), porque o orçamento de ~1,1 ms por consulta em 1 CPU torna a força bruta inviável.

#### Scenario: consulta retorna os 5 vizinhos exatos rotulados
- **WHEN** um vetor de consulta de 14 dimensões é submetido ao índice
- **THEN** o índice retorna 5 referências, cada uma com seu rótulo (`fraud` ou `legit`)
- **AND** as 5 referências são exatamente as de menor distância euclidiana — idênticas às que a força bruta retornaria

#### Scenario: bucket com menos de 5 membros expande a busca
- **WHEN** o bucket da assinatura categórica da consulta tem menos de 5 referências
- **THEN** a busca expande para os buckets vizinhos mais próximos
- **AND** o resultado permanece igual ao da força bruta exata

### Requirement: Dataset de referência é pré-processado no build da imagem
O build SHALL converter `resources/references.json.gz` (~284 MB descomprimido / 3.000.000 vetores) em um artefato binário compacto embarcado na imagem, MUST evitar parse/descompressão de JSON no startup do runtime e MUST permitir que o startup apenas carregue o artefato.

#### Scenario: artefato binário é gerado no build
- **WHEN** a imagem é construída
- **THEN** um artefato binário do índice (vetores + rótulos + estrutura de busca) é produzido e embarcado na imagem
- **AND** o runtime não parseia `references.json.gz` em tempo de requisição nem no startup

### Requirement: Representação cabe no orçamento de memória por instância
O índice SHALL usar vetores quantizados em `int8` (≈42 MB por cópia, contra ≈168 MB em `float32`) de modo que cada instância de API carregue uma cópia independente dentro da sua fatia declarada de memória, sem depender de compartilhamento de page cache entre containers. A soma das fatias declaradas no `docker-compose.yml` MUST respeitar o limite de 350 MB para todos os serviços.

#### Scenario: instância carrega cópia independente dentro da fatia
- **WHEN** uma instância de API inicia com o artefato do índice
- **THEN** o consumo de memória do índice carregado cabe na fatia de memória declarada para aquela instância

### Requirement: Sentinela -1 é preservado no dataset de referência
O índice MUST preservar o valor `-1` nos índices `5` e `6` dos vetores de referência (transações sem histórico) e MUST NOT filtrar nem substituir esses valores, mantendo a mesma convenção da vetorização da consulta.

#### Scenario: referência sem histórico mantém -1
- **WHEN** um vetor de referência tem `-1` nos índices `5` e `6`
- **THEN** o índice armazena e compara esses valores como `-1`, sem substituição

### Requirement: Índice carrega no startup antes de sinalizar prontidão
A instância SHALL concluir a carga do índice e deixá-lo consultável antes de sinalizar prontidão em `GET /ready`, de modo que nenhuma requisição `POST /fraud-score` seja roteada para uma instância sem índice pronto.

#### Scenario: prontidão só após índice carregado
- **WHEN** a instância ainda está carregando o artefato do índice
- **THEN** o índice não está disponível para consulta
- **AND** a instância ainda não sinaliza prontidão em `GET /ready`
