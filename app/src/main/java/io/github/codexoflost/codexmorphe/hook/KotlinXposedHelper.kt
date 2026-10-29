package io.github.codexoflost.codexmorphe.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member

/**
 * Idiomatic Kotlin DSL extensions for modern LibXposed API 102.
 */
class HookDsl(val chain: XposedInterface.Chain) {
    val thisObject: Any?
        inline get() = chain.thisObject

    val args: Array<Any?>
        inline get() = chain.args
}

/**
 * Hooks a method/constructor before execution.
 */
fun XposedInterface.hookBefore(
    member: Member,
    block: (HookDsl) -> Unit
): XposedInterface.HookHandle {
    return hook(member).intercept { chain ->
        block(HookDsl(chain))
        chain.proceed()
    }
}

/**
 * Hooks a method/constructor after execution, allowing result inspection or transformation.
 */
fun XposedInterface.hookAfter(
    member: Member,
    block: (HookDsl, Any?) -> Any?
): XposedInterface.HookHandle {
    return hook(member).intercept { chain ->
        val result = chain.proceed()
        block(HookDsl(chain), result)
    }
}

/**
 * Completely replaces a method/constructor with custom logic without calling original.
 */
fun XposedInterface.hookReplace(
    member: Member,
    block: (HookDsl) -> Any?
): XposedInterface.HookHandle {
    return hook(member).intercept { chain ->
        block(HookDsl(chain))
    }
}
