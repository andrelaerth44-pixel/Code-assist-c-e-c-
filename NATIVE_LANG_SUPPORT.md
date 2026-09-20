# Suporte a C, C++ e C# no CodeAssist — estado final desta sessão

Este arquivo documenta o estado real do fork `andrelaerth44-pixel/Code-assist-c-e-c-` (fork completo de
`tyron12233/CodeAssist`) para C/C++/C#, e exatamente o que falta para compilar de verdade.

## 1. Editor (feito, já commitado)

`app/ide-core/.../plugins/NativeLanguagesUiPlugin.kt` + entrada em `BuiltInPlugins.kt`:
- `.c/.h/.cpp/.cc/.cxx/.hh/.hpp/.hxx/.cs` reconhecidos como suas próprias linguagens (não caem mais como
  Java quebrado)
- Coloração de sintaxe real via `EditorLanguageProfile`/`SyntaxFamily.C_FAMILY` (keywords, comentários,
  diretivas de pré-processador `#include`/`#define`)
- Plugin não-essencial, ativável/desativável em Settings → Plugins

Isso é só reconhecimento + coloração. Não compila nada sozinho.

## 2. Compilação de verdade (já existe no upstream, já está no seu fork)

**Achado importante:** o mecanismo real para "Kotlin/Java na UI + C/C++ no motor, no MESMO app" já existe,
pronto, em `samples/ndk-plugin` (já presente no seu fork por ser um fork de verdade, confirmado em
`settings.gradle.kts`):

- `NdkFacet` — tabela `[ndk]` no `module.toml` de um módulo Android Kotlin/Java normal (pastas de fonte,
  ABIs, padrão C/C++)
- `NdkBuildPlugin` — um `BuildPlugin` real que compila e linka o `.so` e mescla direto no APK do módulo,
  "wired ahead of the Android packaging merge"
- `NdkDiagnosticProvider` / `NdkCompletionContributor` — diagnósticos e completion reais do clang no editor
- `NativeCppTemplate` / `NativeActivityTemplate` — templates de projeto 100% C++ (aparecem sozinhos na
  galeria Create Project assim que o plugin está ativo, via `ProjectTemplateExtensionPoint` — não precisa de
  UI nova)

Ou seja: o fluxo "app Kotlin + engine C++" que você descreveu (Canvas de desenho, pincéis em C++, UI em
Kotlin) é literalmente o caso de uso para o qual esse plugin foi desenhado.

## 3. O que falta, na ordem real

1. **Os binários do compilador não existem ainda** (nem no seu fork, nem no oficial). `tools/ndk-toolchain/
   build-llvm-android.sh` compila um clang+lld+llvm-binutils que RODAM em Android arm64 (não é download —
   ver `tools/ndk-toolchain/README.md`: o NDK do Google só tem clang de desktop). Precisa de cmake, ninja,
   curl, ~13 GB livres. É pesado (LLVM inteiro, ainda que só o alvo AArch64) — pode levar horas.
2. **Um segundo APK precisa ser compilado**: `samples/ndk-plugin` é de propósito um app Android separado
   (`dev.codeassist.ndk`) — não faz parte do APK principal do CodeAssist, para não inchar o app de quem não
   usa C/C++. Instalar os dois (CodeAssist + esse plugin) é o fluxo esperado, não um problema.

## 4. O que já está pronto para os dois passos acima

`READY_ndk-plugin-apk.yml` (raiz do repositório) — um workflow COMPLETO com os dois jobs (build do
toolchain via `build-llvm-android.sh`, depois `:samples:ndk-plugin:assembleDebug` já apontado para o output
do primeiro job). Não pude colocá-lo em `.github/workflows/` nesta sessão porque o GitHub App conectado
aqui não tem o escopo `workflows` (testado e confirmado três vezes ao longo desta conversa: fork bloqueado,
edição de workflow existente bloqueada, e agora criação de workflow novo também). É literalmente um
`git mv READY_ndk-plugin-apk.yml .github/workflows/ndk-plugin-apk.yml` (ou colar o conteúdo, abaixo da linha
`---`, num arquivo novo pela própria interface do GitHub) e rodar pela aba Actions → Run workflow.

O APK principal do CodeAssist (com o suporte de editor para C/C++/C# já embutido) continua compilando
sozinho a cada push, sem precisar de nada disso — `.github/workflows/apk.yml` já existia e não foi tocado.

## 5. C#

Confirmado (busquei duas vezes no upstream): zero menção a Mono/.NET no projeto inteiro. Fora de escopo por
decisão sua também ("pode deixar de fora o C Sharp"). O reconhecimento de arquivo + coloração de sintaxe
para `.cs` continuam ativos (item 1), só não há — nem há planos de haver — compilação real.
