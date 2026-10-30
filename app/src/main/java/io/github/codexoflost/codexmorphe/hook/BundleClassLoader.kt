package io.github.codexoflost.codexmorphe.hook

import android.os.ParcelFileDescriptor
import android.util.Log
import dalvik.system.InMemoryDexClassLoader
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Loads MultiDex bytecode from a bundle APK directly into memory.
 */
object BundleClassLoader {

    private const val TAG = "CodexMorphe/DexLoader"

    /**
     * Parent ClassLoader wrapper that forces certain shadowed runtime library classes
     * (like ExtensionRegistryLite) to be resolved from the bundle's DEX containers
     * instead of the host app's stripped or incompatible stubs.
     */
    private class BundleParentClassLoader(
        private val hostLoader: ClassLoader
    ) : ClassLoader(hostLoader) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            // If the host app (e.g. YouTube) has stripped stubs for ExtensionRegistry,
            // force the InMemoryDexClassLoader to look in its own DEX buffers where the full
            // implementation lives.
            if (name.startsWith("com.google.protobuf.ExtensionRegistry")) {
                throw ClassNotFoundException(name)
            }
            return hostLoader.loadClass(name)
        }
    }

    fun create(pfd: ParcelFileDescriptor, parent: ClassLoader): ClassLoader {
        val dexBuffers = mutableListOf<ByteBuffer>()

        runCatching {
            android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
        }
        ZipInputStream(FileInputStream(pfd.fileDescriptor)).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val current = entry ?: break
                if (current.name.endsWith(".dex")) {
                    val bytes = zis.readBytes()
                    val buffer = ByteBuffer.allocateDirect(bytes.size)
                    buffer.put(bytes)
                    buffer.flip()
                    dexBuffers.add(buffer)
                    Log.d(TAG, "Loaded DEX entry: ${current.name} (${bytes.size} bytes)")
                }
            }
        }

        if (dexBuffers.isEmpty()) {
            throw IllegalStateException("No DEX bytecode containers found in bundle APK")
        }

        Log.d(TAG, "Initializing InMemoryDexClassLoader with ${dexBuffers.size} DEX containers")
        val parentWrapper = BundleParentClassLoader(parent)
        return InMemoryDexClassLoader(dexBuffers.toTypedArray(), parentWrapper)
    }
}
