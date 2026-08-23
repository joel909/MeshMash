package com.example.meshmash.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.meshmash.BleMeshNode
import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID

/** Durable duplicate-suppressing store-and-forward coordinator for the BLE transport. */
class MeshStoreForwardManager(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onNewIssue: (MeshRequest) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val store = MeshRequestStore(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val forwardingQueue = ArrayDeque<UUID>()
    private val queuedIds = mutableSetOf<UUID>()
    private var sendingRequestId: UUID? = null
    private var started = false

    private val forwardingTick = object : Runnable {
        override fun run() {
            store.getRequestsDueForForwarding(limit = 25).forEach(::enqueue)
            mainHandler.postDelayed(this, FORWARDING_TICK_MILLIS)
        }
    }

    private val node = BleMeshNode(
        context = context,
        onStatus = onStatus,
        onMessage = ::receiveWirePacket,
        onSendFinished = ::onSendFinished,
    )

    val isBluetoothEnabled: Boolean
        get() = node.isBluetoothEnabled

    fun start() {
        if (started && node.isListening) return
        mainHandler.removeCallbacks(forwardingTick)
        if (!node.listen()) {
            started = false
            return
        }
        started = true
        mainHandler.post(forwardingTick)
    }

    fun createAndBroadcast(
        category: MeshReportCategory,
        details: String,
        priority: RequestPriority,
        requester: RequesterIdentity? = null,
        location: MeshLocation? = null,
    ): MeshRequest {
        val request = store.createAndStore(
            category = category.wireValue,
            payload = details.trim().toByteArray(Charsets.UTF_8),
            requester = requester,
            location = location,
            priority = priority,
        )
        sendImmediately(request)
        MeshUploadScheduler.enqueue(appContext)
        return request
    }

    /** Gives an explicit user tap priority over automatic retries already in progress. */
    private fun sendImmediately(request: MeshRequest) {
        val interruptedRequestId = sendingRequestId
        if (interruptedRequestId != null) {
            sendingRequestId = null
            node.cancelCurrentSend()
            enqueueId(interruptedRequestId, atFront = false)
        }
        enqueue(request, atFront = true)
    }

    private fun receiveWirePacket(value: String) {
        val request = runCatching { MeshBlePacketCodec.decode(value) }
            .getOrElse {
                onStatus("Ignored an invalid mesh packet")
                return
            }
        val receivedCopy = request.copy(
            receivedAtMillis = System.currentTimeMillis(),
            lastForwardedAtMillis = null,
        )
        if (!store.store(receivedCopy)) return
        onNewIssue(receivedCopy)
        enqueue(receivedCopy)
        MeshUploadScheduler.enqueue(appContext)
    }

    private fun enqueue(request: MeshRequest, atFront: Boolean = false) {
        if (request.status != RequestStatus.ACTIVE) return
        enqueueId(request.requestId, atFront)
        sendNext()
    }

    private fun enqueueId(requestId: UUID, atFront: Boolean) {
        if (requestId == sendingRequestId || !queuedIds.add(requestId)) return
        if (atFront) forwardingQueue.addFirst(requestId) else forwardingQueue.addLast(requestId)
    }

    private fun sendNext() {
        if (sendingRequestId != null) return
        while (forwardingQueue.isNotEmpty()) {
            val requestId = forwardingQueue.removeFirst()
            queuedIds.remove(requestId)
            val request = store.get(requestId) ?: continue
            sendingRequestId = requestId
            val encoded = runCatching { MeshBlePacketCodec.encode(request) }
            val wirePacket = encoded.getOrNull()
            if (wirePacket == null) {
                onStatus(encoded.exceptionOrNull()?.message ?: "Request is too large for BLE")
                sendingRequestId = null
                continue
            }
            if (node.send(wirePacket)) return
            sendingRequestId = null
        }
    }

    private fun onSendFinished(deliveredToAnyDevice: Boolean) {
        val requestId = sendingRequestId
        sendingRequestId = null
        if (deliveredToAnyDevice && requestId != null) store.markForwarded(requestId)
        sendNext()
    }

    override fun close() {
        started = false
        mainHandler.removeCallbacks(forwardingTick)
        node.stop()
        store.close()
    }

    companion object {
        // Check frequently enough to support the first three minutes' five-second burst schedule.
        private const val FORWARDING_TICK_MILLIS = 1_000L
    }
}
