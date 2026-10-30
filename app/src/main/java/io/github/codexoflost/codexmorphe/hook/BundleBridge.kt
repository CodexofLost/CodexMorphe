package io.github.codexoflost.codexmorphe.hook

import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges hook execution in target apps to dynamic classes and methods
 * loaded in-memory from streamed Morphe/Hushgram/Piko bundle APKs.
 */
object BundleBridge {
    private const val TAG = "CodexMorphe/Bridge"
    private var bundleClassLoader: ClassLoader? = null
    private val methodCache = ConcurrentHashMap<String, Method?>()

    fun init(loader: ClassLoader) {
        this.bundleClassLoader = loader
        this.methodCache.clear()
        Log.d(TAG, "BundleBridge initialized with ClassLoader: $loader")
    }

    val loader: ClassLoader?
        get() = bundleClassLoader

    fun loadClass(className: String): Class<*>? {
        val l = bundleClassLoader ?: return null
        return runCatching { Class.forName(className, false, l) }.getOrNull()
    }

    fun callStatic(className: String, methodName: String, vararg args: Any?): Any? {
        val cacheKey = "$className#$methodName(${args.map { it?.javaClass?.simpleName ?: "null" }.joinToString()})"
        val method = methodCache.computeIfAbsent(cacheKey) {
            val clazz = loadClass(className) ?: return@computeIfAbsent null
            findBestMatchingMethod(clazz, methodName, args)
        } ?: return null

        return runCatching {
            val params = method.parameterTypes
            val finalArgs = when {
                params.isEmpty() -> emptyArray()
                params.size <= args.size -> args.take(params.size).toTypedArray()
                else -> args
            }
            method.invoke(null, *finalArgs)
        }.onFailure {
            Log.e(TAG, "Error invoking $className#$methodName: ${it.message}", it)
        }.getOrNull()
    }

    private fun findBestMatchingMethod(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method? {
        val candidates = clazz.declaredMethods.filter { it.name == methodName }
        if (candidates.isEmpty()) return null

        // 1. Exact match by count & assignability
        val exact = candidates.firstOrNull { m ->
            m.parameterTypes.size == args.size && m.parameterTypes.indices.all { i ->
                isAssignable(m.parameterTypes[i], args[i])
            }
        }
        if (exact != null) {
            exact.isAccessible = true
            return exact
        }

        // 2. Prefix match (e.g. method takes [Activity], called with [Activity, Bundle])
        val prefix = candidates.firstOrNull { m ->
            m.parameterTypes.isNotEmpty() && m.parameterTypes.size <= args.size && m.parameterTypes.indices.all { i ->
                isAssignable(m.parameterTypes[i], args[i])
            }
        }
        if (prefix != null) {
            prefix.isAccessible = true
            return prefix
        }

        // 3. Zero-arg fallback (e.g. initialize())
        val noArgs = candidates.firstOrNull { it.parameterTypes.isEmpty() }
        if (noArgs != null) {
            noArgs.isAccessible = true
            return noArgs
        }

        return null
    }

    private fun isAssignable(paramType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !paramType.isPrimitive
        val argClass = arg.javaClass
        if (paramType.isAssignableFrom(argClass)) return true

        return (paramType == java.lang.Boolean.TYPE && argClass == java.lang.Boolean::class.java) ||
               (paramType == java.lang.Integer.TYPE && argClass == java.lang.Integer::class.java) ||
               (paramType == java.lang.Long.TYPE && argClass == java.lang.Long::class.java) ||
               (paramType == java.lang.Float.TYPE && argClass == java.lang.Float::class.java) ||
               (paramType == java.lang.Double.TYPE && argClass == java.lang.Double::class.java) ||
               (paramType == java.lang.Byte.TYPE && argClass == java.lang.Byte::class.java) ||
               (paramType == java.lang.Short.TYPE && argClass == java.lang.Short::class.java) ||
               (paramType == java.lang.Character.TYPE && argClass == java.lang.Character::class.java)
    }

    fun callStaticBoolean(className: String, methodName: String, default: Boolean = false, vararg args: Any?): Boolean {
        return callStatic(className, methodName, *args) as? Boolean ?: default
    }

    fun callStaticString(className: String, methodName: String, default: String? = null, vararg args: Any?): String? {
        return callStatic(className, methodName, *args) as? String ?: default
    }

    fun initializeExtensionContext(context: Context) {
        val utilsCandidates = listOf(
            "app.morphe.extension.shared.Utils",
            "app.hushgram.extension.shared.Utils",
            "app.piko.extension.shared.Utils",
            "app.rushiranpise.extension.shared.Utils",
            "app.devanced.extension.shared.Utils",
            "app.okish.extension.shared.Utils"
        )
        for (candidate in utilsCandidates) {
            val res = callStatic(candidate, "setContext", context)
            if (res != null || loadClass(candidate) != null) {
                Log.i(TAG, "Initialized extension context via $candidate")
                break
            }
        }
    }
}
