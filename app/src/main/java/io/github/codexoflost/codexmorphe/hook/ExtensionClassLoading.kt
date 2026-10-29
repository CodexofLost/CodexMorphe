@file:Suppress("DEPRECATION")

package io.github.codexoflost.codexmorphe.hook

import android.app.Fragment
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.InflateException
import android.view.LayoutInflater
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

/**
 * Intercepts framework inflation entry points to resolve Morphe extension views and drawables
 * from the dynamically loaded bundle ClassLoader.
 */
object ExtensionClassLoading {

    private val viewConstructors = ConcurrentHashMap<String, Constructor<out View>>()

    fun hook(bundleLoader: ClassLoader) {
        val hasViewContext = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val createViewMethod = if (hasViewContext) {
            LayoutInflater::class.java.getDeclaredMethod(
                "createView",
                Context::class.java,
                String::class.java,
                String::class.java,
                AttributeSet::class.java
            )
        } else {
            LayoutInflater::class.java.getDeclaredMethod(
                "createView",
                String::class.java,
                String::class.java,
                AttributeSet::class.java
            )
        }

        createViewMethod.hookMethod {
            before { param ->
                val offset = if (hasViewContext) 1 else 0
                val prefix = param.args[offset + 1] as String?
                val name = prefix.orEmpty() + (param.args[offset] as String)
                if (!name.startsWith("app.morphe.extension.")) return@before

                val inflater = param.thisObject as LayoutInflater
                val context = if (offset == 1) {
                    param.args[0] as Context
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (XposedHelpers.getObjectField(inflater, "mConstructorArgs") as? Array<Any?>)?.get(0) as? Context
                        ?: inflater.context
                }
                val attrs = param.args[offset + 2] as AttributeSet?

                try {
                    val constructor = viewConstructors.getOrPut(name) {
                        bundleLoader.loadClass(name).asSubclass(View::class.java)
                            .getConstructor(Context::class.java, AttributeSet::class.java)
                    }
                    if (inflater.filter?.onLoadClass(constructor.declaringClass) == false) {
                        throw InflateException("Class not allowed to be inflated: $name")
                    }
                    param.result = constructor.newInstance(context, attrs)
                } catch (e: Throwable) {
                    param.throwable = InflateException("Error inflating extension class $name", e)
                }
            }
        }

        // Intercept custom drawable inflation
        runCatching {
            val drawableInflaterClass = XposedHelpers.findClass("android.graphics.drawable.DrawableInflater", null)
            val inflateFromClassMethod = XposedHelpers.findMethodExact(
                drawableInflaterClass,
                "inflateFromClass",
                String::class.java
            )
            inflateFromClassMethod.hookMethod {
                before { param ->
                    val name = param.args[0] as String
                    if (!name.startsWith("app.morphe.extension.")) return@before
                    try {
                        val drawableClass = bundleLoader.loadClass(name).asSubclass(Drawable::class.java)
                        param.result = drawableClass.getConstructor().newInstance()
                    } catch (e: Throwable) {
                        param.throwable = InflateException("Error inflating extension drawable $name", e)
                    }
                }
            }
        }

        // Intercept legacy fragment instantiation
        runCatching {
            val instantiateMethod = Fragment::class.java.getDeclaredMethod(
                "instantiate",
                Context::class.java,
                String::class.java,
                Bundle::class.java
            )
            instantiateMethod.hookMethod {
                before { param ->
                    val name = param.args[1] as String
                    if (!name.startsWith("app.morphe.extension.")) return@before
                    try {
                        val fragment = bundleLoader.loadClass(name).asSubclass(Fragment::class.java)
                            .getConstructor().newInstance()
                        (param.args[2] as? Bundle)?.let { args ->
                            args.classLoader = fragment.javaClass.classLoader
                            fragment.arguments = args
                        }
                        param.result = fragment
                    } catch (e: Throwable) {
                        param.throwable = Fragment.InstantiationException("Unable to instantiate fragment $name", e)
                    }
                }
            }
        }
    }
}
