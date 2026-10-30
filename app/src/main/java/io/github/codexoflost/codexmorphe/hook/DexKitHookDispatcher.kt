package io.github.codexoflost.codexmorphe.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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

        // 1. Initialize Bundle Bridge
        BundleBridge.init(bundleLoader)

        // 2. Read hooks.json from the bundle APK stream
        val manifest = extractHookManifest()
        val entrypoints = manifest?.entrypoints.orEmpty()
        val resourceFallbacks = manifest?.resourceFallbacks.orEmpty()

        // 3. Initialize extension context & resource lookups dynamically
        BundleBridge.initializeExtensionContext(app, entrypoints, resourceFallbacks)

        // 4. Dispatch dynamic extension entrypoints from manifest (SettingsEntry, AddOnApi, etc.)
        for (ep in entrypoints) {
            val parts = ep.split("#")
            if (parts.size == 2) {
                val cls = parts[0].trim()
                val mtd = parts[1].trim()
                if (mtd == "setContext") continue // Already dispatched by BundleBridge
                runCatching {
                    val res = BundleBridge.callStatic(cls, mtd, app)
                    if (res != null || BundleBridge.loadClass(cls) != null) {
                        Log.d(TAG, "Dispatched manifest entrypoint: $ep")
                    }
                }
            }
        }

        if (manifest == null || manifest.hooks.isEmpty()) {
            Log.i(TAG, "No dynamic hook recipes in bundle for ${app.packageName}; context initializers only.")
            return
        }

        Log.i(TAG, "Loaded ${manifest.hooks.size} dynamic hook recipes from bundle for ${app.packageName}")

        // 5. Bind hooks dynamically using signature cache + DexKit fallback
        bindDynamicHooksWithCache(manifest.hooks)
    }

    private val LIFECYCLE_METHODS = setOf(
        "onCreate", "onActivityCreated", "attachBaseContext", "onResume", "onStart", "onPause", "onStop", "onDestroy"
    )

    private fun extractHookManifest(): HookRecipeManifest? {
        return runCatching {
            runCatching {
                android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            }
            FileInputStream(pfd.fileDescriptor).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "hooks.json" || entry.name.endsWith("hooks.json")) {
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

    private fun bindDynamicHooksWithCache(hooks: List<HookDefinition>) {
        val versionCode = runCatching {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        }.getOrDefault(0L)

        val cachePrefs = app.getSharedPreferences("dexkit_cache_${app.packageName}", Context.MODE_PRIVATE)
        val cachedVersion = cachePrefs.getLong("cached_version_code", -1L)
        if (cachedVersion != versionCode) {
            Log.i(TAG, "Target app version changed ($cachedVersion -> $versionCode); clearing DexKit cache.")
            cachePrefs.edit().clear().putLong("cached_version_code", versionCode).apply()
        }

        var bridge: DexKitBridge? = null
        var appliedCount = 0
        var cachedCount = 0

        try {
            for (hook in hooks) {
                val isEnabled = prefs.getBoolean(hook.name.ifEmpty { hook.id }, true)
                if (!isEnabled) {
                    Log.d(TAG, "Hook [${hook.name}] is disabled by user settings")
                    continue
                }

                // 1. Check persistent signature cache
                val cachedSigs = cachePrefs.getString("hook_${hook.id}", null)
                if (!cachedSigs.isNullOrEmpty()) {
                    val resolvedMethods = resolveCachedExecutables(cachedSigs)
                    if (resolvedMethods.isNotEmpty()) {
                        for (exec in resolvedMethods) {
                            installInterceptor(exec, hook)
                            appliedCount++
                        }
                        cachedCount++
                        continue
                    }
                }

                // 2. Cache miss: Lazy initialize DexKit bridge
                if (bridge == null) {
                    ensureDexKitLoaded()
                    bridge = runCatching {
                        DexKitBridge.create(app.applicationInfo.sourceDir)
                    }.onFailure {
                        Log.e(TAG, "Failed to create DexKitBridge for ${app.applicationInfo.sourceDir}: ${it.message}")
                    }.getOrNull()
                }

                if (bridge == null) continue

                val methods = queryMethods(bridge, hook)
                if (methods.isEmpty()) {
                    Log.w(TAG, "No match found for hook: ${hook.id} (${hook.name})")
                    continue
                }

                val sigs = mutableListOf<String>()
                for (methodData in methods) {
                    val resolvedMethod = resolveExecutable(methodData)
                    if (resolvedMethod != null) {
                        installInterceptor(resolvedMethod, hook)
                        appliedCount++
                        sigs.add("${methodData.className}#${methodData.name}#${methodData.paramTypeNames.joinToString(",")}#${methodData.isConstructor}")
                    }
                }

                if (sigs.isNotEmpty()) {
                    cachePrefs.edit().putString("hook_${hook.id}", sigs.joinToString("|")).apply()
                }
            }
        } finally {
            bridge?.let { runCatching { it.close() } }
        }

        Log.i(TAG, "Successfully bound $appliedCount dynamic interceptor(s) for ${app.packageName} ($cachedCount resolved via fast signature cache)")
    }

    private fun resolveCachedExecutables(cachedSigs: String): List<Executable> {
        val list = mutableListOf<Executable>()
        for (item in cachedSigs.split("|")) {
            val parts = item.split("#")
            if (parts.size != 4) return emptyList()
            val className = parts[0]
            val methodName = parts[1]
            val paramStr = parts[2]
            val isConstructor = parts[3].toBoolean()

            val exec = runCatching {
                val clz = hostLoader.loadClass(className)
                val paramClasses = if (paramStr.isEmpty()) {
                    emptyArray()
                } else {
                    paramStr.split(",").map { loadClassOrPrimitive(it, hostLoader) }.toTypedArray()
                }

                if (isConstructor) {
                    XpReflect.findConstructorExactIfExists(clz, *paramClasses)
                } else {
                    XpReflect.findMethodExactIfExists(clz, methodName, *paramClasses)
                }
            }.getOrNull() ?: return emptyList()

            list.add(exec)
        }
        return list
    }

    private fun loadClassOrPrimitive(name: String, loader: ClassLoader): Class<*> {
        return when (name) {
            "void" -> java.lang.Void.TYPE
            "boolean" -> java.lang.Boolean.TYPE
            "int" -> java.lang.Integer.TYPE
            "long" -> java.lang.Long.TYPE
            "float" -> java.lang.Float.TYPE
            "double" -> java.lang.Double.TYPE
            "short" -> java.lang.Short.TYPE
            "byte" -> java.lang.Byte.TYPE
            "char" -> java.lang.Character.TYPE
            else -> {
                if (name.endsWith("[]")) {
                    val element = loadClassOrPrimitive(name.removeSuffix("[]"), loader)
                    java.lang.reflect.Array.newInstance(element, 0).javaClass
                } else {
                    loader.loadClass(name)
                }
            }
        }
    }

    private fun queryMethods(bridge: DexKitBridge, hook: HookDefinition): List<MethodData> {
        val fp = hook.fingerprint

        // Discriminator check: prevent overly broad queries from matching arbitrary methods
        val hasDiscriminator = !fp.methodName.isNullOrEmpty() ||
                fp.strings.isNotEmpty() ||
                fp.literal != null ||
                (fp.paramTypes.isNotEmpty() && !fp.declaringClass.isNullOrEmpty())

        if (!hasDiscriminator) {
            Log.w(TAG, "Rejecting overly broad/wildcard query without discriminator: ${hook.id} (class=${fp.declaringClass})")
            return emptyList()
        }

        // Strict guard: If declaringClass is set, but methodName, strings, and literal are all missing: REJECT!
        if (fp.methodName.isNullOrEmpty() && fp.strings.isEmpty() && fp.literal == null) {
            Log.w(TAG, "Rejecting wildcard query matching entire class: ${hook.id} (${fp.declaringClass})")
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
                    if (fp.paramTypes.isNotEmpty() && fp.paramTypes.none { it == "L" }) {
                        paramTypes(fp.paramTypes.map { cleanType(it) })
                    } else if (fp.paramTypes.isNotEmpty() && fp.paramTypes.any { it == "L" }) {
                        paramCount(fp.paramTypes.size)
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
                if (str.length < 5) continue
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
                            if (fp.paramTypes.isNotEmpty() && fp.paramTypes.none { it == "L" }) {
                                paramTypes(fp.paramTypes.map { cleanType(it) })
                            } else if (fp.paramTypes.isNotEmpty() && fp.paramTypes.any { it == "L" }) {
                                paramCount(fp.paramTypes.size)
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

        if (results.size > 1 && fp.methodName.isNullOrEmpty() && fp.strings.isEmpty()) {
            Log.w(TAG, "Rejecting query that matched multiple methods (${results.size}) without method name: ${hook.id}")
            return emptyList()
        }

        if (results.size > 5 && fp.declaringClass.isNullOrEmpty() && fp.methodName.isNullOrEmpty()) {
            Log.w(TAG, "Rejecting query that matched too many methods (${results.size}) without class or method name: ${hook.id}")
            return emptyList()
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

    private fun isBoxedTypeOf(targetClass: Class<*>, boxedObj: Any): Boolean {
        return when (targetClass) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> boxedObj is Boolean
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> boxedObj is Int
            java.lang.Long.TYPE, java.lang.Long::class.java -> boxedObj is Long
            java.lang.Float.TYPE, java.lang.Float::class.java -> boxedObj is Float
            java.lang.Double.TYPE, java.lang.Double::class.java -> boxedObj is Double
            java.lang.Short.TYPE, java.lang.Short::class.java -> boxedObj is Short
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> boxedObj is Byte
            java.lang.Character.TYPE, java.lang.Character::class.java -> boxedObj is Char
            else -> targetClass.isAssignableFrom(boxedObj.javaClass)
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

        val (extClass, extMethod) = parseExtensionCall(hook)

        runCatching {
            xposed.hook(executable).intercept { chain ->
                when (hook.action) {
                    "CONSTANT_REPLACE", "EARLY_RETURN_CONSTANT" -> {
                        val returnType = (executable as? Method)?.returnType
                        when (val v = hook.constantVal) {
                            null, "void" -> {
                                if (returnType == java.lang.Void.TYPE) null else chain.proceed()
                            }
                            is Boolean -> {
                                if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java) v else chain.proceed()
                            }
                            is Number -> {
                                if (returnType != null && (returnType.isPrimitive || Number::class.java.isAssignableFrom(returnType))) v else chain.proceed()
                            }
                            else -> chain.proceed()
                        }
                    }

                    "BEFORE", "EARLY_RETURN_SUPPRESSOR" -> {
                        if (executable.name in LIFECYCLE_METHODS) {
                            chain.proceed()
                        } else if (extClass != null && extMethod != null) {
                            val argsList = mutableListOf<Any?>()
                            if (chain.thisObject != null) argsList.add(chain.thisObject)
                            argsList.addAll(chain.args)
                            val shouldSuppress = runCatching {
                                BundleBridge.callStaticBoolean(extClass, extMethod, false, *argsList.toTypedArray())
                            }.getOrDefault(false)

                            if (shouldSuppress) {
                                val returnType = (executable as? Method)?.returnType
                                when (returnType) {
                                    java.lang.Void.TYPE -> null
                                    java.lang.Boolean.TYPE -> false
                                    java.lang.Integer.TYPE -> 0
                                    java.lang.Long.TYPE -> 0L
                                    java.lang.Float.TYPE -> 0.0f
                                    java.lang.Double.TYPE -> 0.0
                                    java.lang.Short.TYPE -> 0.toShort()
                                    java.lang.Byte.TYPE -> 0.toByte()
                                    java.lang.Character.TYPE -> '\u0000'
                                    else -> null
                                }
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
                            val rewritten = runCatching {
                                BundleBridge.callStatic(extClass, extMethod, orig)
                            }.getOrNull()
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
                            val transformArgs = mutableListOf<Any?>(originalResult)
                            if (chain.thisObject != null) transformArgs.add(chain.thisObject)
                            transformArgs.addAll(chain.args)
                            val transformed = runCatching {
                                BundleBridge.callStatic(extClass, extMethod, *transformArgs.toTypedArray())
                            }.getOrNull()

                            val returnType = (executable as? Method)?.returnType
                            if (transformed != null && returnType != null) {
                                if (!isBoxedTypeOf(returnType, transformed)) {
                                    Log.w(TAG, "Type mismatch in RETURN_TRANSFORM for ${hook.id}: expected $returnType, got ${transformed.javaClass}. Falling back to original.")
                                    originalResult
                                } else {
                                    transformed
                                }
                            } else {
                                transformed ?: originalResult
                            }
                        } else {
                            originalResult
                        }
                    }

                    "REPLACE", "METHOD_REPLACE" -> {
                        if (executable.name in LIFECYCLE_METHODS) {
                            chain.proceed()
                        } else if (extClass != null && extMethod != null) {
                            val argsList = mutableListOf<Any?>()
                            if (chain.thisObject != null) argsList.add(chain.thisObject)
                            argsList.addAll(chain.args)
                            runCatching {
                                BundleBridge.callStatic(extClass, extMethod, *argsList.toTypedArray())
                            }.getOrElse { chain.proceed() }
                        } else {
                            chain.proceed()
                        }
                    }

                    "AFTER", "METHOD_AFTER" -> {
                        val origResult = chain.proceed()
                        if (extClass != null && extMethod != null) {
                            runCatching {
                                val argsList = mutableListOf<Any?>()
                                if (chain.thisObject != null) argsList.add(chain.thisObject)
                                argsList.addAll(chain.args)
                                BundleBridge.callStatic(extClass, extMethod, *argsList.toTypedArray())
                            }.onFailure { e ->
                                Log.w(TAG, "Error in AFTER hook for ${hook.id}: ${e.message}")
                            }
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

    private fun parseExtensionCall(hook: HookDefinition): Pair<String?, String?> {
        if (!hook.extensionClass.isNullOrEmpty() && !hook.extensionMethod.isNullOrEmpty()) {
            return Pair(hook.extensionClass.removePrefix("L").removeSuffix(";").replace("/", "."), hook.extensionMethod)
        }
        return parseExtensionCall(hook.extensionCall)
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
