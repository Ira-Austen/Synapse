package com.synapse.protocol

/**
 * Snapshot of a live process read directly from `system_server` memory (zero Root / <1ms).
 */
data class LiveProcessSnapshot(
    val pkg: String,
    val userId: Int,
    val processName: String,
    val pid: Int,
    val uid: Int,
    val curAdj: Int,
    val curProcState: Int,
    val frozen: Boolean,
    val rssKb: Long
)
