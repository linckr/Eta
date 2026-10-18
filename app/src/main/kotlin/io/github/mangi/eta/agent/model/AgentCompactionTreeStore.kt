package io.github.mangi.eta.agent.model

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

internal interface AgentCompactionTreeStore {
    fun read(key: String): JSONObject?
    fun save(key: String, state: JSONObject)
    fun clear(key: String)
    object None : AgentCompactionTreeStore {
        override fun read(key: String): JSONObject? = null
        override fun save(key: String, state: JSONObject) {}
        override fun clear(key: String) {}
    }
}

internal class FileAgentCompactionTreeStore(private val directory: File) : AgentCompactionTreeStore {
    private companion object { val lock = Any() }
    private fun target(key: String): File { require(key.matches(Regex("[a-f0-9]{64}"))); return File(directory, "$key.json") }
    override fun read(key: String): JSONObject? = synchronized(lock) {
        val file = target(key)
        if (!file.isFile) return@synchronized null
        require(file.length() <= 1024 * 1024) { "Invalid tree checkpoint size" }
        if (System.currentTimeMillis() - file.lastModified() > 7L * 86400_000) return@synchronized null
        JSONObject(file.readText(Charsets.UTF_8))
    }
    override fun save(key: String, state: JSONObject): Unit = synchronized(lock) {
        require(directory.isDirectory || directory.mkdirs())
        directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false)
        directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true)
        val bytes = state.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024)
        val temporary = File(directory, ".${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use {
                temporary.setReadable(false, false); temporary.setWritable(false, false)
                temporary.setReadable(true, true); temporary.setWritable(true, true)
                it.write(bytes); it.fd.sync()
            }
            Files.move(temporary.toPath(), target(key).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
                ?.sortedByDescending { it.lastModified() }?.drop(16)?.forEach { it.delete() }
        } finally { temporary.delete() }
        Unit
    }
    override fun clear(key: String) { synchronized(lock) { target(key).delete() } }
}
