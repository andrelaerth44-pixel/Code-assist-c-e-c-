package dev.ide.android.support.templates

import dev.ide.android.support.AndroidApiLevels
import dev.ide.android.support.AndroidFacet
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.FacetData
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateDependency
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/** Shared helpers for the built-in Android templates. */
internal object AndroidTemplateSupport {
    fun pkgPath(pkg: String): String = pkg.replace('.', '/')

    private fun options(levels: List<AndroidApiLevels.Level>) =
        levels.map { TemplateParameter.Choice.Option(it.api.toString(), it.label) }

    /** The minSdk picker offered by both Android templates, defaulting to the level new modules use. */
    val minSdkParam = TemplateParameter.Choice(
        key = "minSdk",
        label = "Minimum SDK",
        options = options(AndroidApiLevels.MIN_SDK_LEVELS),
        defaultIndex = AndroidApiLevels.MIN_SDK_LEVELS.indexOfFirst { it.api == AndroidApiLevels.DEFAULT_MIN_SDK },
        help = "Lowest Android version the app supports.",
    )

    /** The targetSdk picker: the API level the app is tested/optimised against, newest by default (Play
     *  requires a current target, and an old one opts the app into compatibility behaviour). */
    val targetSdkParam = TemplateParameter.Choice(
        key = "targetSdk",
        label = "Target SDK",
        options = options(AndroidApiLevels.TARGET_SDK_LEVELS),
        defaultIndex = AndroidApiLevels.TARGET_SDK_LEVELS.lastIndex,
        help = "The API level the app is built and optimised against.",
    )

    // The two "hybrid" language values, alongside the plain "java"/"kotlin" ones: a UI language PLUS a C/C++
    // engine module in the same app, wired through JNI. Kept as two extra options on the SAME picker (not a
    // separate template) because they are the plain app with one thing added, not a different kind of app --
    // the same reasoning NativeActivityTemplate documents for why IT is a separate template (a C++-only app
    // is genuinely a different shape, not a variant of this one).
    const val LANG_JAVA = "java"
    const val LANG_KOTLIN = "kotlin"
    const val LANG_JAVA_CPP = "java-cpp"
    const val LANG_KOTLIN_CPP = "kotlin-cpp"

    /** Source language for the generated starter code, plus the two "+ C/C++" hybrid options (a UI language
     *  with a JNI-bridged native engine module alongside it -- for drawing/canvas/game engines that need
     *  C/C++ speed under a Kotlin or Java UI). */
    val languageParam = TemplateParameter.Choice(
        key = "language",
        label = "Language",
        options = listOf(
            TemplateParameter.Choice.Option(LANG_JAVA, "Java"),
            TemplateParameter.Choice.Option(LANG_KOTLIN, "Kotlin"),
            TemplateParameter.Choice.Option(LANG_JAVA_CPP, "Java + C/C++"),
            TemplateParameter.Choice.Option(LANG_KOTLIN_CPP, "Kotlin + C/C++"),
        ),
        defaultIndex = 0,
        help = "Language of the starter source files. The \"+ C/C++\" options add a JNI-bridged native " +
            "engine module alongside the UI -- for a drawing canvas, brush engine, or game loop that needs " +
            "C/C++ speed, with the UI itself still in Kotlin or Java.",
    )

    /** What every built-in template compiles against: the newest level the IDE ships support for. */
    const val COMPILE_SDK = AndroidApiLevels.LATEST

    /** Google's Material Components for Android — the library behind Material You theming + the FAB/Snackbar. */
    const val MATERIAL_COORDINATE = "com.google.android.material:material:1.12.0"

    fun isKotlin(args: TemplateArgs): Boolean {
        val lang = args.string("language", LANG_JAVA)
        return lang.equals(LANG_KOTLIN, ignoreCase = true) || lang.equals(LANG_KOTLIN_CPP, ignoreCase = true)
    }

    /** Whether the "+ C/C++" hybrid was picked -- a JNI-bridged native engine module alongside the UI. */
    fun isCppHybrid(args: TemplateArgs): Boolean {
        val lang = args.string("language", LANG_JAVA)
        return lang.equals(LANG_JAVA_CPP, ignoreCase = true) || lang.equals(LANG_KOTLIN_CPP, ignoreCase = true)
    }

    /** The native library name for the hybrid templates, matching what `System.loadLibrary` is given and
     *  what the generated .cpp file is named. */
    const val CPP_LIBRARY_NAME = "native-engine"

    /**
     * The JNI export name for a native method on `MainActivity`, following the JNI naming convention
     * (`Java_<package_with_underscores>_<Class>_<method>`). Dots in the package become underscores; this
     * covers the common case (a package with no underscores of its own) rather than the full JNI escaping
     * rules, which is the right tradeoff for a generated starter file a user is meant to read and extend.
     */
    fun jniExportName(pkg: String, method: String): String =
        "Java_${pkg.replace('.', '_')}_MainActivity_$method"

    /**
     * The starter C++ engine source for the "+ C/C++" hybrid templates: one JNI-exported function
     * (`stringFromJNI`) the UI calls to prove the bridge works end to end, with a comment pointing at where
     * a real drawing/game engine's entry points would go instead.
     */
    fun jniEngineSource(pkg: String): String = """
        #include <jni.h>
        #include <string>

        // This is where a real drawing/brush/game engine's entry points would live -- e.g. init(), a
        // resize(width, height), and a render()/onFrame() called from the UI's render loop. This starter
        // wires up ONE call end to end (UI -> JNI -> C++ -> back to the UI) so you can build on it directly.
        extern "C" JNIEXPORT jstring JNICALL
        ${jniExportName(pkg, "stringFromJNI")}(JNIEnv* env, jobject /* this */) {
            std::string greeting = "Hello from the C/C++ engine";
            return env->NewStringUTF(greeting.c_str());
        }
    """.trimIndent() + "\n"

    /**
     * The module-relative ProGuard/R8 keep-rules file the `release` build type references by default
     * ([AndroidFacet.DEFAULT_BUILD_TYPES]). Written for new modules so that, when minification is enabled,
     * the entry resolves to a real file instead of being silently skipped. Comments only by default
     * (the bundled `proguard-android-optimize.txt` carries the framework keep rules); add app-specific rules here.
     */
    val PROGUARD_RULES_PRO: String = """
        # Add project-specific ProGuard/R8 keep rules here.
        # These are applied on top of the bundled defaults (proguard-android-optimize.txt) when the
        # build type has minifyEnabled = true.
        #
        # Keep a class that is referenced only by reflection / from XML, e.g.:
        # -keep class com.example.SomeClass { *; }
        #
        # Preserve line numbers for readable crash stack traces, then hide the original file name:
        # -keepattributes SourceFile,LineNumberTable
        # -renamesourcefileattribute SourceFile
    """.trimIndent() + "\n"
}

/**
 * A native Android application: one `app` module (android-app) with an `AndroidFacet`, an editable
 * `AndroidManifest.xml`, `res/` (strings, colors, theme, and an `activity_main` layout), and a
 * `MainActivity` that inflates that layout to show a "Hello, World!" page. A complete, dependency-free
 * starter app that assembles to a signed APK through the existing `AndroidBuildSystem` pipeline.
 *
 * The "Java + C/C++" / "Kotlin + C/C++" language options add a second content root (`src/main/cpp`) and an
 * `[ndk]` facet table to the SAME `app` module, plus a starter `.cpp` file with one JNI-exported function
 * that `MainActivity` calls and displays. The `[ndk]` table is written as a plain named [FacetData] map
 * (not the typed `NdkFacet` class from the `ndk-native` plugin) so this module keeps its existing
 * independence from that plugin -- the same reverse trick `NdkPlugin` documents for why IT writes the
 * `[android]` table as a named map instead of depending on this module. Either plugin can be absent and the
 * project still opens; only the pairing plugin's own features (native build, C/C++ editor support) are
 * unavailable until it's installed.
 */
object AndroidAppTemplate : ProjectTemplate {
    override val id = TemplateId("android-app")
    override val displayName = "Android App"
    override val description = "A native Android application that builds to an installable APK."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(
        AndroidTemplateSupport.languageParam,
        AndroidTemplateSupport.minSdkParam,
        AndroidTemplateSupport.targetSdkParam,
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 26)
        val targetSdk = args.int("targetSdk", AndroidTemplateSupport.COMPILE_SDK)
        val kotlin = AndroidTemplateSupport.isKotlin(args)
        val cpp = AndroidTemplateSupport.isCppHybrid(args)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            // Android module types supply their own (main/debug/release) source sets, so no addSourceSet here.
            addModule("app", scaffold.moduleType("android-app")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                        minSdk = minSdk,
                        targetSdk = targetSdk,
                    ),
                )
                if (cpp) {
                    // Declaring the content root is load-bearing (see NdkTemplateSupport.scaffoldModule's
                    // note): the IDE resolves a file to its module through DECLARED roots, so a .cpp under
                    // an undeclared directory is invisible to the navigator and gets no analysis target.
                    addContentRoot("main", "src/main/cpp", setOf(ContentRole.SOURCE))
                    putFacetData(
                        FacetData(
                            "ndk",
                            mapOf(
                                "sourceDirs" to listOf("src/main/cpp"),
                                "libraryName" to AndroidTemplateSupport.CPP_LIBRARY_NAME,
                                "linkLibraries" to listOf("log"),
                            ),
                        ),
                    )
                }
            }
            commit()
        }

        val path = AndroidTemplateSupport.pkgPath(pkg)
        scaffold.writeText("app/proguard-rules.pro", AndroidTemplateSupport.PROGUARD_RULES_PRO)
        scaffold.writeText(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.App">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${args.name}</string>
                <string name="hello_world">Hello, World!</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/colors.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="primary">#FF6200EE</color>
                <color name="on_primary">#FFFFFFFF</color>
                ${AndroidAppAssets.ICON_BACKGROUND_COLOR_XML}
            </resources>
            """,
        )
        scaffold.writeText("app/src/main/res/values/themes.xml", AndroidAppAssets.themesXml)
        scaffold.writeText("app/src/main/res/values-night/themes.xml", AndroidAppAssets.themesNightXml)
        for ((rel, content) in AndroidAppAssets.launcherIconResFiles) {
            scaffold.writeText("app/src/main/res/$rel", content)
        }
        // The hybrid layout adds a second TextView for the engine's output, so a user can SEE the JNI call
        // land -- the same "prove it end to end" reasoning as the .cpp file's comment.
        scaffold.writeText(
            "app/src/main/res/layout/activity_main.xml",
            if (cpp) """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:gravity="center">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/hello_world"
                    android:textSize="24sp"/>
                <TextView
                    android:id="@+id/engine_output"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:textSize="16sp"/>
            </LinearLayout>
            """ else """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:gravity="center">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/hello_world"
                    android:textSize="24sp"/>
            </LinearLayout>
            """,
        )
        if (cpp) {
            scaffold.writeText(
                "app/src/main/cpp/${AndroidTemplateSupport.CPP_LIBRARY_NAME}.cpp",
                AndroidTemplateSupport.jniEngineSource(pkg),
            )
        }
        if (kotlin) {
            scaffold.writeText(
                "app/src/main/kotlin/$path/MainActivity.kt",
                if (cpp) """
                package $pkg

                import android.app.Activity
                import android.os.Bundle
                import android.widget.TextView

                class MainActivity : Activity() {
                    // The starter engine call this bridges to; see app/src/main/cpp/${AndroidTemplateSupport.CPP_LIBRARY_NAME}.cpp.
                    // A real drawing/game engine adds more native methods here (init/resize/render/...).
                    external fun stringFromJNI(): String

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                        findViewById<TextView>(R.id.engine_output).text = stringFromJNI()
                    }

                    companion object {
                        // Matches libraryName in the [ndk] table (app/module.toml). Loaded once per process.
                        init {
                            System.loadLibrary("${AndroidTemplateSupport.CPP_LIBRARY_NAME}")
                        }
                    }
                }
                """ else """
                package $pkg

                import android.app.Activity
                import android.os.Bundle

                class MainActivity : Activity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                    }
                }
                """,
            )
        } else {
            scaffold.writeText(
                "app/src/main/java/$path/MainActivity.java",
                if (cpp) """
                package $pkg;

                import android.app.Activity;
                import android.os.Bundle;
                import android.widget.TextView;

                public class MainActivity extends Activity {
                    // Matches libraryName in the [ndk] table (app/module.toml). Loaded once per process.
                    static {
                        System.loadLibrary("${AndroidTemplateSupport.CPP_LIBRARY_NAME}");
                    }

                    // The starter engine call this bridges to; see app/src/main/cpp/${AndroidTemplateSupport.CPP_LIBRARY_NAME}.cpp.
                    // A real drawing/game engine adds more native methods here (init/resize/render/...).
                    public native String stringFromJNI();

                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                        ((TextView) findViewById(R.id.engine_output)).setText(stringFromJNI());
                    }
                }
                """ else """
                package $pkg;

                import android.app.Activity;
                import android.os.Bundle;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                    }
                }
                """,
            )
        }
    }
}

/**
 * A Material You (Material 3) Android application: one `app` module wired to **Google's Material Components
 * library** ([AndroidTemplateSupport.MATERIAL_COORDINATE], resolved by the host after generation). The app
 * theme extends `Theme.Material3.DynamicColors.DayNight` so it adopts the system **dynamic colour** palette
 * on Android 12+ and a light/dark Material 3 baseline below it, and the starter screen is the canonical
 * **FAB example**: a `CoordinatorLayout` with a `FloatingActionButton` whose tap shows a `Snackbar`. The
 * `MainActivity` extends `AppCompatActivity` (pulled in transitively by Material) so the Material 3 theme
 * resolves. Assembles to a signed APK through the existing `AndroidBuildSystem` pipeline (AAR resources +
 * D8 dexing of the Material/AndroidX closure).
 */
object MaterialYouAppTemplate : ProjectTemplate {
    override val id = TemplateId("android-material-you")
    override val displayName = "Material You App"
    override val description = "A Material 3 app with dynamic colour theming and a Floating Action Button."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(
        AndroidTemplateSupport.languageParam,
        // Material Components requires minSdk 21; drop the lower options the plain app template offers.
        AndroidTemplateSupport.minSdkParam.copy(
            options = AndroidTemplateSupport.minSdkParam.options.filter { it.value.toInt() >= 21 },
            defaultIndex = 0,
        ),
        AndroidTemplateSupport.targetSdkParam,
    )

    override fun dependencies(args: TemplateArgs): List<TemplateDependency> =
        listOf(TemplateDependency(module = "app", coordinate = AndroidTemplateSupport.MATERIAL_COORDINATE))

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 21)
        val targetSdk = args.int("targetSdk", AndroidTemplateSupport.COMPILE_SDK)
        val kotlin = AndroidTemplateSupport.isKotlin(args)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("app", scaffold.moduleType("android-app")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                        minSdk = minSdk,
                        targetSdk = targetSdk,
                    ),
                )
            }
            commit()
        }

        val path = AndroidTemplateSupport.pkgPath(pkg)
        scaffold.writeText("app/proguard-rules.pro", AndroidTemplateSupport.PROGUARD_RULES_PRO)
        scaffold.writeText(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.App">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${args.name}</string>
                <string name="hello_world">Hello, Material You!</string>
                <string name="fab_description">Add</string>
                <string name="fab_clicked">FAB clicked</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/colors.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="primary">#FF6750A4</color>
                <color name="on_primary">#FFFFFFFF</color>
                ${AndroidAppAssets.ICON_BACKGROUND_COLOR_XML}
            </resources>
            """,
        )
        // Theme.Material3.DynamicColors.DayNight: dynamic (wallpaper-derived) colour on Android 12+, a
        // Material 3 light/dark baseline below it. No values-night override needed — DayNight handles it.
        scaffold.writeText(
            "app/src/main/res/values/themes.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="Theme.Material3.DynamicColors.DayNight">
                    <item name="colorPrimary">@color/primary</item>
                    <item name="colorOnPrimary">@color/on_primary</item>
                </style>
            </resources>
            """,
        )
        for ((rel, content) in AndroidAppAssets.launcherIconResFiles) {
            scaffold.writeText("app/src/main/res/$rel", content)
        }
        scaffold.writeText(
            "app/src/main/res/layout/activity_main.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <androidx.coordinatorlayout.widget.CoordinatorLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent"
                android:layout_height="match_parent">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:text="@string/hello_world"
                    android:textAppearance="?attr/textAppearanceHeadlineSmall"/>

                <com.google.android.material.floatingactionbutton.FloatingActionButton
                    android:id="@+id/fab"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="bottom|end"
                    android:layout_margin="16dp"
                    android:contentDescription="@string/fab_description"
                    android:src="@android:drawable/ic_input_add"/>
            </androidx.coordinatorlayout.widget.CoordinatorLayout>
            """,
        )
        if (kotlin) {
            scaffold.writeText(
                "app/src/main/kotlin/$path/MainActivity.kt",
                """
                package $pkg

                import android.os.Bundle
                import androidx.appcompat.app.AppCompatActivity
                import com.google.android.material.floatingactionbutton.FloatingActionButton
                import com.google.android.material.snackbar.Snackbar

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { view ->
                            Snackbar.make(view, R.string.fab_clicked, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                """,
            )
        } else {
            scaffold.writeText(
                "app/src/main/java/$path/MainActivity.java",
                """
                package $pkg;

                import android.os.Bundle;
                import android.view.View;
                import androidx.appcompat.app.AppCompatActivity;
                import com.google.android.material.floatingactionbutton.FloatingActionButton;
                import com.google.android.material.snackbar.Snackbar;

                public class MainActivity extends AppCompatActivity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                        FloatingActionButton fab = findViewById(R.id.fab);
                        fab.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                Snackbar.make(view, R.string.fab_clicked, Snackbar.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                """,
            )
        }
    }
}

/**
 * A native Android library: one `lib` module (android-lib) with an `AndroidFacet(isApplication=false)`,
 * its own `res/` (merged into a consuming app's R), and a sample class referencing its own `R`.
 */
object AndroidLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("android-library")
    override val displayName = "Android Library"
    override val description = "A reusable Android library module (AAR) with its own resources."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(
        AndroidTemplateSupport.languageParam,
        AndroidTemplateSupport.minSdkParam,
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 26)
        val kotlin = AndroidTemplateSupport.isKotlin(args)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("lib", scaffold.moduleType("android-lib")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                        minSdk = minSdk,
                        isApplication = false,
                    ),
                )
            }
            commit()
        }

        val path = AndroidTemplateSupport.pkgPath(pkg)
        scaffold.writeText("lib/proguard-rules.pro", AndroidTemplateSupport.PROGUARD_RULES_PRO)
        scaffold.writeText(
            "lib/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg" />
            """,
        )
        scaffold.writeText(
            "lib/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="lib_title">${args.name}</string>
            </resources>
            """,
        )
        if (kotlin) {
            scaffold.writeText(
                "lib/src/main/kotlin/$path/LibraryText.kt",
                """
                package $pkg

                /** Library code resolving its OWN R (merged into a consuming app's R). */
                object LibraryText {
                    fun titleRes(): Int = R.string.lib_title
                }
                """,
            )
        } else {
            scaffold.writeText(
                "lib/src/main/java/$path/LibraryText.java",
                """
                package $pkg;

                /** Library code resolving its OWN R (merged into a consuming app's R). */
                public final class LibraryText {
                    public static int titleRes() { return R.string.lib_title; }
                }
                """,
            )
        }
    }
}
