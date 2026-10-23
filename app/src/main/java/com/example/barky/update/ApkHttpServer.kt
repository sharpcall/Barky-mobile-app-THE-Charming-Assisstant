package com.example.barky.update

import android.content.Context
import android.util.Log
import com.example.barky.LanApkUpdater
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tiny HTTP server that serves this install’s APK (or a staged update file)
 * so peers can download over LAN or the internet (if NAT allows).
 * Port [LanApkUpdater.PORT] (8765) — published to Firebase as updateHttpPort.
 */
class ApkHttpServer(private val appContext: Context) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile var listenPort: Int = 0
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "barky-apk-http", isDaemon = true) {
            try {
                val ss = ServerSocket(LanApkUpdater.PORT)
                ss.reuseAddress = true
                server = ss
                listenPort = ss.localPort
                Log.i(TAG, "serving APK on :$listenPort path=${apkFile()?.absolutePath}")
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        thread(name = "barky-apk-client", isDaemon = true) {
                            handle(sock)
                        }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "start failed", t)
                running.set(false)
                listenPort = 0
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        listenPort = 0
        Log.i(TAG, "stopped")
    }

    fun isRunning(): Boolean = running.get() && listenPort > 0

    private fun apkFile(): File? {
        // Prefer staged update in cache; else this package’s base APK
        val staged = File(appContext.cacheDir, "updates/barky-serve.apk")
        if (staged.isFile && staged.length() > 50_000L) return staged
        return try {
            File(appContext.applicationInfo.sourceDir)
        } catch (_: Throwable) {
            null
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val input = sock.getInputStream().bufferedReader()
            val reqLine = input.readLine() ?: return
            // Drain headers
            while (true) {
                val h = input.readLine() ?: break
                if (h.isBlank()) break
            }
            val path = reqLine.split(" ").getOrNull(1) ?: "/"
            val method = reqLine.split(" ").getOrNull(0) ?: "GET"
            val file = apkFile()
            val out = BufferedOutputStream(sock.getOutputStream())
            if (file == null || !file.isFile) {
                writeResponse(out, 404, "text/plain", "no apk\n".toByteArray())
                return
            }
            val okPath = path.contains("barky") || path.endsWith(".apk") || path == "/" ||
                path.startsWith("/app")
            if (!okPath) {
                writeResponse(out, 404, "text/plain", "not found\n".toByteArray())
                return
            }
            if (method.equals("HEAD", ignoreCase = true)) {
                val head =
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.android.package-archive\r\n" +
                        "Content-Length: ${file.length()}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }
            val head =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.android.package-archive\r\n" +
                    "Content-Length: ${file.length()}\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            FileInputStream(file).use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
            Log.i(TAG, "served ${file.length()} B to ${sock.inetAddress?.hostAddress}")
        } catch (t: Throwable) {
            Log.w(TAG, "client", t)
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeResponse(out: BufferedOutputStream, code: Int, type: String, body: ByteArray) {
        val reason = if (code == 200) "OK" else "Error"
        val head =
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "ApkHttpServer"
    }
}
