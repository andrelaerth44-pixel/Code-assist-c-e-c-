# Suporte a C, C++ e C# no CodeAssist — estado real (atualizado)

Este arquivo documenta o que foi feito de verdade neste fork (`andrelaerth44-pixel/Code-assist-c-e-c-`,
fork completo de `tyron12233/CodeAssist`), com base na arquitetura real do projeto (não em suposição).

## Feito e verificado

- `app/ide-core/.../plugins/NativeLanguagesUiPlugin.kt` — `NativeLanguagesPlugin` (registra `.c/.h/.cpp/.cc/
  .cxx/.hh/.hpp/.hxx/.cs` em `FILE_TYPE_EP`) + `NativeLanguagesUiPlugin` (perfis `EditorLanguageProfile` com
  `SyntaxFamily.C_FAMILY`: coloração de keywords, comentários `//` `/* */`, e `directivePrefix = "#"` para
  `#include`/`#define` em C/C++). Plugin não-essencial, ativável/desativável em Settings → Plugins, seguindo o
  mesmo padrão unificado engine+UI que `AgentPlugin`/`VcsPlugin` usam.
- `app/ide-core/.../plugins/BuiltInPlugins.kt` — `PlatformPlugin` voltou ao original; a entrada nova
  `BuiltInPlugin(NativeLanguagesPlugin(), ui = NativeLanguagesUiPlugin)` foi adicionada em `assemble()`.

**Correção importante:** numa mensagem anterior eu disse que `EditorLanguageProfile`, `SyntaxFamily` e
`UiContributionScope` não existiam no upstream e que por isso o workflow antigo (`Code-assist-`, o outro
repositório) estava quebrado. Isso estava errado — verifiquei o código-fonte real
(`app/ide-ui-api/.../ext/EditorLanguages.kt` e `UiPlugin.kt`) e essas três classes são reais e é exatamente
esse o padrão certo, documentado em `docs/custom-language-support.md`. O que essas três classes fazem é
coloração de sintaxe (nível "Tier 1" na tabela oficial de capacidades) — não compilação.

## O que isso entrega e o que NÃO entrega

Isso dá reconhecimento de arquivo + coloração de sintaxe + comentários. **Não compila nada.** Pela própria
tabela de capacidades do projeto (`docs/custom-language-support.md`, seção 1):

| Capacidade | O que o usuário ganha | O que foi feito aqui |
| --- | --- | --- |
| File type | arquivo abre como sua própria linguagem | ✅ feito |
| Coloração/comentários | keywords coloridas, Toggle Comment | ✅ feito |
| Diagnósticos | erros/avisos ao digitar | ❌ (precisa de um `Analyzer`) |
| Parsing/resolução | DOM, go-to-definition | ❌ (precisa de um `LanguageBackend` completo — "Tier 2", semanas de trabalho) |
| Completion | popup de autocompletar | ❌ |
| **Compilação** | **o app do usuário compila** | ❌ — precisa de um `BuildPlugin` (`BUILD_PLUGIN_EP`) real |

## O caminho real para compilação (verificado em docs/custom-language-support.md §12)

A peça que falta é um `BuildPlugin` que registre uma `Task` (`:app:compileNative`) na `assemble()` do módulo,
com `TaskInputs`/`TaskOutputs` declarados (para incrementalidade) e um `execute(ctx: TaskContext)` que invoca
o compilador. A arquitetura de tasks é real e bem documentada — o que continua faltando, e que nenhum commit
de código sozinho resolve, é o **binário do compilador C/C++ rodando dentro do Android** (o app não pode
depender de um NDK de desktop). Sem esse binário, um `BuildPlugin` para C/C++ pode ser escrito, mas não tem o
que invocar em `execute()`.

C# continua fora de escopo pelo mesmo motivo de sempre: não há caminho leve para rodar C# em Android sem
embutir Mono/.NET inteiro — ordem de grandeza maior que o problema do toolchain C/C++.

## Limitações desta sessão (conector GitHub via chat)

Sem fork automático (403) e sem escrita em `.github/workflows/` (403, falta o escopo `workflows` na
instalação do GitHub App). Isso não afeta os commits acima (foram em arquivos de código comuns), mas afeta
disparar/validar um build real — não há aqui uma ferramenta para rodar Actions. O próximo passo real
(bundlar/portar um toolchain, escrever o `BuildPlugin`, e efetivamente compilar e testar) precisa de um
ambiente com `git`/build real — Claude Code local resolve isso sem as limitações de escopo deste conector.
