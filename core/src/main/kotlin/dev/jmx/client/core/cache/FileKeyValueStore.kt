package dev.jmx.client.core.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

class FileKeyValueStore(
    private val file: Path
) : KeyValueStore {
    private val lock = Any()

    override fun getString(key: String): String? = synchronized(lock) {
        load()[key] as? String
    }

    override fun putString(key: String, value: String?) {
        synchronized(lock) {
            val properties = load()
            if (value == null) {
                properties.remove(key)
            } else {
                properties[key] = value
            }
            save(properties)
        }
    }

    private fun load(): Properties {
        val properties = Properties()
        if (!Files.exists(file)) return properties
        Files.newInputStream(file).use { input ->
            properties.load(input)
        }
        return properties
    }

    private fun save(properties: Properties) {
        file.parent?.let { Files.createDirectories(it) }
        val temp = file.parent?.let { parent ->
            Files.createTempFile(parent, file.fileName.toString(), ".tmp")
        } ?: Files.createTempFile(file.fileName.toString(), ".tmp")
        try {
            Files.newOutputStream(temp).use { output ->
                properties.store(output, null)
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (atomicMoveFailure: UnsupportedOperationException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            Files.deleteIfExists(temp)
            throw failure
        }
    }
}
