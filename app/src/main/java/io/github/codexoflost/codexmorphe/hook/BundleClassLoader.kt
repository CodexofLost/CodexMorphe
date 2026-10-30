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
        return InMemoryDexClassLoader(dexBuffers.toTypedArray(), parent)
    }
}
