package com.example.orato.desktop

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories

/**
 * Aynı anda tek Bêje çalışmasını sağlar. İkinci kez başlatıldığında (ör. `beje --toggle`)
 * komutu çalışan örneğe yerel bir soket üzerinden iletir. Böylece Linux ve macOS'ta
 * sistemin klavye kısayolu ayarlarından `beje --toggle` komutuna bir tuş atanabilir.
 */
object AppInstanceGuard : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    @Volatile
    var lastErrorMessage: String? = null
        private set

    private val lockPath: Path get() = Platform.dataDir().resolve("beje.lock")
    private val portPath: Path get() = Platform.dataDir().resolve("beje.port")

    fun tryAcquire(): Boolean {
        if (lock?.isValid == true) return true
        return try {
            lockPath.parent?.createDirectories()
            val fileChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val fileLock = fileChannel.tryLock() ?: run {
                fileChannel.close()
                return false
            }
            channel = fileChannel
            lock = fileLock
            true
        } catch (exception: IOException) {
            lastErrorMessage = exception.localizedMessage ?: exception.javaClass.simpleName
            false
        }
    }

    /** Diğer örneklerden gelen komutları dinler (yalnızca 127.0.0.1, rastgele belirteçle). */
    fun startCommandServer(onCommand: (String) -> Unit) {
        runCatching {
            val socket = ServerSocket()
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            server = socket
            val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            writePrivate(portPath, "${socket.localPort} $token")
            thread(name = "beje-commands", isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: continue
                    client.use {
                        it.soTimeout = 2000
                        val line = runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull()
                        val parts = line?.trim()?.split(' ', limit = 2)
                        if (parts?.size == 2 && parts[0] == token) onCommand(parts[1])
                    }
                }
            }
        }.onFailure { StartupDiagnostics.logFailure("command.server", it) }
    }

    /** Çalışan örneğe komut gönderir; başarılıysa true. */
    fun sendToRunningInstance(command: String): Boolean = runCatching {
        val (port, token) = Files.readString(portPath).trim().split(' ', limit = 2)
        Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
            socket.getOutputStream().write("$token $command\n".toByteArray())
            socket.getOutputStream().flush()
        }
        true
    }.getOrDefault(false)

    override fun close() {
        runCatching { server?.close() }
        runCatching { Files.deleteIfExists(portPath) }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        server = null
        lock = null
        channel = null
    }

    private fun writePrivate(path: Path, content: String) {
        path.parent?.createDirectories()
        Files.deleteIfExists(path)
        if (!Platform.isWindows) {
            runCatching {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
        }
        Files.writeString(path, content)
    }
}
