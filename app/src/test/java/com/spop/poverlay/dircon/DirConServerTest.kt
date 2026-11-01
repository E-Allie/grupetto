package com.spop.poverlay.dircon

import android.content.Context
import android.net.nsd.NsdManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DirConServerTest {
    private val characteristic = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
    private lateinit var server: DirConServer
    private lateinit var clients: MutableSet<Any>
    private val disconnects = AtomicInteger()
    private val writes = mutableListOf<String>()
    private var onDisconnect: (String) -> Unit = {}
    private var onWrite: (String) -> Unit = {}

    @Before
    @Suppress("UNCHECKED_CAST")
    fun setUp() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>()
        val bridge = object : DirConGattBridge {
            override fun services() = emptyList<DirConService>()
            override fun readCharacteristic(uuid: UUID): ByteArray? = null
            override fun writeCharacteristic(uuid: UUID, value: ByteArray) = true
            override fun writeCharacteristic(client: String, uuid: UUID, value: ByteArray): Boolean {
                writes.add(client)
                onWrite(client)
                return true
            }
            override fun disconnected(client: String) {
                disconnects.incrementAndGet()
                onDisconnect(client)
            }
        }
        server = DirConServer(context, bridge, { "test" })
        clients = DirConServer::class.java.getDeclaredField("clients").apply {
            isAccessible = true
        }.get(server) as MutableSet<Any>
    }

    @After
    fun tearDown() {
        server.stop()
        server.coroutineContext.cancel()
        unmockkAll()
    }

    @Test
    fun `failed notification closes and releases owner immediately outside transport locks`() {
        val socket = TestSocket(failWrites = true)
        val session = session(socket)
        var disconnectedClient: String? = null
        onDisconnect = {
            disconnectedClient = it
            assertTrue(socket.isClosed)
            assertFalse(clients.contains(session))
            assertFalse("Bridge callback must not hold clients monitor", Thread.holdsLock(clients))
            assertFalse("Bridge callback must not hold socket monitor", Thread.holdsLock(socket))
            assertFalse("Bridge callback must not hold lifecycle monitor", Thread.holdsLock(lifecycleLock(session)))
        }

        server.notifyCharacteristicChanged(characteristic, byteArrayOf(1))

        assertEquals(id(session), disconnectedClient)
        assertEquals(1, disconnects.get())
        assertEquals(1, socket.closeCount.get())
        // Neither a delayed reader cleanup nor a later notification may revive the session.
        invoke(session, "close")
        invoke(session, "write", byteArrayOf(2))
        server.notifyCharacteristicChanged(characteristic, byteArrayOf(3))
        assertEquals(1, disconnects.get())
        assertEquals(1, socket.writeCount.get())
    }

    @Test
    fun `failed write unblocks pending read and reader cleanup disconnects only once`() {
        val readStarted = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                check(readReleased.await(5, TimeUnit.SECONDS)) { "Socket close did not unblock read" }
                return -1
            }
        }
        val socket = TestSocket(input, failWrites = true, onClose = { readReleased.countDown() })
        val session = session(socket)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit { invoke(session, "run") }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))

            server.notifyCharacteristicChanged(characteristic, byteArrayOf(1))
            reader.get(5, TimeUnit.SECONDS)

            assertTrue(clients.isEmpty())
            assertEquals(1, socket.closeCount.get())
            assertEquals(1, disconnects.get())
        } finally {
            readReleased.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stop releases every session outside client monitor and repeated cleanup is harmless`() {
        val firstSocket = TestSocket()
        val secondSocket = TestSocket()
        val first = session(firstSocket)
        val second = session(secondSocket)
        onDisconnect = {
            assertFalse(Thread.holdsLock(clients))
            assertFalse(Thread.holdsLock(firstSocket))
            assertFalse(Thread.holdsLock(secondSocket))
        }

        server.stop()
        server.stop()
        invoke(first, "close")
        invoke(second, "close")

        assertTrue(clients.isEmpty())
        assertEquals(2, disconnects.get())
        assertEquals(1, firstSocket.closeCount.get())
        assertEquals(1, secondSocket.closeCount.get())
    }

    @Test
    fun `failed subscriber does not prevent delivery to healthy subscribers or change targeting`() {
        session(TestSocket(failWrites = true))
        val healthySocket = TestSocket()
        val healthy = session(healthySocket)
        val otherSocket = TestSocket()
        session(otherSocket)

        server.notifyCharacteristicChanged(characteristic, byteArrayOf(1))
        assertEquals(1, disconnects.get())
        assertEquals(1, healthySocket.writeCount.get())
        assertEquals(1, otherSocket.writeCount.get())
        val expected = DirConMessage(
            identifier = DirConConstants.MessageUnsolicitedCharacteristicNotification,
            sequenceNumber = 0,
            uuid = characteristic,
            data = byteArrayOf(1)
        )
        assertArrayEquals(DirConCodec.encode(expected), healthySocket.bytes.toByteArray())

        server.notifyCharacteristicChanged(characteristic, byteArrayOf(2), id(healthy))
        assertEquals(2, healthySocket.writeCount.get())
        assertEquals(1, otherSocket.writeCount.get())
    }

    @Test
    fun `failed response prevents subsequent buffered control commands from reclaiming ownership`() {
        val command = DirConCodec.encode(
            DirConMessage(
                identifier = DirConConstants.MessageWriteCharacteristic,
                sequenceNumber = 1,
                uuid = characteristic,
                data = byteArrayOf(0)
            )
        )
        val socket = TestSocket(ByteArrayInputStream(command + command), failWrites = true)
        val session = session(socket)

        invoke(session, "run")

        assertEquals(listOf(id(session)), writes)
        assertEquals(1, disconnects.get())
        assertEquals(1, socket.closeCount.get())
        assertTrue(clients.isEmpty())
    }

    @Test
    fun `ordinary EOF removes session and releases owner once`() {
        val socket = TestSocket()
        val session = session(socket)

        invoke(session, "run")
        server.stop()

        assertEquals(1, disconnects.get())
        assertEquals(1, socket.closeCount.get())
        assertEquals(0, socket.writeCount.get())
        assertTrue(clients.isEmpty())
    }

    @Test
    fun `close during in flight control write releases ownership after that write finishes`() {
        val command = DirConCodec.encode(
            DirConMessage(
                identifier = DirConConstants.MessageWriteCharacteristic,
                sequenceNumber = 1,
                uuid = characteristic,
                data = byteArrayOf(0)
            )
        )
        val socket = TestSocket(ByteArrayInputStream(command + command))
        val session = session(socket)
        val handlerStarted = CountDownLatch(1)
        val finishHandler = CountDownLatch(1)
        var owner: String? = null
        onWrite = {
            handlerStarted.countDown()
            check(finishHandler.await(5, TimeUnit.SECONDS)) { "Control handler not released" }
            owner = it
        }
        onDisconnect = {
            assertEquals(id(session), owner)
            assertFalse(Thread.holdsLock(clients))
            assertFalse(Thread.holdsLock(socket))
            assertFalse(Thread.holdsLock(lifecycleLock(session)))
            owner = null
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit { invoke(session, "run") }
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))

            invoke(session, "close")
            assertTrue(socket.isClosed)
            assertTrue(clients.isEmpty())
            assertEquals(0, disconnects.get())
            finishHandler.countDown()
            reader.get(5, TimeUnit.SECONDS)

            assertNull(owner)
            assertEquals(1, disconnects.get())
            assertEquals(1, socket.closeCount.get())
            assertEquals(0, socket.writeCount.get())
            assertEquals(listOf(id(session)), writes)
        } finally {
            finishHandler.countDown()
            executor.shutdownNow()
        }
    }

    private fun session(socket: Socket): Any {
        val type = DirConServer::class.java.declaredClasses.single { it.simpleName == "ClientSession" }
        return type.getDeclaredConstructor(DirConServer::class.java, Socket::class.java).apply {
            isAccessible = true
        }.newInstance(server, socket).also {
            clients.add(it)
            invoke(it, "subscribe", characteristic)
        }
    }

    private fun id(session: Any) = invoke(session, "getId") as String

    private fun lifecycleLock(session: Any): Any = session.javaClass.getDeclaredField("lifecycleLock").apply {
        isAccessible = true
    }.get(session)

    private fun invoke(session: Any, name: String, vararg args: Any): Any? =
        session.javaClass.declaredMethods.single { it.name == name }.apply {
            isAccessible = true
        }.invoke(session, *args)

    private class TestSocket(
        private val input: InputStream = ByteArrayInputStream(byteArrayOf()),
        private val failWrites: Boolean = false,
        private val onClose: () -> Unit = {}
    ) : Socket() {
        private val closed = AtomicBoolean()
        val bytes = ByteArrayOutputStream()
        val closeCount = AtomicInteger()
        val writeCount = AtomicInteger()
        private val output = object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                writeCount.incrementAndGet()
                if (failWrites) throw IOException("Broken pipe")
                bytes.write(value, offset, length)
            }
        }

        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun isClosed() = closed.get()
        override fun close() {
            closeCount.incrementAndGet()
            closed.set(true)
            onClose()
        }
    }
}
