## ADDED Requirements

### Requirement: Topologia mínima com load balancer e 2 instâncias da API
O sistema SHALL ser entregue como um único `docker-compose.yml` na raiz do repositório, contendo exatamente 1 serviço de load balancer (`lb`) e exatamente 2 serviços de API (`api-1`, `api-2`), todos conectados a uma única rede `bridge` chamada `rinha`.

#### Scenario: docker compose up sobe os três serviços
- **WHEN** o operador executa `docker compose up --build` na raiz do projeto
- **THEN** os serviços `lb`, `api-1` e `api-2` sobem com status `running` em até 60 segundos
- **AND** apenas o serviço `lb` publica porta no host

#### Scenario: rede em modo bridge é a única declarada
- **WHEN** o `docker-compose.yml` é inspecionado
- **THEN** existe exatamente uma rede declarada com `driver: bridge`
- **AND** nenhum serviço usa `network_mode: host` ou `privileged: true`

### Requirement: Porta pública 9999 no load balancer
O serviço `lb` SHALL expor a porta `9999` no host e MUST encaminhar todo tráfego HTTP recebido para o `upstream` formado por `api-1:8080` e `api-2:8080`.

#### Scenario: requisição chega via 9999
- **WHEN** um cliente externo faz `GET http://localhost:9999/ready`
- **THEN** o load balancer encaminha a requisição para uma das instâncias `api-1` ou `api-2`
- **AND** retorna ao cliente a resposta da instância selecionada

#### Scenario: portas das APIs não são publicadas no host
- **WHEN** `docker compose ps` é executado
- **THEN** apenas o serviço `lb` aparece com mapeamento de portas (`0.0.0.0:9999->9999/tcp`)
- **AND** `api-1` e `api-2` não têm portas publicadas

### Requirement: Distribuição round-robin simples sem lógica de negócio
O load balancer SHALL distribuir requisições entre `api-1` e `api-2` usando estratégia **round-robin simples** e MUST NOT inspecionar payloads, aplicar condicionais, transformar corpos de mensagem ou responder antes de repassar.

#### Scenario: requisições alternam entre as duas instâncias
- **WHEN** 10 requisições idênticas são enviadas para o load balancer em sequência
- **THEN** aproximadamente 5 requisições são logadas em `api-1` e 5 em `api-2` (tolerância de ±1)

#### Scenario: configuração do balanceador é declarativa pura
- **WHEN** o arquivo de configuração do load balancer (`nginx/nginx.conf`) é inspecionado
- **THEN** o bloco `upstream` lista `api-1:8080` e `api-2:8080` sem diretivas `weight`, `hash`, `ip_hash` ou `least_conn`
- **AND** o `location /` usa apenas `proxy_pass` sem `if`, `map` condicional ou reescrita de corpo

### Requirement: Envelope total de recursos ≤ 1 CPU e 350 MB
A soma de `deploy.resources.limits.cpus` e `deploy.resources.limits.memory` de todos os serviços SHALL ser ≤ `1.0` CPU e ≤ `350MB` de memória.

#### Scenario: limites estão declarados em todos os serviços
- **WHEN** `docker-compose.yml` é inspecionado
- **THEN** cada serviço (`lb`, `api-1`, `api-2`) possui `deploy.resources.limits.cpus` e `deploy.resources.limits.memory` explicitamente declarados

#### Scenario: somatório respeita o teto
- **WHEN** os valores de `cpus` são somados
- **THEN** o total é ≤ `1.0`
- **WHEN** os valores de `memory` são somados (normalizados para MB)
- **THEN** o total é ≤ `350MB`

### Requirement: Compatibilidade linux/amd64
Todas as imagens declaradas em `docker-compose.yml` SHALL ser compatíveis com a arquitetura `linux/amd64` e MUST estar publicamente acessíveis em um registry público (Docker Hub, GHCR ou equivalente).

#### Scenario: imagens são puxáveis sem autenticação
- **WHEN** `docker pull <imagem>` é executado para cada imagem referenciada (incluindo a imagem buildada localmente, quando publicada)
- **THEN** o pull conclui sem solicitar credenciais

#### Scenario: build local respeita a plataforma
- **WHEN** o `Dockerfile` é construído em um host ARM64
- **THEN** o build usa `--platform=linux/amd64` ou diretiva equivalente
- **AND** a imagem resultante roda em runtime `linux/amd64`
