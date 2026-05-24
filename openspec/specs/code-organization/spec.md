# Especificação: code-organization

## Purpose
Define como o código-fonte do projeto é organizado: pacotes nomeados por camada/intenção (não pelo jargão do algoritmo), o domínio de detecção de fraude visível e isolado da infraestrutura de busca, e a separação das zonas de execução (produção, teste e build offline) de modo que o pacote de produção contenha apenas o que executa em uma requisição.

## Requirements

### Requirement: Pacotes organizados por camada e intenção

O código-fonte em `src/main` SHALL ser organizado em pacotes que nomeiam a camada/intenção, não o jargão do algoritmo. A topologia MUST ser:

- `dev.santo.bootstrap` — composição e inicialização da aplicação (`main`, `rootModule`, `AppComponents`, `IndexLoader`)
- `dev.santo.api` — borda HTTP (rotas)
- `dev.santo.dto` — DTOs neutros de request/response, compartilhados pela borda HTTP e pelo domínio (não pertencem a nenhuma camada)
- `dev.santo.fraud` — domínio de detecção de fraude
- `dev.santo.vectorization` — extração de features da transação
- `dev.santo.search` — motor de busca por vizinhos (k-NN)
- `dev.santo.tools` — build-tooling offline

#### Scenario: Pacote da busca não usa nome opaco

- **WHEN** um leitor inspeciona `src/main/kotlin`
- **THEN** o motor de busca está em `dev.santo.search` (não em `dev.santo.index`)
- **AND** os DTOs HTTP estão no pacote neutro `dev.santo.dto` (não em `dev.santo.api.dto` nem em `dev.santo.model`)

#### Scenario: Cada arquivo declara o pacote da sua camada

- **WHEN** qualquer arquivo `.kt` de produção é aberto
- **THEN** sua declaração `package` corresponde a um dos pacotes de camada listados acima
- **AND** nenhum arquivo de produção permanece no pacote raiz `dev.santo`

#### Scenario: Request e response em arquivos separados

- **WHEN** um leitor abre o pacote `dev.santo.dto`
- **THEN** `FraudScoreRequest` (com seus sub-DTOs `Transaction`, `Customer`, `Merchant`, `Terminal`, `LastTransaction`) reside em `FraudScoreRequest.kt`
- **AND** `FraudScoreResponse` reside em `FraudScoreResponse.kt`
- **AND** nenhum arquivo mistura tipos de request e de response

### Requirement: Domínio não depende da camada de API

Os pacotes de domínio e de processamento (`dev.santo.fraud`, `dev.santo.vectorization`) SHALL NOT importar a camada de API (`dev.santo.api`). Os tipos compartilhados entre a borda HTTP e o domínio MUST residir no pacote neutro `dev.santo.dto`, de modo que a dependência aponte sempre da camada externa para a interna (ou para o pacote neutro), nunca de dentro para fora. O composition root `dev.santo.bootstrap`, que monta a aplicação inteira, é a única exceção autorizada a importar a camada de API.

#### Scenario: Domínio importa o pacote neutro, nunca a API

- **WHEN** se rastreiam os imports de `dev.santo.fraud` e `dev.santo.vectorization`
- **THEN** os DTOs vêm de `dev.santo.dto`
- **AND** nenhum desses pacotes importa `dev.santo.api`

#### Scenario: Apenas a borda HTTP e o composition root conhecem a API

- **WHEN** se procura por imports de `dev.santo.api` no código de produção
- **THEN** eles ocorrem apenas dentro do próprio pacote `dev.santo.api` ou no composition root `dev.santo.bootstrap`, que monta a aplicação inteira e legitimamente importa todas as camadas
- **AND** nenhum pacote de domínio ou de processamento (`dev.santo.fraud`, `dev.santo.vectorization`) importa `dev.santo.api`

### Requirement: Domínio de fraude visível e isolado

O domínio de detecção de fraude SHALL residir no pacote `dev.santo.fraud`, separado da infraestrutura de busca. As regras de decisão (número de vizinhos, threshold, cálculo do score, regra de aprovação) MUST estar em `fraud/FraudPolicy.kt`, e o serviço orquestrador MUST se chamar `FraudDetectorService`. Utilitários de infraestrutura (ex.: distância euclidiana) MUST NOT compartilhar arquivo com as regras de domínio.

#### Scenario: Regras de domínio fora da infraestrutura de busca

- **WHEN** um leitor procura as regras de decisão de fraude (`K_NEIGHBORS`, `FRAUD_THRESHOLD`, `isApproved`, `fraudScore`)
- **THEN** elas estão em `dev.santo.fraud.FraudPolicy`
- **AND** não estão misturadas com funções de distância no antigo `index/Decision.kt`

#### Scenario: Serviço nomeado pela responsabilidade de domínio

- **WHEN** um leitor procura o orquestrador da detecção de fraude
- **THEN** ele se chama `FraudDetectorService` e reside em `dev.santo.fraud`

### Requirement: Separação das zonas de execução

O código SHALL ser separado por zona de execução para que o pacote de produção contenha apenas o que executa em uma requisição. Código usado exclusivamente como gabarito de teste MUST residir em `src/test`. Código usado exclusivamente no build offline MUST residir em `dev.santo.tools`. O runtime de produção MUST NOT depender do parser JSON do dataset de referência.

#### Scenario: Oráculos de teste fora da produção

- **WHEN** um leitor inspeciona `src/main`
- **THEN** `BruteForceIndex` e `QuantizedBruteForceIndex` não estão presentes
- **AND** ambos residem em `src/test`

#### Scenario: Runtime carrega o índice binário, nunca JSON

- **WHEN** se rastreiam as dependências do caminho de runtime (`bootstrap`, `api`, `fraud`, `vectorization`, `search`)
- **THEN** nenhuma delas importa o parser JSON `References`
- **AND** `References` reside em `dev.santo.tools` junto de `BuildIndex`

#### Scenario: Comentário de uso reflete a realidade

- **WHEN** o comentário de documentação de `BruteForceIndex` é lido
- **THEN** ele não afirma uso "during the offline index build" (o build usa `tools.IndexBuilder.build`)
