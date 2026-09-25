package dev.codeassist.ndk

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * An Android app with no Java in it: the platform's own `NativeActivity` loads a library and calls
 * `android_main`.
 *
 * This is an Android application, not a library with an app around it, so it generates what an Android
 * application needs -- a manifest, a string resource, an `[android]` facet -- and it generates no Java,
 * which is the whole point of the shape. `android:hasCode="false"` is the line that says so, and
 * `android.app.lib_name` is the one the platform reads to decide what to `dlopen`.
 *
 * The event loop is written out rather than stubbed. An empty `android_main` returns immediately and the app
 * looks broken instead of empty, so the generated loop handles the two events that decide whether an app is
 * alive: a window arriving and going away.
 *
 * "No Java" is this template's STARTING shape, not a wall: `android-app` is the same module type the plain
 * Kotlin/Java `AndroidAppTemplate` uses, so its own Java/Kotlin source sets and compiler are already wired
 * for this module too -- adding `.kt`/`.java` files and flipping `android:hasCode` back to the default
 * (true) is the whole migration (see the generated README's "Adding Kotlin or Java later" section).
 */
class NativeActivityTemplate : ProjectTemplate {

    override val id = TemplateId("ndk-native-activity")
    override val displayName = "Native C++ Activity"
    override val description =
        "An Android app whose only code is C++: the platform calls android_main, and there is no Java."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(
        NdkTemplateSupport.libraryNameParam(
            "The library the platform loads. It is written into the manifest's android.app.lib_name, so " +
                "this name and that value are one setting in two files.",
        ),
        NdkTemplateSupport.standardParam(cppOnly = true),
        TemplateParameter.Choice(
            key = NdkTemplateSupport.MIN_SDK,
            label = "Minimum SDK",
            options = listOf(
                TemplateParameter.Choice.Option("26", "API 26 (Android 8.0)"),
                TemplateParameter.Choice.Option("24", "API 24 (Android 7.0)"),
                TemplateParameter.Choice.Option("29", "API 29 (Android 10)"),
                TemplateParameter.Choice.Option("33", "API 33 (Android 13)"),
            ),
            help = "Lowest Android version the app supports. It is also what the native code is compiled " +
                "against, so a lower value means fewer NDK functions are declared.",
        ),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val library = NdkTemplateSupport.libraryName(args)
        val standard = args.string(NdkTemplateSupport.STANDARD, "c++17")
        val minSdk = args.int(NdkTemplateSupport.MIN_SDK, 26)
        val packageName = args.packageName

        NdkTemplateSupport.scaffoldModule(
            scaffold = scaffold,
            projectName = args.name,
            moduleTypeId = "android-app",
            ndk = NdkTemplateSupport.withStandard(
                NdkFacet(
                    libraryName = library,
                    minSdk = minSdk,
                    // The glue is compiled into the app (the NDK ships it as source for exactly that), and
                    // `android` carries ANativeWindow and the looper the loop below blocks on.
                    nativeActivity = true,
                    linkLibraries = listOf("log", "android"),
                ),
                standard,
            ),
            // Written as a named table because this plugin does not link against android-support: the
            // `[android]` facet is another plugin's, and its codec reads this back as its own.
            android = mapOf(
                "namespace" to packageName,
                "compileSdk" to NdkTemplateSupport.COMPILE_SDK,
                "minSdk" to minSdk,
                "targetSdk" to NdkTemplateSupport.COMPILE_SDK,
            ),
        )

        scaffold.writeText("app/src/main/cpp/$library.cpp", nativeActivitySource(library))
        scaffold.writeText("app/src/main/AndroidManifest.xml", manifest(packageName, library))
        scaffold.writeText("app/src/main/res/values/strings.xml", strings(args.name))
        scaffold.writeText("README.md", readme(library, standard, packageName))
    }

    /** `android_main` driving the glue's event loop. The entry point; nothing calls it from Java. */
    private fun nativeActivitySource(library: String): String = """
        #include <android/log.h>
        #include <android_native_app_glue.h>

        #define LOG_TAG "$library"
        #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

        static void handle_cmd(android_app* app, int32_t cmd) {
            switch (cmd) {
                case APP_CMD_INIT_WINDOW:
                    LOGI("window ready: %d x %d",
                         ANativeWindow_getWidth(app->window),
                         ANativeWindow_getHeight(app->window));
                    break;
                case APP_CMD_TERM_WINDOW:
                    LOGI("window gone");
                    break;
                default:
                    break;
            }
        }

        /** The entry point. The platform loads this library and calls this; there is no Java anywhere. */
        void android_main(android_app* app) {
            app->onAppCmd = handle_cmd;
            LOGI("android_main started");

            while (true) {
                int events;
                android_poll_source* source;
                // Block until something happens. -1 is what keeps an idle app off the CPU; a real renderer
                // passes 0 here and draws a frame between polls instead.
                while (ALooper_pollOnce(-1, nullptr, &events, reinterpret_cast<void**>(&source)) >= 0) {
                    if (source != nullptr) source->process(app, source);
                    if (app->destroyRequested != 0) {
                        LOGI("destroy requested");
                        return;
                    }
                }
            }
        }
    """.trimIndent() + "\n"

    /**
     * The manifest for an app with no code of its own to name.
     *
     * `android.app.lib_name` is the load-bearing line: the platform's NativeActivity reads it to decide
     * which library to `dlopen`, so it has to be the facet's `libraryName` without the `lib` prefix or the
     * `.so` suffix. `hasCode="false"` is what says there are no classes to look for.
     */
    private fun manifest(packageName: String, library: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">

            <application android:label="@string/app_name" android:hasCode="false">
                <activity
                    android:name="android.app.NativeActivity"
                    android:exported="true"
                    android:configChanges="orientation|keyboardHidden|screenSize">

                    <!-- Which library to load. Must match libraryName in the [ndk] table. -->
                    <meta-data android:name="android.app.lib_name" android:value="$library" />

                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>

        </manifest>
    """.trimIndent() + "\n"

    private fun strings(appName: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">$appName</string>
        </resources>
    """.trimIndent() + "\n"

    private fun readme(library: String, standard: String, packageName: String): String = """
        # Native activity

        An Android app written entirely in C++, compiled on the device. The toolchain is a clang and an lld
        built to RUN on Android arm64 and packaged inside the NDK plugin, so nothing here needs a desktop.

        - `app/src/main/cpp/$library.cpp` holds `android_main`, which the platform calls. There is no Java.
        - `AndroidManifest.xml` names `android.app.NativeActivity` and points `android.app.lib_name` at
          `$library`. Renaming the library means changing both that line and `libraryName` below.
        - The `[ndk]` table in `app/module.toml` is the native build configuration: source directories, ABIs,
          the language standards (this project was created with `$standard`), the STL, optimization, extra
          flags and the libraries to link. `nativeActivity = true` is what compiles the NDK's
          `android_native_app_glue` in and puts its header on the include path.
        - The `[android]` table beside it is the ordinary Android configuration, unchanged by this plugin.

        Errors appear in the editor as you type, reported by the same clang that builds the code, so the two
        cannot disagree.

        ## Adding Kotlin or Java later

        This module's type (`android-app`) is the SAME one a plain Kotlin/Java Android app uses -- "no Java"
        is this template's starting shape, not something the build system enforces. To add UI or other code
        in Kotlin or Java alongside the C++:

        1. Add source files under `app/src/main/kotlin/<package>/` (Kotlin) or `app/src/main/java/<package>/`
           (Java) -- the same layout `AndroidAppTemplate`-generated projects use. They compile automatically;
           no change to `module.toml` is needed for this part.
        2. In `AndroidManifest.xml`, remove `android:hasCode="false"` (or set it to `true`) -- it exists only
           to tell the platform "there are no classes here", which stops being accurate. Leaving it `false`
           while Kotlin/Java classes exist does not break the build, but it is a lie the manifest is telling.
        3. Decide who launches the app. `android.app.NativeActivity` calls `android_main` directly and never
           runs any Kotlin/Java you add. For a normal Activity to run your added code (with the option to
           still call into $library.cpp via JNI, the way the "Kotlin + C/C++" / "Java + C/C++" Android App
           template does it), replace `android.app.NativeActivity` with your own Activity class in the
           manifest's `<activity android:name=...>`, and give it a `System.loadLibrary("$library")` call plus
           `external`/`native` method declarations for whichever C++ functions it should call directly.

        If what you actually want from the start is "Kotlin or Java UI calling into a C/C++ engine", the
        "Android App" template's "Kotlin + C/C++" / "Java + C/C++" language options generate that shape
        directly (a normal Activity, JNI bridge, and $library-equivalent all wired already) -- this manual
        path is for a project that started here, as C++-only, and grew a UI later.
    """.trimIndent() + "\n"
}
