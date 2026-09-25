package dev.codeassist.ndk

import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.build.BUILD_PLUGIN_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.CompletionContribution
import dev.ide.model.FACET_CODEC_EP
import dev.ide.model.FileIconExtensionPoint
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.platform.notify.USER_MESSAGES
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The engine facet: what this plugin contributes to the IDE that is not Compose.
 *
 * `register` runs at startup, before any project is open, and it deliberately does NOT touch the toolchain.
 * Unpacking ~2800 files is not something to do on the way to the first frame, and a device with no compiler
 * for its ABI should still get a plugin that loads and can say so. [NdkToolchain.prepare] is called from the
 * first thing that actually needs a compiler.
 *
 * BUILT-IN, not a separately-installed plugin app: the class this plugin was written against
 * (`samples/ndk-plugin`, an installable `dev.codeassist.ndk` APK) had no [manifest] of its own because the
 * external-plugin loader supplies one from the package's own descriptor. A built-in gets no such
 * substitution, so this override is the one real addition over that version -- everything else below is
 * unchanged. `usesHostNativeLibrary = true` is what lets [NdkToolchain] (via [PluginRegistration.nativeLibrary])
 * find the clang/lld this app now ships as its OWN `lib*.so`, the same way it already ships aapt2/zipalign.
 */
class NdkPlugin : Plugin {

    override val manifest = PluginManifest(
        id = "ndk-native",
        name = "C / C++ (NDK)",
        description = "On-device NDK toolchain: compiles and links C/C++ sources into a module's APK, with " +
            "editor diagnostics and completion from the same compiler.",
        dependsOn = listOf("jdt-language"),
        usesHostNativeLibrary = true,
    )

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("NdkPlugin")
        val messages: UserMessages? = reg.appServices.getServiceOrNull(USER_MESSAGES)
        val toolchain = NdkToolchain(reg, log, messages)
        NdkState.toolchain = toolchain

        // Route C and C++ sources to their own language, so the engine stops treating a .cpp as unknown text.
        // The ids match what the UI facet registers an EditorLanguage for; one language is one id across both.
        reg.register(FILE_TYPE_EP, FileTypeMapping(C_SUFFIXES, LanguageId(C_LANGUAGE)))
        reg.register(FILE_TYPE_EP, FileTypeMapping(CPP_SUFFIXES, LanguageId(CPP_LANGUAGE)))

        // The module's native configuration, persisted as the `[ndk]` table of its module.toml. A facet is
        // a type plus its codec, always both: the core cannot serialize one generically.
        reg.register(FACET_CODEC_EP, NdkFacetCodec)
        // Two templates, not one with a switch, because the two projects are not variants of each other: a
        // C++ library has no manifest and no Android in it, while a native activity is an Android app whose
        // only code is C++. Each also arrives with its `[ndk]` table written out in full, which is where a
        // user finds out what is configurable.
        reg.register(ProjectTemplateExtensionPoint, NativeCppTemplate())
        reg.register(ProjectTemplateExtensionPoint, NativeActivityTemplate())

        // What a .c, .cpp or .h looks like in the project tree. The UI facet registers the matching art and
        // the same mapping for tabs and breadcrumbs; both read one table ([NdkFileIcons]).
        reg.register(FileIconExtensionPoint, NdkFileIconProvider)

        // Completion answered by the compiler itself: the same frontend that builds the file knows what a
        // `.` can be followed by, so there is no second parser to disagree with the first.
        reg.register(
            COMPLETION_CONTRIBUTOR_EP,
            CompletionContribution(
                contributor = NdkCompletionContributor({ NdkState.toolchain }, reg.logger("NdkComplete")),
                languages = setOf(LanguageId(C_LANGUAGE), LanguageId(CPP_LANGUAGE)),
            ),
        )

        // The native half of an ordinary build: one task per module carrying an [ndk] facet, wired ahead of
        // the Android packaging merge so the .so is on disk before it looks for one.
        reg.register(BUILD_PLUGIN_EP, NdkBuildPlugin({ NdkState.toolchain }, reg.logger("NdkBuild")))

        // Errors in the editor, from the compiler that builds the file. Registered with the toolchain read
        // lazily, because `register` runs before there is any reason to unpack one.
        reg.register(DIAGNOSTIC_PROVIDER_EP, NdkDiagnosticProvider({ NdkState.toolchain }, reg.logger("NdkClang")))

        // The command that makes the toolchain real to the user: it prepares it (unpacking on first run,
        // with a progress row), then compiles and links a throwaway file so the answer is "it works" rather
        // than "the binary exists".
        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "dev.codeassist.ndk.checkToolchain",
                text = "NDK: check the C/C++ toolchain",
                places = setOf(ActionPlaces.COMMAND_PALETTE, ActionPlaces.MORE_MENU),
                iconId = "build",
            ) {
                when (val status = toolchain.prepare()) {
                    is NdkToolchain.Status.Unavailable -> {
                        log.warn("toolchain unavailable: ${status.reason}")
                        messages?.show(
                            dev.ide.platform.notify.UserMessage(
                                status.reason,
                                dev.ide.platform.notify.MessageSeverity.WARNING,
                            )
                        )
                        ActionResult.message(status.reason)
                    }

                    is NdkToolchain.Status.Ready -> {
                        val built = buildProbe(toolchain, reg)
                        log.info("${status.version}; probe: $built")
                        NdkState.lastCheck = "${status.version}\n$built"
                        messages?.show(
                            dev.ide.platform.notify.UserMessage(
                                "NDK toolchain ready: ${status.version}",
                                dev.ide.platform.notify.MessageSeverity.INFO,
                            )
                        )
                        ActionResult.message(status.version)
                    }
                }
            },
        )

        // A SECOND, heavier check: a multi-file "mini drawing engine" (several .cpp/.h pairs, cross-
        // including each other, with STL containers and virtual dispatch), compiled file-by-file and linked
        // into one .so. `checkToolchain` above proves the toolchain runs at all; this proves it holds up at
        // roughly the SHAPE a real brush/canvas engine needs -- multiple translation units, headers included
        // across files, inheritance, std::vector<std::unique_ptr<T>> -- before real engine work is built on
        // top of it. See [NdkScaleProbe].
        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "dev.codeassist.ndk.scaleProbe",
                text = "NDK: multi-file build test (drawing-engine scale)",
                places = setOf(ActionPlaces.COMMAND_PALETTE, ActionPlaces.MORE_MENU),
                iconId = "build",
            ) {
                when (val status = toolchain.prepare()) {
                    is NdkToolchain.Status.Unavailable -> {
                        log.warn("toolchain unavailable: ${status.reason}")
                        messages?.show(
                            dev.ide.platform.notify.UserMessage(
                                status.reason,
                                dev.ide.platform.notify.MessageSeverity.WARNING,
                            )
                        )
                        ActionResult.message(status.reason)
                    }

                    is NdkToolchain.Status.Ready -> {
                        val result = NdkScaleProbe.run(toolchain, reg.dataDir.resolve("scale-probe"))
                        log.info("scale probe: $result")
                        NdkState.lastCheck = result
                        val severity = if (result.startsWith("OK")) {
                            dev.ide.platform.notify.MessageSeverity.INFO
                        } else {
                            dev.ide.platform.notify.MessageSeverity.WARNING
                        }
                        messages?.show(dev.ide.platform.notify.UserMessage(result, severity))
                        ActionResult.message(result)
                    }
                }
            },
        )

        log.info("registered; toolchain is prepared on first use")
    }

    /**
     * Compile and link a file the plugin writes itself.
     *
     * A version string only proves the binary starts. This proves the parts that actually go wrong: that the
     * resource headers and sysroot were found, that the linker was reached under its own odd name, and that
     * what came out is an AArch64 object rather than something for the build machine.
     */
    private fun buildProbe(toolchain: NdkToolchain, reg: PluginRegistration): String {
        val dir = reg.dataDir.resolve("probe")
        Files.createDirectories(dir)
        val source = dir.resolve("probe.cpp")
        // writeText, not Files.writeString: this runs on ART with a minSdk of 26, and Files.writeString is
        // API 33 -- it dexes clean and throws NoSuchMethodError on the devices this plugin targets.
        source.writeText(
            """
            #include <string>
            #include <vector>
            extern "C" int ca_probe() {
                std::vector<std::string> v{"ndk"};
                return static_cast<int>(v[0].size());
            }
            """.trimIndent()
        )
        val obj = dir.resolve("probe.o")
        val compiled = toolchain.compile(source, obj, cpp = true, extraFlags = listOf("-std=c++17", "-fPIC"))
        if (!compiled.ok) return "compile failed: ${compiled.output.take(300)}"

        val so = dir.resolve("libcaprobe.so")
        val linked = toolchain.linkShared(listOf(obj), so, libs = listOf("log"))
        if (!linked.ok) return "link failed: ${linked.output.take(300)}"

        return "compiled and linked ${so.fileName} (${Files.size(so)} bytes)"
    }

    companion object {
        const val C_LANGUAGE = "c"
        const val CPP_LANGUAGE = "cpp"

        val C_SUFFIXES = listOf(".c")

        /** `.h` goes to C++: a header is compiled as whichever language includes it, and C++ is the tolerant
         *  reading of the two, so treating it as C would flag every class in a C++ project's headers. */
        val CPP_SUFFIXES = listOf(".cpp", ".cc", ".cxx", ".c++", ".h", ".hpp", ".hh", ".hxx", ".inl")
    }
}

/** What the two facets share. They load off one APK on one classloader, so this is one object to both. */
object NdkState {
    @Volatile
    var toolchain: NdkToolchain? = null

    @Volatile
    var lastCheck: String? = null
}

/**
 * A multi-file "mini drawing engine", written to disk and built exactly the way a real module would be: one
 * `.cpp` per translation unit, headers `#include`d ACROSS files (not just within one), a small class
 * hierarchy with virtual dispatch, and the STL containers a brush/layer/canvas engine actually reaches for
 * (`std::vector`, `std::unique_ptr`, `std::string`). [NdkPlugin.buildProbe] proves the toolchain runs at
 * all; this proves it holds up at roughly the SHAPE that scale of engine needs, before real engine work is
 * built on top of it.
 *
 * The shape, file by file (deliberately mirroring the [[app-animacao]]-style domain: strokes, layers, a
 * canvas, more than one brush):
 *  - `Point.h` / `Color.h` -- plain structs, included by everything below them.
 *  - `Brush.h` -- an abstract base (`virtual ~Brush()`, pure-virtual `width()`), forcing a vtable and
 *    dynamic dispatch, not just templates the compiler could inline away.
 *  - `PencilBrush.h/.cpp`, `MarkerBrush.h/.cpp` -- two concrete brushes, each its own translation unit,
 *    each `#include`ing `Brush.h`.
 *  - `Stroke.h/.cpp` -- holds `std::vector<Point>` and a `std::unique_ptr<Brush>`; `#include`s `Point.h`,
 *    `Color.h` and `Brush.h`.
 *  - `Layer.h/.cpp` -- `std::vector<std::unique_ptr<Stroke>>`; `#include`s `Stroke.h`.
 *  - `Canvas.h/.cpp` -- `std::vector<std::unique_ptr<Layer>>`; `#include`s `Layer.h`, so by this point the
 *    include chain is four files deep, which is the thing a one-file probe cannot exercise at all.
 *  - `main.cpp` -- builds a small `Canvas`, adds strokes with both brush types, and returns a checksum
 *    derived from the real object graph (not a constant), so a build that silently produced the WRONG
 *    program (a stale link, a shadowed symbol) is as likely to be caught as one that failed outright.
 *
 * Nine translation units compiled separately and linked into one `.so` -- proportionally small next to a
 * real engine, but the same KIND of build: cross-header dependencies, polymorphism, ownership, multiple
 * `.o` files meeting only at link time.
 */
internal object NdkScaleProbe {

    fun run(toolchain: NdkToolchain, dir: Path): String {
        val srcDir = dir.resolve("src")
        Files.createDirectories(srcDir)
        val objDir = dir.resolve("obj")
        Files.createDirectories(objDir)

        for ((name, content) in files()) srcDir.resolve(name).writeText(content)

        val flags = listOf("-std=c++17", "-fPIC", "-I", srcDir.toString())
        val objects = mutableListOf<Path>()
        for (cppFile in listOf("PencilBrush.cpp", "MarkerBrush.cpp", "Stroke.cpp", "Layer.cpp", "Canvas.cpp", "main.cpp")) {
            val source = srcDir.resolve(cppFile)
            val obj = objDir.resolve(cppFile.removeSuffix(".cpp") + ".o")
            val result = toolchain.compile(source, obj, cpp = true, extraFlags = flags)
            if (!result.ok) return "FAILED compiling $cppFile:\n${result.output.take(800)}"
            objects += obj
        }

        val so = dir.resolve("libscaleprobe.so")
        val linked = toolchain.linkShared(objects, so, libs = listOf("log"))
        if (!linked.ok) return "FAILED linking (${objects.size} object files):\n${linked.output.take(800)}"

        return "OK: ${objects.size} files compiled separately (cross-including headers, virtual dispatch, " +
            "std::vector<std::unique_ptr<T>>) and linked into ${so.fileName} (${Files.size(so)} bytes)"
    }

    private fun files(): Map<String, String> = mapOf(
        "Point.h" to """
            #pragma once
            struct Point {
                float x = 0.0f;
                float y = 0.0f;
            };
        """.trimIndent() + "\n",

        "Color.h" to """
            #pragma once
            #include <cstdint>
            struct Color {
                uint8_t r = 0, g = 0, b = 0, a = 255;
            };
        """.trimIndent() + "\n",

        "Brush.h" to """
            #pragma once
            // An abstract base with a pure-virtual method: this forces a real vtable and dynamic dispatch,
            // the thing a template-only test would let the compiler optimize away entirely.
            class Brush {
            public:
                virtual ~Brush() = default;
                virtual float width() const = 0;
                virtual const char* name() const = 0;
            };
        """.trimIndent() + "\n",

        "PencilBrush.h" to """
            #pragma once
            #include "Brush.h"
            class PencilBrush : public Brush {
            public:
                float width() const override;
                const char* name() const override;
            };
        """.trimIndent() + "\n",

        "PencilBrush.cpp" to """
            #include "PencilBrush.h"
            float PencilBrush::width() const { return 1.5f; }
            const char* PencilBrush::name() const { return "pencil"; }
        """.trimIndent() + "\n",

        "MarkerBrush.h" to """
            #pragma once
            #include "Brush.h"
            class MarkerBrush : public Brush {
            public:
                float width() const override;
                const char* name() const override;
            };
        """.trimIndent() + "\n",

        "MarkerBrush.cpp" to """
            #include "MarkerBrush.h"
            float MarkerBrush::width() const { return 8.0f; }
            const char* MarkerBrush::name() const { return "marker"; }
        """.trimIndent() + "\n",

        "Stroke.h" to """
            #pragma once
            #include <memory>
            #include <vector>
            #include "Point.h"
            #include "Color.h"
            #include "Brush.h"

            class Stroke {
            public:
                Stroke(std::unique_ptr<Brush> brush, Color color);
                void addPoint(Point p);
                // A double-precision accumulator over the real point data, not a constant, so a stale or
                // wrong link is as likely to be caught as an outright build failure.
                double pathLength() const;
                const Brush& brush() const { return *brush_; }
                Color color() const { return color_; }
                size_t pointCount() const { return points_.size(); }

            private:
                std::unique_ptr<Brush> brush_;
                Color color_;
                std::vector<Point> points_;
            };
        """.trimIndent() + "\n",

        "Stroke.cpp" to """
            #include "Stroke.h"
            #include <cmath>

            Stroke::Stroke(std::unique_ptr<Brush> brush, Color color)
                : brush_(std::move(brush)), color_(color) {}

            void Stroke::addPoint(Point p) { points_.push_back(p); }

            double Stroke::pathLength() const {
                double total = 0.0;
                for (size_t i = 1; i < points_.size(); ++i) {
                    double dx = points_[i].x - points_[i - 1].x;
                    double dy = points_[i].y - points_[i - 1].y;
                    total += std::sqrt(dx * dx + dy * dy);
                }
                return total;
            }
        """.trimIndent() + "\n",

        "Layer.h" to """
            #pragma once
            #include <memory>
            #include <vector>
            #include "Stroke.h"

            class Layer {
            public:
                Stroke& addStroke(std::unique_ptr<Brush> brush, Color color);
                size_t strokeCount() const { return strokes_.size(); }
                double totalLength() const;

            private:
                std::vector<std::unique_ptr<Stroke>> strokes_;
            };
        """.trimIndent() + "\n",

        "Layer.cpp" to """
            #include "Layer.h"

            Stroke& Layer::addStroke(std::unique_ptr<Brush> brush, Color color) {
                strokes_.push_back(std::make_unique<Stroke>(std::move(brush), color));
                return *strokes_.back();
            }

            double Layer::totalLength() const {
                double total = 0.0;
                for (const auto& s : strokes_) total += s->pathLength();
                return total;
            }
        """.trimIndent() + "\n",

        "Canvas.h" to """
            #pragma once
            #include <memory>
            #include <vector>
            #include "Layer.h"

            // Four #include hops deep from here down to Point.h/Color.h/Brush.h -- the thing a single-file
            // probe cannot exercise, and where a stale header or a missing include guard would first show up.
            class Canvas {
            public:
                Layer& addLayer();
                size_t layerCount() const { return layers_.size(); }
                double totalLength() const;

            private:
                std::vector<std::unique_ptr<Layer>> layers_;
            };
        """.trimIndent() + "\n",

        "Canvas.cpp" to """
            #include "Canvas.h"

            Layer& Canvas::addLayer() {
                layers_.push_back(std::make_unique<Layer>());
                return *layers_.back();
            }

            double Canvas::totalLength() const {
                double total = 0.0;
                for (const auto& l : layers_) total += l->totalLength();
                return total;
            }
        """.trimIndent() + "\n",

        "main.cpp" to """
            #include "Canvas.h"
            #include "PencilBrush.h"
            #include "MarkerBrush.h"
            #include <memory>

            // extern "C": the entry point this test calls back into from Kotlin, past name mangling.
            // Builds a small but real object graph (2 layers, 3 strokes across both brush types, several
            // points each) and returns a value DERIVED from it, so a wrong link or a shadowed symbol changes
            // the answer instead of silently passing.
            extern "C" int ca_scale_probe() {
                Canvas canvas;

                Layer& layer1 = canvas.addLayer();
                Stroke& s1 = layer1.addStroke(std::make_unique<PencilBrush>(), Color{255, 0, 0, 255});
                s1.addPoint({0.0f, 0.0f});
                s1.addPoint({10.0f, 0.0f});
                s1.addPoint({10.0f, 10.0f});

                Stroke& s2 = layer1.addStroke(std::make_unique<MarkerBrush>(), Color{0, 255, 0, 255});
                s2.addPoint({0.0f, 0.0f});
                s2.addPoint({5.0f, 5.0f});

                Layer& layer2 = canvas.addLayer();
                Stroke& s3 = layer2.addStroke(std::make_unique<PencilBrush>(), Color{0, 0, 255, 255});
                s3.addPoint({0.0f, 0.0f});
                s3.addPoint({3.0f, 4.0f});

                int checksum = static_cast<int>(canvas.totalLength() * 100.0);
                checksum += static_cast<int>(canvas.layerCount());
                checksum += static_cast<int>(s1.brush().width() + s2.brush().width() + s3.brush().width());
                return checksum;
            }
        """.trimIndent() + "\n",
    )
}
