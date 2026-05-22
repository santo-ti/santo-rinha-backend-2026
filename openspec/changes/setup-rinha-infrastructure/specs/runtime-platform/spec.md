## ADDED Requirements

### Requirement: Toolchain Kotlin compila para JDK 25
O `build.gradle.kts` SHALL declarar `kotlin { jvmToolchain(25) }` e MUST compilar sem warnings de incompatibilidade de bytecode.

#### Scenario: build com JDK 25 sucede
- **WHEN** `./gradlew clean assemble` é executado
- **THEN** o build conclui com `BUILD SUCCESSFUL`
- **AND** os arquivos `.class` gerados em `build/classes/` têm `major version` correspondente ao JDK 25 (69)

#### Scenario: testes existentes continuam passando após o bump
- **WHEN** `./gradlew test` é executado após a atualização da toolchain
- **THEN** todos os testes pré-existentes em `ServerTest.kt` (incluindo `test root endpoint`) passam

### Requirement: Imagem de runtime usa JRE 25
A imagem final descrita no `Dockerfile` SHALL ser baseada em uma distribuição oficial de Temurin (ou equivalente) com JRE 25 para `linux/amd64`, e MUST NOT incluir o JDK completo no estágio final.

#### Scenario: estágio final usa eclipse-temurin:25-jre-*
- **WHEN** o `Dockerfile` é inspecionado
- **THEN** a última instrução `FROM` referencia uma imagem `eclipse-temurin:25-jre-*` (alpine ou noble)

#### Scenario: javac não está disponível na imagem final
- **WHEN** `docker run --rm <imagem> which javac` é executado
- **THEN** o comando retorna código de saída diferente de zero (javac ausente)

### Requirement: Build é multi-stage com fat jar
O `Dockerfile` SHALL usar build multi-stage onde o estágio `builder` produz o fat jar via `./gradlew buildFatJar` e o estágio final copia somente o artefato necessário (`build/libs/*-all.jar` ou equivalente).

#### Scenario: imagem final não contém código-fonte
- **WHEN** `docker run --rm <imagem> ls /app` é executado
- **THEN** a saída lista apenas o jar da aplicação (e.g. `app.jar`)
- **AND** não existem diretórios `src/`, `build/`, `.gradle/` ou similares na imagem final

#### Scenario: tamanho da imagem final é razoável
- **WHEN** `docker image inspect <imagem>` é executado
- **THEN** o campo `Size` é inferior a 200 MB
