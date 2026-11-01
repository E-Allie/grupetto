package com.spop.poverlay.control

import java.nio.ByteBuffer
import java.util.UUID

/** Optional Grupetto characteristics inside FTMS; existing clients ignore them. */
object TrainerProtocol {
    val STATUS_UUID: UUID = UUID.fromString("d7b74e01-42ce-4e68-9db0-80cae6f10a01")
    val RECOVER_UUID: UUID = UUID.fromString("d7b74e02-42ce-4e68-9db0-80cae6f10a01")
    const val VERSION = 1
    const val POWER_BUCKETS = 1
    fun encode(state: ControlState): ByteArray = ByteBuffer.allocate(12)
        .put('G'.code.toByte()).put('R'.code.toByte()).put(VERSION.toByte())
        .put(POWER_BUCKETS.toByte()).put(70).put((state.fault?.code ?: 0).toByte())
        .put(((if (state.connected) 1 else 0) or (if (state.suspended) 2 else 0) or
            (if (state.fault?.remoteRecoverable == true) 4 else 0)).toByte())
        .putInt(state.faultGeneration).put(0).array()
    fun recoveryGeneration(value: ByteArray): Int? =
        if (value.size == 5 && value[0].toInt() == VERSION) ByteBuffer.wrap(value, 1, 4).int else null
}
