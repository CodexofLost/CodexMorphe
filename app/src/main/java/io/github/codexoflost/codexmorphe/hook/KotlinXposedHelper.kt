package io.github.codexoflost.codexmorphe.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member

class HookDsl {
    private var beforeBlock: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    private var afterBlock: ((XC_MethodHook.MethodHookParam) -> Unit)? = null

    fun before(block: (XC_MethodHook.MethodHookParam) -> Unit) {
        beforeBlock = block
    }

    fun after(block: (XC_MethodHook.MethodHookParam) -> Unit) {
        afterBlock = block
    }

    fun toCallback(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                beforeBlock?.invoke(param)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                afterBlock?.invoke(param)
            }
        }
    }
}

fun Member.hookMethod(block: HookDsl.() -> Unit): XC_MethodHook.Unhook {
    val dsl = HookDsl().apply(block)
    return XposedBridge.hookMethod(this, dsl.toCallback())
}

fun Member.hookBefore(block: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook.Unhook {
    return hookMethod { before(block) }
}

fun Member.hookAfter(block: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook.Unhook {
    return hookMethod { after(block) }
}

fun Member.hookReplace(block: (XC_MethodHook.MethodHookParam) -> Any?): XC_MethodHook.Unhook {
    return hookMethod {
        before { param ->
            param.result = block(param)
        }
    }
}
