package dev.ide.core.plugins

import dev.ide.ui.ext.EditorLanguageProfile
import dev.ide.ui.ext.SyntaxFamily
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin

/**
 * Editor-side text profiles for C, C++ and C#: keyword coloring, line/block comments, and (for C/C++) the
 * `#include`/`#define` preprocessor-directive coloring [SyntaxFamily.C_FAMILY] supports natively via
 * [EditorLanguageProfile.directivePrefix]. This is the cheap per-line layer the profile is documented as --
 * no parser, no resolution: it is what colors the text while the user types, before any analysis lands.
 *
 * The suffix sets here MUST match the file-type mappings `dev.codeassist.ndk.NdkPlugin` registers for C and
 * C++ (`.c` is C; everything else, `.h` included, is C++ -- see NdkPlugin.CPP_SUFFIXES). C# has no compiler
 * and no other owner, so [NativeLanguagesPlugin] still claims `.cs`.
 */
object NativeLanguagesUiPlugin : UiPlugin {
    override val id = "native-languages"

    override fun contributeUi(scope: UiContributionScope) {
        scope.editorLanguage(
            EditorLanguageProfile(
                id = "c",
                suffixes = listOf(".c"),
                syntax = SyntaxFamily.C_FAMILY,
                keywords = C_KEYWORDS,
                lineComment = "//",
                blockCommentOpen = "/*",
                blockCommentClose = "*/",
                directivePrefix = "#",
            ),
        )
        scope.editorLanguage(
            EditorLanguageProfile(
                id = "cpp",
                suffixes = listOf(".cpp", ".cc", ".cxx", ".c++", ".h", ".hpp", ".hh", ".hxx", ".inl"),
                syntax = SyntaxFamily.C_FAMILY,
                keywords = C_KEYWORDS + CPP_ONLY_KEYWORDS,
                lineComment = "//",
                blockCommentOpen = "/*",
                blockCommentClose = "*/",
                directivePrefix = "#",
            ),
        )
        scope.editorLanguage(
            EditorLanguageProfile(
                id = "csharp",
                suffixes = listOf(".cs"),
                syntax = SyntaxFamily.C_FAMILY,
                keywords = CSHARP_KEYWORDS,
                lineComment = "//",
                blockCommentOpen = "/*",
                blockCommentClose = "*/",
            ),
        )
    }

    private val C_KEYWORDS = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
        "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return",
        "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
        "volatile", "while", "_Bool", "_Complex", "_Imaginary",
    )

    private val CPP_ONLY_KEYWORDS = setOf(
        "alignas", "alignof", "and", "and_eq", "asm", "bitand", "bitor", "bool", "catch", "class", "compl",
        "concept", "constexpr", "const_cast", "decltype", "delete", "dynamic_cast", "explicit", "export",
        "false", "friend", "mutable", "namespace", "new", "noexcept", "not", "not_eq", "nullptr", "operator",
        "or", "or_eq", "override", "private", "protected", "public", "reinterpret_cast", "requires",
        "static_assert", "static_cast", "template", "this", "thread_local", "throw", "true", "try", "typeid",
        "typename", "using", "virtual", "xor", "xor_eq",
    )

    private val CSHARP_KEYWORDS = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const",
        "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit",
        "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int",
        "interface", "internal", "is", "lock", "long", "namespace", "new", "null", "object", "operator", "out",
        "override", "params", "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
        "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", "try",
        "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void", "volatile",
        "while", "async", "await", "var", "dynamic", "get", "set", "value", "partial", "yield",
    )
}

/**
 * The C# file type (`.cs`), plus the host of [NativeLanguagesUiPlugin]'s coloring for all three languages.
 *
 * C and C++ file types are NOT registered here: `NdkPlugin` (the real compiler, `id = "ndk-native"`) owns
 * `.c` and the C++ suffixes, and two plugins claiming the same suffix would fight over which language id a
 * file gets. C# has no compiler and no other owner, so it stays here. Non-essential, so it can be turned off
 * from Settings -> Plugins like any other language.
 */
internal class NativeLanguagesPlugin : dev.ide.plugin.Plugin {
    override val manifest = dev.ide.plugin.PluginManifest(
        id = "native-languages",
        name = "C / C++ / C# (editor)",
        description = "C-family syntax coloring for C, C++ and C#, and the C# file type. The C/C++ compiler " +
            "itself is the separate 'C / C++ (NDK)' plugin.",
        dependsOn = listOf("jdt-language"),
    )

    override fun register(reg: dev.ide.plugin.PluginRegistration) {
        reg.register(dev.ide.lang.FILE_TYPE_EP, dev.ide.lang.FileTypeMapping(listOf(".cs"), dev.ide.lang.LanguageId("csharp")))
    }
}
