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
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

/**
 * Intercepts framework inflation entry points using LibXposed API 102 to resolve
 * Morphe extension views and drawables from the dynamically loaded bundle ClassLoader.
 */
object ExtensionClassLoading {

    private val viewConstructors = ConcurrentHashMap<String, Constructor<out View>>()

    fun hook(module: XposedInterface, bundleLoader: ClassLoader) {
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

        module.hook(createViewMethod).intercept { chain ->
            val offset = if (hasViewContext) 1 else 0
            val prefix = chain.args[offset + 1] as String?
            val name = prefix.orEmpty() + (chain.args[offset] as String)
            if (!name.startsWith("app.morphe.extension.")) {
                return@intercept chain.proceed()
            }

            val inflater = chain.thisObject as LayoutInflater
            val context = if (offset == 1) {
                chain.args[0] as Context
            } else {
                runCatching {
                    val field = LayoutInflater::class.java.getDeclaredField("mConstructorArgs").apply {
                        isAccessible = true
                    }
                    (field.get(inflater) as? Array<*>)?.get(0) as? Context
                }.getOrNull() ?: inflater.context
            }
            val attrs = chain.args[offset + 2] as AttributeSet?

            try {
                val constructor = viewConstructors.getOrPut(name) {
                    bundleLoader.loadClass(name).asSubclass(View::class.java)
                        .getConstructor(Context::class.java, AttributeSet::class.java)
                }
                if (inflater.filter?.onLoadClass(constructor.declaringClass) == false) {
                    throw InflateException("Class not allowed to be inflated: $name")
                }
                constructor.newInstance(context, attrs)
            } catch (e: Throwable) {
                throw InflateException("Error inflating extension class $name", e)
            }
        }

        // Intercept custom drawable inflation
        runCatching {
            val drawableInflaterClass = Class.forName("android.graphics.drawable.DrawableInflater")
            val inflateFromClassMethod = drawableInflaterClass.getDeclaredMethod(
                "inflateFromClass",
                String::class.java
            )
            module.hook(inflateFromClassMethod).intercept { chain ->
                val name = chain.args[0] as String
                if (!name.startsWith("app.morphe.extension.")) {
                    return@intercept chain.proceed()
                }
                try {
                    val drawableClass = bundleLoader.loadClass(name).asSubclass(Drawable::class.java)
                    drawableClass.getConstructor().newInstance()
                } catch (e: Throwable) {
                    throw InflateException("Error inflating extension drawable $name", e)
                }
            }
        }

        // Intercept legacy fragment instantiation
        runCatching {
            val fragmentClass = Class.forName("android.app.Fragment")
            val instantiateMethod = fragmentClass.getDeclaredMethod(
                "instantiate",
                Context::class.java,
                String::class.java,
                Bundle::class.java
            )
            module.hook(instantiateMethod).intercept { chain ->
                val className = chain.args[1] as String
                if (!className.startsWith("app.morphe.extension.")) {
                    return@intercept chain.proceed()
                }
                val args = chain.args[2] as? Bundle
                try {
                    val clazz = bundleLoader.loadClass(className).asSubclass(Fragment::class.java)
                    val fragment = clazz.getConstructor().newInstance()
                    if (args != null) {
                        args.classLoader = clazz.classLoader
                        fragment.arguments = args
                    }
                    fragment
                } catch (e: Throwable) {
                    throw Fragment.InstantiationException("Unable to instantiate fragment $className", e)
                }
            }
        }
    }
}
