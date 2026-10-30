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
            val finalArgs: Array<out Any?> = when {
                params.isEmpty() -> emptyArray()
                params.size == args.size && params.indices.all { isAssignable(params[it], args[it]) } -> args
                params.size <= args.size && params.indices.all { isAssignable(params[it], args[it]) } -> args.take(params.size).toTypedArray()
                args.size > 1 && params.size <= args.size - 1 && params.indices.all { isAssignable(params[it], args[it + 1]) } -> args.drop(1).take(params.size).toTypedArray()
                params.size == 1 -> {
                    val matchingArg = args.firstOrNull { isAssignable(params[0], it) }
                    arrayOf(matchingArg)
                }
                else -> args.take(params.size).toTypedArray()
            }

            val isCompatible = params.size == finalArgs.size && params.indices.all { i ->
                isAssignable(params[i], finalArgs[i])
            }
            if (!isCompatible) {
                Log.w(TAG, "Skipping invocation of $className#$methodName: parameter type mismatch")
                return@runCatching null
            }

            method.invoke(null, *finalArgs)
        }.onFailure {
            Log.e(TAG, "Error invoking $className#$methodName: ${it.message}", it)
        }.getOrNull()
    }

    private fun findBestMatchingMethod(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method? {
        val candidates = clazz.declaredMethods.filter { it.name == methodName }
        if (candidates.isEmpty()) return null

        // 1. Exact match by count & assignability with all args
        val exact = candidates.firstOrNull { m ->
            m.parameterTypes.size == args.size && m.parameterTypes.indices.all { i ->
                isAssignable(m.parameterTypes[i], args[i])
            }
        }
        if (exact != null) {
            exact.isAccessible = true
            return exact
        }

        // 2. Prefix match with all args (e.g. method takes [Activity], called with [Activity, Bundle])
        val prefix = candidates.firstOrNull { m ->
            m.parameterTypes.isNotEmpty() && m.parameterTypes.size <= args.size && m.parameterTypes.indices.all { i ->
                isAssignable(m.parameterTypes[i], args[i])
            }
        }
        if (prefix != null) {
            prefix.isAccessible = true
            return prefix
        }

        // 3. Match without thisObject (when caller prepended thisObject to argsList)
        if (args.size > 1) {
            val argsWithoutThis = args.drop(1).toTypedArray()
            val matchWithoutThis = candidates.firstOrNull { m ->
                m.parameterTypes.size == argsWithoutThis.size && m.parameterTypes.indices.all { i ->
                    isAssignable(m.parameterTypes[i], argsWithoutThis[i])
                }
            }
            if (matchWithoutThis != null) {
                matchWithoutThis.isAccessible = true
                return matchWithoutThis
            }

            val prefixWithoutThis = candidates.firstOrNull { m ->
                m.parameterTypes.isNotEmpty() && m.parameterTypes.size <= argsWithoutThis.size && m.parameterTypes.indices.all { i ->
                    isAssignable(m.parameterTypes[i], argsWithoutThis[i])
                }
            }
            if (prefixWithoutThis != null) {
                prefixWithoutThis.isAccessible = true
                return prefixWithoutThis
            }
        }

        // 4. Single-parameter match that matches any argument in args (e.g. takes Context or Activity)
        val singleParam = candidates.firstOrNull { m ->
            m.parameterTypes.size == 1 && args.any { arg -> isAssignable(m.parameterTypes[0], arg) }
        }
        if (singleParam != null) {
            singleParam.isAccessible = true
            return singleParam
        }

        // 5. Zero-arg fallback (e.g. initialize(), hide())
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

    fun initializeExtensionContext(
        context: Context,
        entrypoints: List<String> = emptyList(),
        resourceFallbacks: List<String> = emptyList()
    ) {
        val candidatePackages = mutableSetOf<String>()

        // 1. Dispatch context to any Utils/setContext entrypoints dynamically discovered from the bundle
        for (ep in entrypoints) {
            val parts = ep.split("#")
            if (parts.size == 2) {
                val className = parts[0].trim()
                val methodName = parts[1].trim()
                val pkg = className.substringBeforeLast(".")
                candidatePackages.add(pkg)

                if (methodName == "setContext" || className.endsWith(".Utils")) {
                    runCatching {
                        val res = callStatic(className, "setContext", context)
                        if (res != null || loadClass(className) != null) {
                            Log.i(TAG, "Initialized extension context via $className#setContext")
                        }
                    }
                }
            }
        }

        // 2. Initialize ResourceUtils.fallbackPackageName dynamically for layout/drawable lookups
        val targets = mutableSetOf<String>()
        targets.addAll(resourceFallbacks)
        for (pkg in candidatePackages) {
            targets.add("$pkg.ResourceUtils")
            if (pkg.contains(".extension.")) {
                val sharedPkg = pkg.substringBefore(".extension.") + ".extension.shared"
                targets.add("$sharedPkg.ResourceUtils")
            }
        }

        for (target in targets) {
            val clazz = loadClass(target) ?: continue
            runCatching {
                val setter = runCatching { clazz.getDeclaredMethod("setFallbackPackageName", String::class.java) }.getOrNull()
                if (setter != null) {
                    setter.isAccessible = true
                    setter.invoke(null, context.packageName)
                    Log.i(TAG, "Initialized $target.fallbackPackageName via setter to ${context.packageName}")
                } else {
                    val field = clazz.getDeclaredField("fallbackPackageName")
                    field.isAccessible = true
                    field.set(null, context.packageName)
                    Log.i(TAG, "Initialized $target.fallbackPackageName via field to ${context.packageName}")
                }
            }.onFailure {
                Log.w(TAG, "Could not set fallbackPackageName on $target: ${it.message}")
            }
        }
    }
}
