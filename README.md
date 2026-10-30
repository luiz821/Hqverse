# HQVerse

Biblioteca e leitor de HQs para Android, 100% offline. Você escolhe uma pasta do celular e o app organiza a coleção: capas, séries, progresso de leitura, marcadores, coleções, estatísticas e backup. Sem conta, sem anúncios, sem permissão de internet.

> **Estado do projeto — leia antes de compilar.**
> O código foi escrito em um ambiente **sem Android SDK e sem acesso ao Maven/Google**, portanto:
> - A lógica pura (módulo `core`) foi **compilada e testada de verdade**: 89 testes JUnit, todos passando.
> - A camada Android (`app`: Room, Compose, SAF, PdfRenderer, junrar, WorkManager) **ainda não foi compilada nem executada**. Espere ajustar alguns erros de compilação e versões de dependências na primeira build. Nenhum APK foi gerado.
> - As versões em `gradle/libs.versions.toml` foram escolhidas de memória; se o Gradle não as encontrar, o Android Studio sugere a mais próxima.

## Requisitos

- Android Studio Ladybug (2024.2) ou mais novo, ou apenas JDK 17 + Android SDK (plataforma 35 e build-tools).
- Dispositivo ou emulador com Android 8.0 (API 26) ou superior.
- Internet apenas na primeira compilação, para baixar dependências.

## Estrutura

```
HQVerse/
├── core/                  Kotlin/JVM puro, sem Android. Testado aqui.
│   └── src/main/kotlin/com/hqverse/core/
│       ├── Formats.kt         detecção por bytes (PDF/ZIP/RAR/RAR5), erros com mensagem em português
│       ├── FileNameParser.kt  título, série, edição, volume e ano a partir do nome do arquivo
│       ├── PageOrdering.kt    ordem natural de páginas, filtro de lixo (__MACOSX, Thumbs.db…)
│       ├── PageSource.kt      leitor de CBZ com acesso aleatório
│       ├── ArchiveSafety.kt   limites contra zip-bomb, zip-slip, leitura limitada
│       ├── LibraryDiff.kt     indexação incremental: novo / alterado / movido / ausente
│       ├── LibraryQuery.kt    busca sem acento, filtros, ordenação, agrupamento por série
│       ├── Progress.kt        progresso, regra de conclusão, ritmo, tempo restante, sessões
│       ├── Stats.kt           sequências de dias, gráficos semanais/mensais, coleções
│       ├── IntRangeSet.kt     páginas únicas lidas ("1-12,15")
│       ├── MiniJson.kt        JSON próprio com limites de profundidade/tamanho
│       └── Backup.kt          codec, validação e reassociação de backup
├── app/                   Aplicativo Android (Jetpack Compose + Material 3)
│   └── src/main/kotlin/com/hqverse/app/
│       ├── data/db/       Room: entidades, DAOs, migrações
│       ├── data/settings/ DataStore
│       ├── data/files/    Storage Access Framework, cache em disco com limite
│       ├── data/reader/   PdfRenderer, CBZ, CBR (junrar), pasta de imagens, PageLoader (cache LRU)
│       ├── data/scan/     LibraryIndexer, ScanCoordinator, ScanWorker (WorkManager)
│       ├── data/repo/     Library, Progress, Stats, Backup
│       └── ui/            telas, ViewModels, tema, navegação
├── tools/core-test.sh     compila e testa o core sem Gradle nem rede
└── .github/workflows/build.yml   CI que gera o APK de depuração
```

Arquitetura: MVVM com injeção de dependências manual (`AppContainer`). Regras de negócio ficam no `core` para serem testáveis na JVM.

## Como abrir e compilar

1. Abra a pasta `HQVerse` no Android Studio e aguarde a sincronização do Gradle.
2. Se alguma dependência não for encontrada, ajuste a versão em `gradle/libs.versions.toml`.
3. Execute o app em um dispositivo/emulador (botão ▶).

Pela linha de comando (com `ANDROID_HOME` apontando para o SDK):

```bash
./gradlew :core:test            # testes da lógica
./gradlew :app:assembleDebug    # APK de depuração
```

O APK de depuração fica em `app/build/outputs/apk/debug/app-debug.apk` (o aplicativo de depuração usa o id `com.hqverse.app.debug`, então convive com a versão de lançamento).

Sem Android SDK, só a lógica pode ser testada:

```bash
tools/core-test.sh              # usa o compilador Kotlin e o JUnit que acompanham o Gradle
```

### CI

O workflow `.github/workflows/build.yml` roda os testes e publica o APK de depuração como artefato a cada push. É a forma mais simples de obter o APK sem instalar o Android Studio: suba o projeto para um repositório no GitHub e baixe o artefato na aba *Actions*.

## Instalar no celular

1. Copie `app-debug.apk` para o celular.
2. Abra o arquivo; se o Android pedir, permita "instalar apps desconhecidos" para o app que você usou (Arquivos, navegador…).
3. Abra o HQVerse, toque em **Escolher pasta** e selecione a pasta das suas HQs.

Ou, com o celular em modo depuração USB: `adb install app/build/outputs/apk/debug/app-debug.apk`.

## APK de lançamento assinado

A chave **não** está no projeto e nunca deve ser versionada.

```bash
keytool -genkeypair -v -keystore hqverse-release.jks -alias hqverse \
        -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # edite com seus dados
./gradlew :app:assembleRelease
```

Saída: `app/build/outputs/apk/release/app-release.apk`. Sem `keystore.properties`, o release sai **sem assinatura** e não instala. Guarde o `.jks` e as senhas em local seguro: sem eles não há como atualizar o app.

## Permissões e privacidade

- **Nenhuma** permissão de armazenamento ou de internet. O acesso às pastas é concedido por você no seletor do sistema e mantido após reiniciar.
- Nenhum rastreamento, nenhuma conta, nenhum envio de arquivos.
- Marcadores, anotações e progresso ficam no banco do app; os arquivos originais nunca são alterados.
- Remover uma pasta da biblioteca não apaga os arquivos. Apagar um arquivo físico exige confirmação explícita.
- Backup automático do Android desativado; o backup é o arquivo JSON que você exporta.

## Formatos

| Formato | Como é lido | Situação |
|---|---|---|
| PDF | `PdfRenderer` do Android | Implementado, **não executado** em dispositivo |
| CBZ | `java.util.zip`, acesso aleatório | Implementado; leitura e limites **testados na JVM** |
| CBR (RAR4) | junrar | Implementado, **não executado** |
| CBR (RAR5) | — | **Não suportado**: o app detecta e mostra uma mensagem pedindo conversão |
| Pasta de imagens | SAF | Implementado, **não executado** |
| JPG/PNG/WebP | como páginas | Implementado, **não executado** |
| EPUB e outros | — | Não implementado |

O formato é decidido pelo **conteúdo** do arquivo, não pela extensão: um `.cbr` que na verdade é ZIP abre como CBZ.

## Testes executados

`tools/core-test.sh` — **89 testes, 0 falhas**, cobrindo:

- Nomes de arquivo (`Batman 001.cbr`, `Homem-Aranha - Edição 15.pdf`, `Saga Volume 01.cbz`, `X-Men 2024 003.cbr` e variações)
- Detecção de formato por bytes, incluindo RAR4 × RAR5 e CBR que é ZIP
- Ordem de páginas em subpastas, descarte de lixo, números enormes
- Progresso, regra de conclusão (abrir a última página sozinho não conclui), tempo restante com histórico e com ritmo padrão
- Sessões: pausa longa, segundo plano, pausa manual, revisitas, recuperação após encerramento inesperado
- Indexação incremental, arquivo movido/renomeado, associação ambígua, pasta vazia, 30 mil arquivos
- Busca/filtros/ordenação/séries, 40 mil HQs
- CBZ: inexistente, corrompido, truncado, vazio, sem imagens, **protegido por senha**, zip-bomb, tamanho por página, zip-slip
- Backup: ida e volta, validação de campos, JSON malformado e aninhamento excessivo, reassociação por nome+tamanho
- Marcadores e coleções: apenas o cálculo de progresso/ausentes (ver abaixo)

## Limitações conhecidas

- **Camada Android não compilada nem executada** (ver aviso no topo). Isso inclui: fluxo de navegação, leitor, zoom e gestos, geração de capas, PDF com senha (`PdfRenderer` lança `SecurityException`, tratado no código mas não exercitado), animações e acessibilidade com TalkBack.
- **Sem testes instrumentados** e sem testes dos DAOs do Room (exigem Android). Salvamento/recuperação de marcadores e coleções no banco, e a reabertura do app após interrupção, **não foram testados**; só a lógica pura por trás (sessões, checkpoint, progresso).
- **RAR5 não é lido** (limitação do junrar). CBR protegido por senha é detectado, mas sem teste real com arquivo RAR aqui.
- CBR e CBZ são copiados para o cache do app para permitir acesso a páginas isoladas; o cache tem limite configurável e é limpo automaticamente, mas HQs muito grandes ocupam espaço temporário.
- Busca de metadados online **não** foi implementada (nada é enviado à internet).
- Migrações de banco: o esquema está na versão 1; há a estrutura para migrações, sem nenhuma ainda.
- O desempenho em aparelhos modestos e a estimativa de memória do `PageLoader` (limites de 24–160 MB) são palpites razoáveis, não medidos.

## Licenças

- junrar — [licença própria permissiva, baseada em UnRAR com restrição de uso para recriar o algoritmo de compressão RAR](https://github.com/junrar/junrar); confirme a compatibilidade com o uso que você pretende antes de distribuir.
- AndroidX, Jetpack Compose, Room, DataStore, WorkManager — Apache 2.0.
# Hqverse
