# Suporte a C/C++ embutido no app principal — status atual

## Feito (verificado arquivo por arquivo nesta sessão, não é suposição)

1. **Editor**: `.c/.h/.cpp/.cs` reconhecidos + coloração real (`NativeLanguagesUiPlugin.kt` + `BuiltInPlugins.kt`).
2. **`PluginManifest.usesHostNativeLibrary`** já existe em `plugin-api` (documentado como SPI `2.10.1`), e
   `ApplicationEnvironment.hostNativeLibraryDir` já resolve o diretório nativo real do próprio APK para um
   built-in que ativa essa flag.
3. **Os 11 arquivos de `samples/ndk-plugin` já foram copiados para `app/ide-core/src/main/kotlin/dev/codeassist/ndk/`**
   (todos, exceto `NdkUiPlugin.kt` e `PluginInfoActivity.kt`, de propósito — ver nota abaixo). O `NdkPlugin.kt`
   de lá já tem `override val manifest = PluginManifest(id = "ndk-native", ..., usesHostNativeLibrary = true)`.
4. **`BuiltInPlugins.kt`** já importa e já registra `BuiltInPlugin(NdkPlugin())` na lista `assemble()`, com
   comentário explicando por que não tem UI facet própria (coberta por `NativeLanguagesUiPlugin`).
5. **`app/ide-android/build.gradle.kts`** já empacota o toolchain: `sourceSets["main"].jniLibs.srcDir(ndkToolchainLibs)`,
   `assets.srcDir(ndkToolchainAssetsDir)`, e a task `packToolchainAssets` já zipa headers/libs de link. Lê de
   `tools/ndk-toolchain/build/out` por padrão (ou `-Pndk.toolchain.dir=...`).

Ou seja: os 4 passos "mecânicos" que uma sessão anterior tinha deixado documentados como pendentes **já
foram todos concluídos** — por esta sessão ou por uma anterior não registrada aqui. `NdkUiPlugin.kt` continua
de fora de propósito (API pública incompatível com built-in; `NativeLanguagesUiPlugin` já cobre a mesma
coloração com o tipo interno correto).

## O que falta — não é mais código, é infraestrutura de build

1. **Os binários reais do LLVM (clang/lld) não existem no repositório.** `ndkToolchainLibs`/`ndkToolchainSourceAssets`
   apontam para `tools/ndk-toolchain/build/out`, que só existe depois de rodar
   `tools/ndk-toolchain/build-llvm-android.sh` — um build completo do LLVM cross-compilado para rodar em
   Android arm64, que leva horas e precisa de ~13GB de disco. Sem isso, o app compila e instala normalmente
   (via `apk.yml`, que roda em todo push), só que `NdkPlugin` reporta "no toolchain for this device's ABI"
   em vez de compilar C/C++.
2. **O workflow que builda esse toolchain (`READY_ndk-plugin-apk.yml`, na raiz do repo) ainda não está
   ativo.** Ele já foi corrigido nesta sessão para buildar `:ide-android:assembleDebug` (o app principal) em
   vez do app de exemplo separado `:samples:ndk-plugin`. Falta apenas MOVER o arquivo para
   `.github/workflows/ndk-toolchain-apk.yml` — nenhuma sessão do Claude conseguiu fazer isso via API porque
   o GitHub App conectado não tem o escopo de permissão `workflows`. O próprio arquivo tem as instruções
   (renomear pelo site/app do GitHub, ou `git mv` + push local).
3. Depois de mover o arquivo, rodar manualmente pela aba Actions ("Run workflow") — é `workflow_dispatch`,
   não roda sozinho. Esperar várias horas pelo job `build-toolchain`. Se faltar espaço em disco no runner, os
   passos de limpeza / a lista de targets do LLVM são o primeiro ajuste a fazer.

## Por que documentar isso agora

Sem este arquivo atualizado, a próxima sessão (ou a próxima leitura deste) recomeçaria do zero achando que
faltam os 4 passos de código — que não faltam mais. O único trabalho real restante é infraestrutura de CI
(mover 1 arquivo + esperar um build de LLVM rodar), não mais escrita de Kotlin.
