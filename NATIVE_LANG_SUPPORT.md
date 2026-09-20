# Suporte a C/C++ SEM segundo APK — plano concreto (não executado ainda)

Investiguei o pedido de trazer o NDK para dentro do próprio `:ide-android`, sem precisar instalar o
`samples/ndk-plugin` como app separado. Resultado: **é arquiteturalmente possível e bem definido** — não é
mais uma parede de permissão, é engenharia real de framework, com risco real de quebrar o carregamento de
plugins inteiro se eu errar algo sem poder compilar para checar. Por isso não apliquei ainda; deixo o plano
exato abaixo.

## Por que hoje não funciona simplesmente movendo o código

`PluginRegistration.nativeLibrary()`/`nativeLibraryDir` resolvem para o diretório nativo do PACOTE
instalado — só que hoje só são preenchidos para um plugin instalado como APK separado.
`plugins/plugin-impl/.../PluginRegistrationImpl.kt` documenta isso explicitamente: **"null for a built-in"**.
Se eu só movesse `NdkPlugin`/`NdkToolchain` para `ide-core` sem mais nada, `reg.nativeLibrary()` sempre
voltaria null e o toolchain reportaria "no toolchain for this device's ABI" em TODO dispositivo — pior que
não ter feito nada, porque pareceria pronto e nunca funcionaria.

**Mas** o comentário em `PluginManager.kt` sobre esse mapa diz: *"Empty for built-ins (their libraries are
the IDE's own)"* — ou seja, a intenção original já reconhece que as bibliotecas de um built-in SÃO as do
próprio app; simplesmente nenhum built-in tinha precisado disso até agora. Não é uma restrição do Android
(o APK do `:ide-android` tem seu próprio `nativeLibraryDir` de instalação, igualmente válido para `exec()`),
é só um caminho do framework que nunca foi preenchido.

## O plano, arquivo por arquivo

1. **`app/ide-android`** (build.gradle.kts): copiar a lógica de empacotamento de `samples/ndk-plugin/
   build.gradle.kts` (`packToolchainAssets`, `sourceSets["main"].jniLibs.srcDir(...)`, `assets.srcDir(...)`)
   — as bytes do toolchain passam a entrar no APK principal em vez do plugin separado.
2. **Contexto do app até o `ApplicationEnvironment`**: em algum ponto de inicialização do `:ide-android`
   (Application/Activity) já deve existir um `Context`; falta passar
   `context.applicationInfo.nativeLibraryDir` para baixo até onde o `PluginManager` é construído.
3. **`app/ide-core/.../ApplicationEnvironment.kt`**: ao montar o `PluginManager`, incluir no mapa
   `nativeLibraryDirs` a entrada `"native-languages-ndk" to hostNativeLibraryDir` (o mesmo diretório do host,
   não um path de plugin instalado).
4. **`BuiltInPlugins.kt`**: registrar `NdkPlugin()`/`NdkUiPlugin` (copiados de `samples/ndk-plugin`, mesmo
   pacote `dev.codeassist.ndk`) como um `BuiltInPlugin` com esse id.
5. **Galeria "Create Project"**: nada a fazer aqui além do passo 4 — `NativeCppTemplate`/
   `NativeActivityTemplate` já se registram via `ProjectTemplateExtensionPoint` dentro do próprio
   `NdkPlugin.register()`, e a galeria já lê esse extension point dinamicamente (confirmado em
   `docs/custom-language-support.md`). Nenhuma tela nova para escrever.

## Por que não apliquei isso ainda, nesta sessão

Os passos 1 a 3 alteram código central (`PluginManager`, `ApplicationEnvironment`, o `build.gradle.kts` do
app principal) sem eu conseguir compilar para checar — diferente dos commits anteriores desta sessão, que
seguiam um padrão já existente e comprovado linha por linha (`.aidl`/`.pro`/`.md`, `AgentPlugin`/`VcsPlugin`).
Aqui não há precedente exato para copiar; um erro de assinatura de função quebraria o carregamento de
plugins do app inteiro, não só o NDK. É o tipo de mudança que vale mais a pena fazer com um compilador do
lado — Claude Code local resolve isso sem essa limitação.
