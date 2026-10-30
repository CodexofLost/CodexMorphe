package io.github.codexoflost.codexmorphe.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.gson.Gson
import io.github.codexoflost.codexmorphe.hook.model.HookDefinition
import io.github.codexoflost.codexmorphe.hook.model.HookRecipeManifest
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.zip.ZipInputStream

/**
 * Universal dynamic hook dispatcher that reads hook recipes from the bundle APK
 * and binds them using DexKit pattern matching and LibXposed API 102 interceptors.
 */
class DexKitHookDispatcher(
    private val app: Application,
    private val hostLoader: ClassLoader,
    private val bundleLoader: ClassLoader,
    private val pfd: ParcelFileDescriptor,
    private val prefs: SharedPreferences,
    private val xposed: XposedInterface
) {

    companion object {
        private const val TAG = "CodexMorphe/Dispatcher"
    }

    fun dispatch() {
        Log.i(TAG, "Initializing CodexMorphe Dynamic Engine for ${app.packageName}")

        // 1. Initialize Bundle Bridge and multi-author context
        BundleBridge.init(bundleLoader)
        BundleBridge.initializeExtensionContext(app)
        initializeAuthorSettingsEntries()

        // 2. Read hooks.json from the bundle APK stream
        val manifest = extractHookManifest()
        if (manifest == null || manifest.hooks.isEmpty()) {
            Log.i(TAG, "No dynamic hook recipes in bundle for ${app.packageName}; context initializers only.")
            return
        }

        // 2b. Dispatch dynamic extension entrypoints from manifest
        if (manifest.entrypoints.isNotEmpty()) {
            for (ep in manifest.entrypoints) {
                val parts = ep.split("#")
                if (parts.size == 2) {
                    val cls = parts[0].trim()
                    val mtd = parts[1].trim()
                    runCatching {
                        val res = BundleBridge.callStatic(cls, mtd, app)
                        if (res != null || BundleBridge.loadClass(cls) != null) {
                            Log.d(TAG, "Dispatched manifest entrypoint: $ep")
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Loaded ${manifest.hooks.size} dynamic hook recipes from bundle for ${app.packageName}")

        // 3. Initialize DexKit native engine with fallback
        ensureDexKitLoaded()

        val bridge = runCatching {
            DexKitBridge.create(app.applicationInfo.sourceDir)
        }.onFailure {
            Log.e(TAG, "Failed to create DexKitBridge for ${app.applicationInfo.sourceDir}: ${it.message}")
        }.getOrNull() ?: return

        try {
            // 4. Bind hooks dynamically using pure LibXposed API 102
            bindDynamicHooks(bridge, manifest.hooks)
        } finally {
            runCatching { bridge.close() }
        }
    }

    private fun extractHookManifest(): HookRecipeManifest? {
        return runCatching {
            runCatching {
                android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            }
            FileInputStream(pfd.fileDescriptor).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "hooks.json") {
                            val reader = InputStreamReader(zis)
                            return@runCatching Gson().fromJson(reader, HookRecipeManifest::class.java)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    null
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed reading hooks.json from bundle: ${it.message}")
        }.getOrNull()
    }

    private fun ensureDexKitLoaded() {
        runCatching {
            System.loadLibrary("dexkit")
        }.onFailure {
            val nativeDir = MainHook.moduleNativeLibDir
            if (nativeDir != null) {
                val so = File(nativeDir, "libdexkit.so")
                if (so.exists()) {
                    runCatching { System.load(so.absolutePath) }
                }
            }
        }
    }

    private fun initializeAuthorSettingsEntries() {
        val entryCandidates = listOf(
            "app.hushgram.extension.instagram.settings.SettingsEntry" to "onApplicationCreate",
            "app.piko.extension.instagram.settings.SettingsEntry" to "onApplicationCreate",
            "app.piko.extension.twitter.settings.SettingsEntry" to "onApplicationCreate",
            "app.morphe.extension.reddit.settings.SettingsEntry" to "onApplicationCreate",
            "app.morphe.extension.youtube.addon.AddOnApi" to "initialize",
            "app.morphe.extension.music.addon.AddOnApi" to "initialize"
        )

        for ((clsName, method) in entryCandidates) {
            runCatching {
                val res = BundleBridge.callStatic(clsName, method, app)
                if (res != null || BundleBridge.loadClass(clsName) != null) {
                    Log.d(TAG, "Invoked entrypoint: $clsName#$method")
                }
            }
        }
    }

    private fun bindDynamicHooks(bridge: DexKitBridge, hooks: List<HookDefinition>) {
        var appliedCount = 0

        for (hook in hooks) {
            val isEnabled = prefs.getBoolean(hook.name.ifEmpty { hook.id }, true)
            if (!isEnabled) {
                Log.d(TAG, "Hook [${hook.name}] is disabled by user settings")
                continue
            }

            val methods = queryMethods(bridge, hook)
            if (methods.isEmpty()) {
                Log.w(TAG, "No match found for hook: ${hook.id} (${hook.name})")
                continue
            }

            for (methodData in methods) {
                val resolvedMethod = resolveExecutable(methodData)
                if (resolvedMethod != null) {
                    installInterceptor(resolvedMethod, hook)
                    appliedCount++
                }
            }
        }

        Log.i(TAG, "Successfully bound $appliedCount dynamic interceptor(s) for ${app.packageName}")
    }

    private fun queryMethods(bridge: DexKitBridge, hook: HookDefinition): List<MethodData> {
        val fp = hook.fingerprint

        // Discriminator check: prevent overly broad queries from matching arbitrary methods
        val hasDiscriminator = !fp.methodName.isNullOrEmpty() ||
                fp.strings.isNotEmpty() ||
                fp.paramTypes.isNotEmpty() ||
                (fp.paramCount != null && fp.paramCount > 0) ||
                fp.literal != null
        if (!hasDiscriminator) {
            Log.w(TAG, "Rejecting overly broad query without discriminator: ${hook.id}")
            return emptyList()
        }

        val results = runCatching {
            bridge.findMethod {
                matcher {
                    if (!fp.declaringClass.isNullOrEmpty()) {
                        declaredClass = fp.declaringClass
                    } else if (!fp.declaringClassEndsWith.isNullOrEmpty()) {
                        declaredClass(fp.declaringClassEndsWith, StringMatchType.EndsWith)
                    }
                    if (!fp.methodName.isNullOrEmpty()) {
                        name = fp.methodName
                    }
                    if (!fp.returnType.isNullOrEmpty()) {
                        returnType = cleanType(fp.returnType)
                    }
                    if (fp.paramTypes.isNotEmpty()) {
                        paramTypes(fp.paramTypes.map { cleanType(it) })
                    } else if (fp.paramCount != null && fp.paramCount > 0) {
                        paramCount(fp.paramCount)
                    }
                    if (fp.strings.isNotEmpty()) {
                        usingStrings(fp.strings)
                    }
                    if (fp.literal != null) {
                        addUsingNumber(fp.literal)
                    }
                }
            }
        }.getOrDefault(emptyList())

        if (results.isEmpty() && fp.strings.size > 1) {
            for (str in fp.strings) {
                val fallbackResults = runCatching {
                    bridge.findMethod {
                        matcher {
                            if (!fp.declaringClass.isNullOrEmpty()) {
                                declaredClass = fp.declaringClass
                            } else if (!fp.declaringClassEndsWith.isNullOrEmpty()) {
                                declaredClass(fp.declaringClassEndsWith, StringMatchType.EndsWith)
                            }
                            if (!fp.methodName.isNullOrEmpty()) {
                                name = fp.methodName
                            }
                            if (!fp.returnType.isNullOrEmpty()) {
                                returnType = cleanType(fp.returnType)
                            }
                            if (fp.paramTypes.isNotEmpty()) {
                                paramTypes(fp.paramTypes.map { cleanType(it) })
                            } else if (fp.paramCount != null && fp.paramCount > 0) {
                                paramCount(fp.paramCount)
                            }
                            usingStrings(listOf(str))
                            if (fp.literal != null) {
                                addUsingNumber(fp.literal)
                            }
                        }
                    }
                }.getOrDefault(emptyList())
                if (fallbackResults.isNotEmpty()) return fallbackResults
            }
        }

        return results
    }

    private fun cleanType(t: String?): String {
        if (t.isNullOrEmpty()) return "void"
        val trimmed = t.trim()
        return when (trimmed) {
            "V" -> "void"
            "Z" -> "boolean"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            "S" -> "short"
            "B" -> "byte"
            "C" -> "char"
            else -> {
                if (trimmed.startsWith("L") && trimmed.endsWith(";")) {
                    trimmed.substring(1, trimmed.length - 1).replace("/", ".")
                } else if (trimmed.startsWith("[")) {
                    cleanType(trimmed.substring(1)) + "[]"
                } else {
                    trimmed.replace("/", ".")
                }
            }
        }
    }

    private fun resolveExecutable(data: MethodData): Executable? {
        return runCatching {
            val clz = hostLoader.loadClass(data.className)
            val paramClasses = data.paramTypeNames.map { hostLoader.loadClass(it) }.toTypedArray()
            if (data.isConstructor) {
                XpReflect.findConstructorExactIfExists(clz, *paramClasses)
            } else {
                XpReflect.findMethodExactIfExists(clz, data.name, *paramClasses)
            }
        }.getOrNull()
    }

    private fun installInterceptor(executable: Executable, hook: HookDefinition) {
        if (Modifier.isAbstract(executable.modifiers) || executable.declaringClass.isInterface) {
            Log.w(TAG, "Skipping abstract/interface method: $executable")
            return
        }

        val (extClass, extMethod) = parseExtensionCall(hook.extensionCall)

        runCatching {
            xposed.hook(executable).intercept { chain ->
                when (hook.action) {
                    "CONSTANT_REPLACE" -> {
                        when (val v = hook.constantVal) {
                            null, "void" -> null
                            is Boolean -> v
                            is Number -> v
                            else -> v.toString()
                        }
                    }

                    "EARLY_RETURN_SUPPRESSOR" -> {
                        if (extClass != null && extMethod != null) {
                            val shouldSuppress = BundleBridge.callStaticBoolean(extClass, extMethod, false)
                            if (shouldSuppress) {
                                null // Early return, suppressing target execution
                            } else {
                                chain.proceed()
                            }
                        } else {
                            chain.proceed()
                        }
                    }

                    "ARG_REWRITE" -> {
                        val idx = hook.argIndex
                        if (extClass != null && extMethod != null && idx < chain.args.size) {
                            val orig = chain.getArg(idx)
                            val rewritten = BundleBridge.callStatic(extClass, extMethod, orig)
                            if (rewritten != null) {
                                val newArgs = chain.args.toTypedArray()
                                newArgs[idx] = rewritten
                                @Suppress("UNCHECKED_CAST")
                                chain.proceed(newArgs as Array<Any>)
                            } else {
                                chain.proceed()
                            }
                        } else {
                            chain.proceed()
                        }
                    }

                    "RETURN_TRANSFORM" -> {
                        val originalResult = chain.proceed()
                        if (extClass != null && extMethod != null && originalResult != null) {
                            BundleBridge.callStatic(extClass, extMethod, originalResult) ?: originalResult
                        } else {
                            originalResult
                        }
                    }

                    "METHOD_REPLACE" -> {
                        if (extClass != null && extMethod != null) {
                            val argsList = mutableListOf<Any?>()
                            if (chain.thisObject != null) argsList.add(chain.thisObject)
                            argsList.addAll(chain.args)
                            BundleBridge.callStatic(extClass, extMethod, *argsList.toTypedArray())
                        } else {
                            null
                        }
                    }

                    "METHOD_AFTER" -> {
                        val origResult = chain.proceed()
                        if (extClass != null && extMethod != null) {
                            val argsList = mutableListOf<Any?>()
                            if (chain.thisObject != null) argsList.add(chain.thisObject)
                            argsList.addAll(chain.args)
                            BundleBridge.callStatic(extClass, extMethod, *argsList.toTypedArray())
                        }
                        origResult
                    }

                    else -> chain.proceed()
                }
            }
            Log.d(TAG, "Bound LibXposed 102 interceptor [${hook.action}] to ${executable.name} for patch [${hook.name}]")
        }.onFailure { e ->
            Log.e(TAG, "Failed to hook $executable for patch ${hook.id}: ${e.message}")
        }
    }

    private fun parseExtensionCall(desc: String?): Pair<String?, String?> {
        if (desc.isNullOrEmpty()) return Pair(null, null)
        val parts = desc.split("->")
        if (parts.size != 2) return Pair(null, null)
        val cls = parts[0].trim().removePrefix("L").removeSuffix(";").replace("/", ".")
        val method = parts[1].split("(")[0].trim()
        return Pair(cls, method)
    }
}
