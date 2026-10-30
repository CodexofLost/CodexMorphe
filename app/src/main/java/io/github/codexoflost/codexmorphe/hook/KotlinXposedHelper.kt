package io.github.codexoflost.codexmorphe.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Invoker
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Idiomatic Kotlin DSL extensions for pure LibXposed API 102 (https://github.com/libxposed/api).
 */
class HookDsl(val chain: XposedInterface.Chain) {
    val thisObject: Any?
        inline get() = chain.thisObject

    val args: List<Any?>
        inline get() = chain.args

    val executable: Executable
        inline get() = chain.executable

    inline fun <reified T> arg(index: Int): T = chain.getArg(index) as T

    fun proceed(): Any? = chain.proceed()

    fun proceed(newArgs: Array<out Any?>): Any? = chain.proceed(newArgs as Array<Any>)

    fun proceedWith(newThis: Any): Any? = chain.proceedWith(newThis)

    fun proceedWith(newThis: Any, newArgs: Array<out Any?>): Any? = chain.proceedWith(newThis, newArgs as Array<Any>)
}

/**
 * Hooks an Executable with before-interception.
 */
fun XposedInterface.hookBefore(
    executable: Executable,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: ExceptionMode = ExceptionMode.DEFAULT,
    block: (HookDsl) -> Unit
): XposedInterface.HookHandle {
    return hook(executable)
        .setPriority(priority)
        .setExceptionMode(exceptionMode)
        .intercept { chain ->
            block(HookDsl(chain))
            chain.proceed()
        }
}

/**
 * Hooks an Executable with after-interception, allowing inspection or mutation of the return value.
 */
fun XposedInterface.hookAfter(
    executable: Executable,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: ExceptionMode = ExceptionMode.DEFAULT,
    block: (HookDsl, Any?) -> Any?
): XposedInterface.HookHandle {
    return hook(executable)
        .setPriority(priority)
        .setExceptionMode(exceptionMode)
        .intercept { chain ->
            val result = chain.proceed()
            block(HookDsl(chain), result)
        }
}

/**
 * Completely replaces an Executable with custom logic without calling original executable.
 */
fun XposedInterface.hookReplace(
    executable: Executable,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: ExceptionMode = ExceptionMode.DEFAULT,
    block: (HookDsl) -> Any?
): XposedInterface.HookHandle {
    return hook(executable)
        .setPriority(priority)
        .setExceptionMode(exceptionMode)
        .intercept { chain ->
            block(HookDsl(chain))
        }
}

/**
 * Invokes the original unhooked implementation bypassing all registered interceptors.
 */
fun XposedInterface.invokeOriginal(method: Method, thisObject: Any?, vararg args: Any?): Any? {
    return getInvoker(method).setType(Invoker.Type.ORIGIN).invoke(thisObject, *args)
}

/**
 * Invokes a constructor bypassing all registered interceptors.
 */
fun <T> XposedInterface.newInstanceOriginal(constructor: Constructor<T>, vararg args: Any?): T {
    return getInvoker(constructor).setType(Invoker.Type.ORIGIN).newInstance(*args)
}

/**
 * Standard reflection helpers replacing legacy XposedHelpers.
 */
object XpReflect {
    fun findClass(className: String, classLoader: ClassLoader? = null): Class<*> {
        val loader = classLoader ?: ClassLoader.getSystemClassLoader()
        return Class.forName(className, false, loader)
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader? = null): Class<*>? {
        return runCatching { findClass(className, classLoader) }.getOrNull()
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg paramTypes: Class<*>): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                val method = current!!.getDeclaredMethod(methodName, *paramTypes)
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${clazz.name}#$methodName(${paramTypes.joinToString { it.name }})")
    }

    fun findMethodExactIfExists(clazz: Class<*>, methodName: String, vararg paramTypes: Class<*>): Method? {
        return runCatching { findMethodExact(clazz, methodName, *paramTypes) }.getOrNull()
    }

    fun findConstructorExact(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*> {
        val ctor = clazz.getDeclaredConstructor(*paramTypes)
        ctor.isAccessible = true
        return ctor
    }

    fun findConstructorExactIfExists(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? {
        return runCatching { findConstructorExact(clazz, *paramTypes) }.getOrNull()
    }

    fun findField(clazz: Class<*>, fieldName: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                val field = current!!.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${clazz.name}#$fieldName")
    }

    fun findFieldIfExists(clazz: Class<*>, fieldName: String): Field? {
        return runCatching { findField(clazz, fieldName) }.getOrNull()
    }
}

// Fluent extensions on Any / Class for reflection
fun Any.callMethod(methodName: String, vararg args: Any?): Any? {
    val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
    val method = this.javaClass.declaredMethods.firstOrNull { m ->
        m.name == methodName && m.parameterTypes.size == args.size
    } ?: XpReflect.findMethodExact(this.javaClass, methodName, *paramTypes)
    method.isAccessible = true
    return method.invoke(this, *args)
}

fun Any.callMethodOrNull(methodName: String, vararg args: Any?): Any? =
    runCatching { callMethod(methodName, *args) }.getOrNull()

fun Class<*>.callStaticMethod(methodName: String, vararg args: Any?): Any? {
    val method = this.declaredMethods.firstOrNull { m ->
        Modifier.isStatic(m.modifiers) && m.name == methodName && m.parameterTypes.size == args.size
    } ?: throw NoSuchMethodException("${this.name}#$methodName")
    method.isAccessible = true
    return method.invoke(null, *args)
}

fun Class<*>.callStaticMethodOrNull(methodName: String, vararg args: Any?): Any? =
    runCatching { callStaticMethod(methodName, *args) }.getOrNull()

fun Any.getObjectField(fieldName: String): Any? {
    val field = XpReflect.findField(this.javaClass, fieldName)
    return field.get(this)
}

fun Any.getObjectFieldOrNull(fieldName: String): Any? =
    runCatching { getObjectField(fieldName) }.getOrNull()

fun Any.setObjectField(fieldName: String, value: Any?) {
    val field = XpReflect.findField(this.javaClass, fieldName)
    field.set(this, value)
}

fun Any.getBooleanFieldOrNull(fieldName: String): Boolean? =
    getObjectFieldOrNull(fieldName) as? Boolean

fun Any.getIntFieldOrNull(fieldName: String): Int? =
    getObjectFieldOrNull(fieldName) as? Int
