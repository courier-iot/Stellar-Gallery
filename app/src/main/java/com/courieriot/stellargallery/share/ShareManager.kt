package com.courieriot.stellargallery.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.data.MediaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

data class Peer(val serviceName: String, val host: InetAddress, val port: Int) {
    val displayName: String get() = serviceName.removePrefix(SERVICE_PREFIX)
}

class IncomingRequest(
    val sender: String,
    val count: Int,
    val totalBytes: Long,
    val answer: CompletableDeferred<Boolean>,
)

data class TransferProgress(
    val label: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

private const val SERVICE_TYPE = "_stellargallery._tcp."
private const val SERVICE_PREFIX = "Stellar Gallery · "
private const val MAGIC = 0x53475632 // "SGV2" — per-file skip so interrupted transfers resume

/**
 * Phone-to-phone sharing on the same Wi-Fi. Uses Android's built-in network service discovery (mDNS)
 * and a direct socket — nothing leaves your local network.
 */
class ShareManager(context: Context, private val repo: MediaRepository) {
    private val receivedLog = File(context.filesDir, "received.log")
    private val receivedKeys: HashSet<String> by lazy {
        runCatching { receivedLog.readLines().toHashSet() }.getOrDefault(HashSet())
    }

    /** "sg://ip:port" while receiving — shown as a QR code for direct pairing. */
    val pairingCode = MutableStateFlow<String?>(null)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val deviceName: String = Build.MODEL.take(40)

    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val incoming = MutableStateFlow<IncomingRequest?>(null)
    val progress = MutableStateFlow<TransferProgress?>(null)
    val receiving = MutableStateFlow(false)
    val discovering = MutableStateFlow(false)
    val busy = MutableStateFlow(false)

    private var server: ServerSocket? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var myServiceName: String? = null
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (info in resolveQueue) {
                val r = resolve(info) ?: continue
                @Suppress("DEPRECATION")
                val host = r.host ?: continue
                val peer = Peer(r.serviceName, host, r.port)
                peers.update { list -> list.filterNot { it.serviceName == peer.serviceName } + peer }
            }
        }
    }

    // ---------------- Receiving ----------------

    fun startReceiving() {
        if (server != null) return
        acquireLock()
        val ss = try { ServerSocket(0) } catch (e: IOException) { return }
        server = ss
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_PREFIX + deviceName
            serviceType = SERVICE_TYPE
            port = ss.localPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { myServiceName = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        receiving.value = true
        pairingCode.value = localIpv4()?.let { "sg://${it.hostAddress}:${ss.localPort}" }
        scope.launch {
            while (isActive && !ss.isClosed) {
                val socket = try { ss.accept() } catch (e: IOException) { break }
                handleIncoming(socket)
            }
        }
    }

    fun stopReceiving() {
        regListener?.let { l -> runCatching { nsd.unregisterService(l) } }
        regListener = null
        myServiceName = null
        runCatching { server?.close() }
        server = null
        receiving.value = false
        pairingCode.value = null
        incoming.value?.answer?.complete(false)
        incoming.value = null
        releaseLockIfIdle()
    }

    private suspend fun handleIncoming(socket: Socket) {
        withContext(Dispatchers.IO) {
            socket.use { s ->
                try {
                    s.soTimeout = 90_000
                    val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
                    val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    if (input.readInt() != MAGIC) return@use
                    val sender = input.readUTF().take(60)
                    val count = input.readInt()
                    val total = input.readLong()
                    if (count !in 1..5000) return@use

                    val answer = CompletableDeferred<Boolean>()
                    incoming.value = IncomingRequest(sender, count, total, answer)
                    val accepted = withTimeoutOrNull(60_000) { answer.await() } ?: false
                    incoming.value = null
                    output.writeBoolean(accepted)
                    output.flush()
                    if (!accepted) return@use

                    busy.value = true
                    var done = 0L
                    var lastReport = 0L
                    for (idx in 0 until count) {
                        val name = sanitize(input.readUTF())
                        val mime = input.readUTF()
                        val size = input.readLong()
                        val fileKey = sender + "|" + input.readUTF()
                        val label = "Receiving ${idx + 1} of $count from $sender"
                        val already = synchronized(receivedKeys) { fileKey in receivedKeys }
                        output.writeBoolean(already) // true = skip, we already have it
                        output.flush()
                        if (already) {
                            done += size
                            progress.value = TransferProgress("$label (already received)", done, total)
                            continue
                        }
                        progress.value = TransferProgress(label, done, total)
                        val limited = LimitedInputStream(input, size) { n ->
                            done += n
                            if (done - lastReport > 256 * 1024) {
                                lastReport = done
                                progress.value = TransferProgress(label, done, total)
                            }
                        }
                        var saved = false
                        if (mime.startsWith("image/") || mime.startsWith("video/")) {
                            saved = runCatching { repo.insertStream(name, mime, MediaRepository.RECEIVED_PATH, limited) != null }
                                .getOrDefault(false)
                        }
                        limited.drain()
                        if (saved) synchronized(receivedKeys) {
                            receivedKeys += fileKey
                            runCatching { receivedLog.appendText(fileKey + "\n") }
                        }
                    }
                    output.writeBoolean(true)
                    output.flush()
                    progress.value = TransferProgress("Received $count from $sender", total, total, finished = true)
                } catch (e: Exception) {
                    progress.value = TransferProgress("Transfer failed", 0, 0, finished = true, error = e.message ?: "Connection lost")
                } finally {
                    incoming.value = null
                    busy.value = false
                }
            }
        }
    }

    // ---------------- Sending ----------------

    fun startDiscovery() {
        if (discListener != null) return
        acquireLock()
        peers.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { discovering.value = true }
            override fun onDiscoveryStopped(serviceType: String) { discovering.value = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering.value = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != myServiceName) resolveQueue.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                peers.update { list -> list.filterNot { it.serviceName == info.serviceName } }
            }
        }
        discListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopDiscovery() {
        discListener?.let { l -> runCatching { nsd.stopServiceDiscovery(l) } }
        discListener = null
        discovering.value = false
        releaseLockIfIdle()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(i)
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    fun send(peer: Peer, items: List<MediaItem>) {
        if (busy.value || items.isEmpty()) return
        busy.value = true
        scope.launch {
            try {
                val sizes = items.map { repo.fileSize(it.uri).takeIf { s -> s >= 0 } ?: it.size }
                val total = sizes.sum()
                Socket().use { s ->
                    progress.value = TransferProgress("Connecting to ${peer.displayName}…", 0, total)
                    s.connect(InetSocketAddress(peer.host, peer.port), 8_000)
                    s.soTimeout = 75_000
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                    val input = DataInputStream(s.getInputStream())
                    out.writeInt(MAGIC)
                    out.writeUTF(deviceName)
                    out.writeInt(items.size)
                    out.writeLong(total)
                    out.flush()

                    progress.value = TransferProgress("Waiting for ${peer.displayName} to accept…", 0, total)
                    if (!input.readBoolean()) {
                        progress.value = TransferProgress("${peer.displayName} declined", 0, total, finished = true, error = "Declined")
                        return@use
                    }

                    var done = 0L
                    var lastReport = 0L
                    val buf = ByteArray(128 * 1024)
                    items.forEachIndexed { idx, item ->
                        val size = sizes[idx]
                        val label = "Sending ${idx + 1} of ${items.size} to ${peer.displayName}"
                        out.writeUTF(item.name.take(200))
                        out.writeUTF(item.mimeType)
                        out.writeLong(size)
                        out.writeUTF("${item.id}:$size:${item.dateTaken}")
                        out.flush()
                        if (input.readBoolean()) {
                            done += size
                            progress.value = TransferProgress("$label (already on ${peer.displayName})", done, total)
                            return@forEachIndexed
                        }
                        var remaining = size
                        repo.openInput(item.uri)?.use { src ->
                            while (remaining > 0) {
                                val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                                done += n
                                if (done - lastReport > 256 * 1024) {
                                    lastReport = done
                                    progress.value = TransferProgress(label, done, total)
                                }
                            }
                        }
                        // Keep the stream aligned if a file came up short.
                        while (remaining > 0) {
                            val n = minOf(buf.size.toLong(), remaining).toInt()
                            buf.fill(0, 0, n)
                            out.write(buf, 0, n)
                            remaining -= n
                            done += n
                        }
                    }
                    out.flush()
                    input.readBoolean()
                    progress.value = TransferProgress("Sent ${items.size} to ${peer.displayName}", total, total, finished = true)
                }
            } catch (e: Exception) {
                progress.value = TransferProgress("Send failed", 0, 0, finished = true, error = e.message ?: "Connection lost")
            } finally {
                busy.value = false
            }
        }
    }

    fun clearProgress() { if (!busy.value) progress.value = null }

    fun stopAll() { stopReceiving(); stopDiscovery() }

    // ---------------- Helpers ----------------

    private fun acquireLock() {
        if (multicastLock == null) {
            multicastLock = wifi?.createMulticastLock("stellar-share")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
    }

    private fun releaseLockIfIdle() {
        if (server == null && discListener == null) {
            runCatching { multicastLock?.release() }
            multicastLock = null
        }
    }

    /** Turns a scanned "sg://ip:port" code into a peer. */
    fun peerFromCode(code: String): Peer? = runCatching {
        val rest = code.trim().removePrefix("sg://")
        val host = rest.substringBeforeLast(':')
        val port = rest.substringAfterLast(':').toInt()
        Peer(SERVICE_PREFIX + "Paired phone ($host)", InetAddress.getByName(host), port)
    }.getOrNull()

    private fun localIpv4(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").trim().take(120).ifBlank { "file" }
}

private class LimitedInputStream(
    private val src: InputStream,
    private var remaining: Long,
    private val onRead: (Int) -> Unit,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = src.read()
        if (b < 0) throw EOFException()
        remaining--
        onRead(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException()
        remaining -= n
        onRead(n)
        return n
    }

    fun drain() {
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) read(buf, 0, buf.size)
    }

    override fun close() { /* never close the socket stream */ }
}
