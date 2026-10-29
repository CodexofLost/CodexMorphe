package io.github.codexoflost.codexmorphe.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Idiomatic Kotlin DSL extensions for modern LibXposed API 102.
 */
class HookDsl(val chain: XposedInterface.Chain) {
    val thisObject: Any?
        inline get() = chain.thisObject

    val args: List<Any?>
        inline get() = chain.args

    inline fun <reified T> arg(index: Int): T = chain.getArg(index) as T
}

/**
 * Hooks an Executable (Method or Constructor) before execution.
 */
fun XposedInterface.hookBefore(
    executable: Executable,
    block: (HookDsl) -> Unit
): XposedInterface.HookHandle {
    return hook(executable).intercept { chain ->
        block(HookDsl(chain))
        chain.proceed()
    }
}

/**
 * Hooks an Executable (Method or Constructor) after execution, allowing result inspection or transformation.
 */
fun XposedInterface.hookAfter(
    executable: Executable,
    block: (HookDsl, Any?) -> Any?
): XposedInterface.HookHandle {
    return hook(executable).intercept { chain ->
        val result = chain.proceed()
        block(HookDsl(chain), result)
    }
}

/**
 * Completely replaces an Executable (Method or Constructor) with custom logic without calling original.
 */
fun XposedInterface.hookReplace(
    executable: Executable,
    block: (HookDsl) -> Any?
): XposedInterface.HookHandle {
    return hook(executable).intercept { chain ->
        block(HookDsl(chain))
    }
}
