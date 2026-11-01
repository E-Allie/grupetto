package com.spop.poverlay.dircon

import android.content.Context
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class DirConServer(
    context: Context,
    private val bridge: DirConGattBridge,
    private val serialNumberProvider: () -> String,
    private val port: Int = DirConConstants.TcpPort
) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.IO

    private val mdnsAdvertiser = DirConMdnsAdvertiser(context.applicationContext)
    private val clients = Collections.synchronizedSet(mutableSetOf<ClientSession>())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob != null) return

        val serviceUuids = bridge.services()
            .map { it.uuid.toShortUuidString() }
            .toDirConMdnsServiceUuidList()
        mdnsAdvertiser.start(port, serviceUuids, serialNumberProvider())

        acceptJob = launch {
            runCatching {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    Timber.i("DIRCON TCP server listening on port $port")
                    while (isActive) {
                        val client = socket.accept()
                        ClientSession(client).also { session ->
                            clients.add(session)
                            launch { session.run() }
                        }
                    }
                }
            }.onFailure { error ->
                if (error !is SocketException || isActive) {
                    Timber.e(error, "DIRCON TCP server stopped unexpectedly")
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null

        val sessions = synchronized(clients) {
            val snapshot = clients.toList()
            clients.clear()
            snapshot
        }
        sessions.forEach { it.close() }
        mdnsAdvertiser.stop()
        coroutineContext.cancelChildren()
    }

    fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray, client: String? = null) {
        val message = DirConMessage(
            identifier = DirConConstants.MessageUnsolicitedCharacteristicNotification,
            sequenceNumber = 0,
            uuid = uuid,
            data = value
        )
        val encoded = DirConCodec.encode(message)

        // A failed write releases controller ownership through the bridge. Never invoke it
        // with the clients monitor held: the BLE side can call us while holding its own lock.
        val sessions = synchronized(clients) { clients.toList() }
        sessions
            .filter { it.isSubscribed(uuid) && (client == null || client == it.id) }
            .forEach { it.write(encoded) }
    }

    private fun handle(message: DirConMessage, session: ClientSession): DirConMessage {
        if (message.version != DirConConstants.ProtocolVersion) {
            return errorResponse(message, DirConConstants.ResponseUnknownProtocol)
        }

        return when (message.identifier) {
            DirConConstants.MessageDiscoverServices -> {
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuids = bridge.services().map { it.uuid }
                )
            }
            DirConConstants.MessageDiscoverCharacteristics -> {
                val serviceUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseServiceNotFound)
                val service = bridge.services().firstOrNull { it.uuid == serviceUuid }
                    ?: return errorResponse(message, DirConConstants.ResponseServiceNotFound)
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = service.uuid,
                    uuids = service.characteristics.map { it.uuid },
                    properties = service.characteristics.map { it.properties }
                )
            }
            DirConConstants.MessageReadCharacteristic -> {
                val characteristicUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                val value = bridge.readCharacteristic(characteristicUuid)
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = characteristicUuid,
                    data = value
                )
            }
            DirConConstants.MessageWriteCharacteristic -> {
                val characteristicUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                if (!bridge.writeCharacteristic(session.id, characteristicUuid, message.data)) {
                    return errorResponse(message, DirConConstants.ResponseCharacteristicWriteFailed)
                }
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = characteristicUuid
                )
            }
            DirConConstants.MessageEnableCharacteristicNotifications -> {
                val characteristicUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                val exists = bridge.services().any { service ->
                    service.characteristics.any { it.uuid == characteristicUuid }
                }
                if (!exists) {
                    return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                }

                val enabled = message.data.firstOrNull()?.toInt()?.and(0xFF) != 0
                if (enabled) session.subscribe(characteristicUuid) else session.unsubscribe(characteristicUuid)
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = characteristicUuid
                )
            }
            else -> errorResponse(message, DirConConstants.ResponseUnknownMessageType)
        }
    }

    private fun errorResponse(message: DirConMessage, code: Int): DirConMessage =
        DirConMessage(
            identifier = message.identifier,
            sequenceNumber = message.sequenceNumber,
            responseCode = code
        )

    private inner class ClientSession(private val socket: Socket) {
        val id = "dircon:${UUID.randomUUID()}"
        private val subscriptions = Collections.synchronizedSet(mutableSetOf<UUID>())
        private val closed = AtomicBoolean(false)
        private val lifecycleLock = Any()
        private var activeRequests = 0
        private var transportClosed = false
        private var disconnected = false

        fun run() {
            Timber.i("DIRCON client connected from ${socket.remoteSocketAddress}")
            try {
                val input = socket.getInputStream()
                val readBuffer = ByteArray(512)
                var pending = ByteArray(0)

                while (isActive && !closed.get() && !socket.isClosed) {
                    val read = input.read(readBuffer)
                    if (read < 0) throw EOFException()
                    pending += readBuffer.copyOf(read)

                    var offset = 0
                    while (offset < pending.size && !closed.get()) {
                        val decoded = runCatching {
                            DirConCodec.decode(pending, offset, pending.size - offset)
                        }.getOrElse { error ->
                            Timber.w(error, "Invalid DIRCON frame")
                            null
                        } ?: break

                        val response = handleWhileOpen(decoded.message) ?: break
                        write(DirConCodec.encode(response))
                        offset += decoded.bytesConsumed
                    }
                    pending = pending.copyOfRange(offset, pending.size)
                }
            } catch (_: EOFException) {
                Timber.i("DIRCON client disconnected")
            } catch (_: SocketException) {
                Timber.i("DIRCON client socket closed")
            } catch (error: Exception) {
                Timber.e(error, "DIRCON client failed")
            } finally {
                close()
            }
        }

        fun subscribe(uuid: UUID) {
            subscriptions.add(uuid)
        }

        fun unsubscribe(uuid: UUID) {
            subscriptions.remove(uuid)
        }

        fun isSubscribed(uuid: UUID): Boolean = subscriptions.contains(uuid)

        private fun handleWhileOpen(message: DirConMessage): DirConMessage? {
            synchronized(lifecycleLock) {
                if (closed.get()) return null
                activeRequests++
            }
            try {
                return handle(message, this)
            } finally {
                synchronized(lifecycleLock) { activeRequests-- }
                disconnectWhenIdle()
            }
        }

        fun write(bytes: ByteArray) {
            runCatching {
                synchronized(socket) {
                    if (closed.get()) return
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }.onFailure {
                Timber.d(it, "Failed to write DIRCON frame")
                // Close outside the write monitor and unblock any pending read. Idle ownership
                // is released now rather than waiting for the reader coroutine to finish.
                close()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            clients.remove(this)
            synchronized(lifecycleLock) { transportClosed = true }
            disconnectWhenIdle()
        }

        private fun disconnectWhenIdle() {
            // An in-flight control write must finish before releasing its ownership; otherwise
            // it could grant control again after the disconnect. The callback runs without locks.
            val notify = synchronized(lifecycleLock) {
                if (!transportClosed || activeRequests != 0 || disconnected) {
                    false
                } else {
                    disconnected = true
                    true
                }
            }
            if (notify) bridge.disconnected(id)
        }
    }
}

private fun UUID.toShortUuidString(): String {
    val text = toString()
    return if (text.endsWith("-0000-1000-8000-00805f9b34fb", ignoreCase = true)) {
        text.substring(4, 8).lowercase()
    } else {
        text.lowercase()
    }
}

internal fun List<String>.toDirConMdnsServiceUuidList(): List<String> {
    val available = map { it.lowercase() }.toSet()
    val preferred = listOf(
        "1816", // Cycling Speed and Cadence
        "1818", // Cycling Power Service
        "180d", // Heart Rate
        "1826"  // Fitness Machine Service
    )
    val hasTrainerService = available.any { it in setOf("1816", "1818", "1826") }
    return if (hasTrainerService) preferred else emptyList()
}
