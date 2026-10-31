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
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.lang.reflect.Constructor
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

    private fun resolveDirectExecutable(fp: io.github.codexoflost.codexmorphe.hook.model.FingerprintQuery): Executable? {
        val className = fp.declaringClass?.takeIf { it.isNotEmpty() } ?: return null
        val methodName = fp.methodName?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val clz = hostLoader.loadClass(className)
            if (fp.paramTypes.isEmpty()) {
                if (methodName == "<init>") {
                    XpReflect.findConstructorExactIfExists(clz)
                } else {
                    val methods = clz.declaredMethods.filter { it.name == methodName }
                    if (methods.size == 1) methods[0] else null
                }
            } else if (fp.paramTypes.any { it == "L" }) {
                null
            } else {
                val paramClasses = fp.paramTypes.map { loadClassOrPrimitive(cleanType(it), hostLoader) }.toTypedArray()
                if (methodName == "<init>") {
                    XpReflect.findConstructorExactIfExists(clz, *paramClasses)
                } else {
                    XpReflect.findMethodExactIfExists(clz, methodName, *paramClasses)
                }
            }
        }.getOrNull()
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
        val currentSchema = cachePrefs.getInt("cache_schema_version", 0)
        val CACHE_SCHEMA_VERSION = 4
        if (cachedVersion != versionCode || currentSchema != CACHE_SCHEMA_VERSION) {
            Log.i(TAG, "Target app version or cache schema changed ($cachedVersion -> $versionCode, schema $currentSchema -> $CACHE_SCHEMA_VERSION); clearing DexKit cache.")
            cachePrefs.edit().clear()
                .putLong("cached_version_code", versionCode)
                .putInt("cache_schema_version", CACHE_SCHEMA_VERSION)
                .apply()
        }

        var appliedCount = 0
        var cachedCount = 0
        var directCount = 0
        val uncachedHooks = mutableListOf<HookDefinition>()
        val directSigsToCache = mutableMapOf<String, String>()

        // Pass 1: Synchronous Fast Path (Cache hits + Direct reflection)
        for (hook in hooks) {
            val isEnabled = isHookEnabled(hook)
            if (!isEnabled) {
                Log.d(TAG, "Hook [${hook.name.ifEmpty { hook.id }}] is disabled by user settings")
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

            // 2. Direct Reflection Fast Path (known class + method)
            val directExec = resolveDirectExecutable(hook.fingerprint)
            if (directExec != null) {
                installInterceptor(directExec, hook)
                appliedCount++
                directCount++
                val paramStr = if (directExec is Method) {
                    directExec.parameterTypes.joinToString(",") { it.name }
                } else if (directExec is Constructor<*>) {
                    directExec.parameterTypes.joinToString(",") { it.name }
                } else ""
                val isCtor = directExec is Constructor<*>
                val sig = "${directExec.declaringClass.name}#${directExec.name}#${paramStr}#${isCtor}"
                directSigsToCache["hook_${hook.id}"] = sig
                continue
            }

            // 3. Needs DexKit query
            uncachedHooks.add(hook)
        }

        // Commit direct signatures to cache immediately
        if (directSigsToCache.isNotEmpty()) {
            val editor = cachePrefs.edit()
            directSigsToCache.forEach { (k, v) -> editor.putString(k, v) }
            editor.apply()
        }

        Log.i(TAG, "Synchronous fast path: bound $appliedCount hook(s) ($cachedCount cached, $directCount direct reflection). ${uncachedHooks.size} remaining for DexKit.")

        if (uncachedHooks.isEmpty()) {
            Log.i(TAG, "All hooks bound in fast path; zero DexKit scanning needed.")
            return
        }

        // Pass 2: Asynchronous Background Worker for DexKit bytecode scanning
        // Never block Application.onCreate with hundreds of Dalvik bytecode searches!
        kotlin.concurrent.thread(name = "CodexMorphe-DexKitWorker", priority = Thread.NORM_PRIORITY) {
            var bridge: DexKitBridge? = null
            var bgAppliedCount = 0
            val editor = cachePrefs.edit()
            val dexCache = SharedPrefDexKitCache(cachePrefs)

            try {
                ensureDexKitLoaded()
                runCatching { DexKitCacheBridge.init(dexCache) }
                bridge = runCatching {
                    DexKitBridge.create(app.applicationInfo.sourceDir)
                }.onFailure {
                    Log.e(TAG, "Failed to create DexKitBridge for ${app.applicationInfo.sourceDir}: ${it.message}")
                }.getOrNull()

                if (bridge == null) return@thread

                val resolvedFingerprintClasses = java.util.concurrent.ConcurrentHashMap<String, String>()
                val sortedHooks = uncachedHooks.sortedBy { if (it.fingerprint.parentFingerprint.isNullOrEmpty()) 0 else 1 }

                for (hook in sortedHooks) {
                    val methods = queryMethods(bridge, hook, resolvedFingerprintClasses)
                    if (methods.isEmpty()) {
                        Log.w(TAG, "No match found for hook: ${hook.id} (${hook.name})")
                        continue
                    }

                    val sigs = mutableListOf<String>()
                    for (methodData in methods) {
                        val resolvedMethod = resolveExecutable(methodData)
                        if (resolvedMethod != null) {
                            installInterceptor(resolvedMethod, hook)
                            bgAppliedCount++
                            sigs.add("${methodData.className}#${methodData.name}#${methodData.paramTypeNames.joinToString(",")}#${methodData.isConstructor}")
                            val fpClass = hook.fingerprint.fingerprintClass
                            if (!fpClass.isNullOrEmpty()) {
                                resolvedFingerprintClasses[fpClass] = methodData.className
                                resolvedFingerprintClasses[fpClass.substringAfterLast(".")] = methodData.className
                            }
                        }
                    }

                    if (sigs.isNotEmpty()) {
                        editor.putString("hook_${hook.id}", sigs.joinToString("|"))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in background DexKit worker for ${app.packageName}: ${t.message}", t)
            } finally {
                editor.apply()
                dexCache.save()
                bridge?.let { runCatching { it.close() } }
                Log.i(TAG, "Background DexKit worker completed: bound $bgAppliedCount dynamic interceptor(s) for ${app.packageName}")
            }
        }
    }

    @OptIn(DexKitExperimentalApi::class)
    private class SharedPrefDexKitCache(private val prefs: SharedPreferences) : DexKitCacheBridge.Cache {
        @Suppress("UNCHECKED_CAST")
        private val map = mutableMapOf<String, String>().apply {
            putAll(prefs.all.filterValues { it is String } as Map<String, String>)
        }

        override fun clearAll() {
            map.clear()
        }

        override fun getString(key: String, default: String?): String? = map.getOrDefault(key, default)

        override fun getAllKeys(): Collection<String> = map.keys

        override fun getStringList(key: String, default: List<String>?): List<String>? =
            map[key]?.takeIf { it.isNotBlank() }?.split('|') ?: default

        override fun putString(key: String, value: String) {
            map[key] = value
        }

        override fun putStringList(key: String, value: List<String>) {
            map[key] = value.joinToString("|")
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        fun save() {
            val editor = prefs.edit()
            map.forEach { (k, v) -> editor.putString("dkc_$k", v) }
            editor.apply()
        }
    }

    private fun resolveCachedExecutables(cachedSigs: String): List<Executable> {
        val items = cachedSigs.split("|")
        if (items.size > 5) {
            Log.w(TAG, "Rejecting corrupted cache entry with too many matches (${items.size}): $cachedSigs")
            return emptyList()
        }
        val list = mutableListOf<Executable>()
        for (item in items) {
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

    private val COMMON_METHODS = setOf(
        "onCreate", "onActivityCreated", "attachBaseContext", "onResume", "onStart",
        "onPause", "onStop", "onDestroy", "run", "toString", "equals", "hashCode", "close"
    )

    private fun queryMethods(
        bridge: DexKitBridge,
        hook: HookDefinition,
        resolvedFingerprintClasses: Map<String, String> = emptyMap()
    ): List<MethodData> {
        val fp = hook.fingerprint
        val parentClass = if (!fp.parentFingerprint.isNullOrEmpty()) {
            resolvedFingerprintClasses[fp.parentFingerprint]
                ?: resolvedFingerprintClasses[fp.parentFingerprint.substringAfterLast(".")]
                ?: fp.parentQuery?.let { pq ->
                    val parentResults = queryMethods(bridge, hook.copy(id = "${hook.id}_parent", fingerprint = pq), resolvedFingerprintClasses)
                    parentResults.firstOrNull()?.className?.also { cls ->
                        if (resolvedFingerprintClasses is java.util.concurrent.ConcurrentMap) {
                            resolvedFingerprintClasses[fp.parentFingerprint] = cls
                            resolvedFingerprintClasses[fp.parentFingerprint.substringAfterLast(".")] = cls
                        }
                    }
                }
        } else null

        val hasClassDiscriminator = !parentClass.isNullOrEmpty() || !fp.declaringClass.isNullOrEmpty() || !fp.declaringClassEndsWith.isNullOrEmpty()
        val hasValidMethodName = !fp.methodName.isNullOrEmpty()
        val hasStrings = fp.strings.isNotEmpty()

        // Resolve runtime integer resource identifier if specified
        val resolvedLiteral: Long? = if (!fp.resourceName.isNullOrEmpty()) {
            val resType = fp.resourceType ?: "string"
            val resId = runCatching {
                app.resources.getIdentifier(fp.resourceName, resType, app.packageName)
            }.getOrDefault(0)
            if (resId != 0) resId.toLong() else fp.literal
        } else {
            fp.literal
        }
        val hasLiteral = resolvedLiteral != null
        val hasConcreteParam = fp.paramTypes.any { it != "L" && it.length > 5 && !it.startsWith("java.") && !it.startsWith("android.") }

        // Strict invariant: Reject any unconstrained query that lacks a method name, strings, literal, concrete params, and parentClass.
        if (!hasValidMethodName && !hasStrings && !hasLiteral && !hasConcreteParam && !hasClassDiscriminator) {
            Log.w(TAG, "Rejecting unconstrained query lacking method_name, strings, literal, concrete params, and parentClass: ${hook.id}")
            return emptyList()
        }

        // Strict guard: If declaringClass is set, but methodName, strings, literal, and concrete param are all missing: REJECT!
        if (fp.methodName.isNullOrEmpty() && fp.strings.isEmpty() && resolvedLiteral == null && !hasConcreteParam) {
            Log.w(TAG, "Rejecting wildcard query matching entire class: ${hook.id} (${parentClass ?: fp.declaringClass})")
            return emptyList()
        }

        val results = runCatching {
            bridge.findMethod {
                matcher {
                    if (!parentClass.isNullOrEmpty()) {
                        declaredClass = parentClass
                    } else if (!fp.declaringClass.isNullOrEmpty()) {
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
                    if (resolvedLiteral != null && fp.strings.isEmpty()) {
                        addUsingNumber(resolvedLiteral)
                    }
                }
            }
        }.getOrDefault(emptyList())

        val filteredResults = if (fp.paramTypes.isNotEmpty() && fp.paramTypes.any { it == "L" }) {
            results.filter { methodData ->
                if (methodData.paramTypeNames.size != fp.paramTypes.size) return@filter false
                for (i in fp.paramTypes.indices) {
                    val expected = fp.paramTypes[i]
                    if (expected != "L") {
                        val cleanedExpected = cleanType(expected)
                        val actual = methodData.paramTypeNames.getOrNull(i)
                        if (actual != cleanedExpected) return@filter false
                    }
                }
                true
            }
        } else {
            results
        }

        var finalResults = filteredResults
        if (finalResults.isEmpty() && fp.strings.size > 1) {
            for (str in fp.strings) {
                if (str.length < 5) continue
                val fallbackResults = runCatching {
                    bridge.findMethod {
                        matcher {
                            if (!parentClass.isNullOrEmpty()) {
                                declaredClass = parentClass
                            } else if (!fp.declaringClass.isNullOrEmpty()) {
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
                            if (resolvedLiteral != null && fp.strings.isEmpty()) {
                                addUsingNumber(resolvedLiteral)
                            }
                        }
                    }
                }.getOrDefault(emptyList())

                val filteredFallback = if (fp.paramTypes.isNotEmpty() && fp.paramTypes.any { it == "L" }) {
                    fallbackResults.filter { methodData ->
                        if (methodData.paramTypeNames.size != fp.paramTypes.size) return@filter false
                        for (i in fp.paramTypes.indices) {
                            val expected = fp.paramTypes[i]
                            if (expected != "L") {
                                val cleanedExpected = cleanType(expected)
                                val actual = methodData.paramTypeNames.getOrNull(i)
                                if (actual != cleanedExpected) return@filter false
                            }
                        }
                        true
                    }
                } else {
                    fallbackResults
                }

                if (filteredFallback.size == 1 || (hasClassDiscriminator && filteredFallback.isNotEmpty())) {
                    finalResults = filteredFallback
                    break
                }
            }
        }

        val distinctClasses = finalResults.map { it.className }.distinct()
        if (distinctClasses.size > 1 && !hasClassDiscriminator) {
            Log.w(TAG, "Rejecting ambiguous query matching ${finalResults.size} methods across ${distinctClasses.size} distinct classes without declaringClass: ${hook.id} (${distinctClasses.joinToString()})")
            return emptyList()
        }

        if (finalResults.size > 5 && !hasClassDiscriminator) {
            Log.w(TAG, "Rejecting query that matched too many methods (${finalResults.size}) without declaringClass: ${hook.id}")
            return emptyList()
        }

        return finalResults
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
                        val argsList = mutableListOf<Any?>()
                        if (chain.thisObject != null) argsList.add(chain.thisObject)
                        argsList.addAll(chain.args)

                        val res = if (extClass != null && extMethod != null) {
                            runCatching {
                                BundleBridge.callStatic(extClass, extMethod, *argsList.toTypedArray())
                            }.getOrNull() ?: runCatching {
                                if (chain.thisObject != null && chain.args.isNotEmpty()) {
                                    BundleBridge.callStatic(extClass, extMethod, *chain.args.toTypedArray())
                                } else null
                            }.getOrNull()
                        } else null

                        if (executable.name in LIFECYCLE_METHODS) {
                            // Lifecycle methods must never be suppressed
                            chain.proceed()
                        } else if (res is Boolean && res) {
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
                    }

                    "ARG_REWRITE" -> {
                        val idx = hook.argIndex
                        if (extClass != null && extMethod != null && idx < chain.args.size) {
                            val orig = chain.getArg(idx)
                            val rewriteArgs = mutableListOf<Any?>()
                            if (chain.thisObject != null) rewriteArgs.add(chain.thisObject)
                            rewriteArgs.add(orig)
                            val rewritten = runCatching {
                                BundleBridge.callStatic(extClass, extMethod, *rewriteArgs.toTypedArray())
                                    ?: BundleBridge.callStatic(extClass, extMethod, orig)
                            }.getOrNull()

                            val expectedArgType = (executable as? Method)?.parameterTypes?.getOrNull(idx)
                                ?: (executable as? Constructor<*>)?.parameterTypes?.getOrNull(idx)
                            if (rewritten != null && expectedArgType != null && isBoxedTypeOf(expectedArgType, rewritten)) {
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
                                    ?: BundleBridge.callStatic(extClass, extMethod, originalResult, *chain.args.toTypedArray())
                                    ?: BundleBridge.callStatic(extClass, extMethod, originalResult)
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
                                originalResult
                            }
                        } else {
                            originalResult
                        }
                    }

                    "REPLACE", "METHOD_REPLACE" -> {
                        if (executable.name in LIFECYCLE_METHODS || executable is Constructor<*>) {
                            chain.proceed()
                        } else if (extClass != null && extMethod != null) {
                            val argsList = mutableListOf<Any?>()
                            if (chain.thisObject != null) argsList.add(chain.thisObject)
                            argsList.addAll(chain.args)
                            val res = runCatching {
                                BundleBridge.callStatic(extClass, extMethod, *argsList.toTypedArray())
                                    ?: if (chain.thisObject != null && chain.args.isNotEmpty()) {
                                        BundleBridge.callStatic(extClass, extMethod, *chain.args.toTypedArray())
                                    } else null
                            }.getOrNull()

                            val returnType = (executable as? Method)?.returnType
                            if (res == null && returnType != null && returnType != java.lang.Void.TYPE) {
                                Log.w(TAG, "REPLACE hook returned null for non-void method $executable; falling back to original proceed()")
                                chain.proceed()
                            } else if (res != null && returnType != null && !isBoxedTypeOf(returnType, res)) {
                                Log.w(TAG, "REPLACE hook return type mismatch for $executable (expected $returnType, got ${res.javaClass}); falling back to proceed()")
                                chain.proceed()
                            } else {
                                res
                            }
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
                                    ?: if (chain.thisObject != null && chain.args.isNotEmpty()) {
                                        BundleBridge.callStatic(extClass, extMethod, *chain.args.toTypedArray())
                                    } else null
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

    private fun isHookEnabled(hook: HookDefinition): Boolean {
        val candidates = mutableListOf<String>()
        if (hook.id.isNotEmpty()) {
            candidates.add("morphe_${hook.id}")
            candidates.add(hook.id)
        }
        if (hook.name.isNotEmpty()) {
            val kebab = hook.name.lowercase().replace(" ", "-")
            val snake = hook.name.lowercase().replace(" ", "_")
            candidates.add("morphe_$kebab")
            candidates.add("morphe_$snake")
            candidates.add("morphe_${hook.name}")
            candidates.add(kebab)
            candidates.add(snake)
            candidates.add(hook.name)
        }
        for (key in candidates.distinct()) {
            if (prefs.contains(key)) {
                return prefs.getBoolean(key, true)
            }
        }
        return true
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
        if (cls.isEmpty() || method.isEmpty()) return Pair(null, null)
        return Pair(cls, method)
    }
}
