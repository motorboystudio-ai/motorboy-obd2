package com.sirisakboy.ecuobd2.transport

interface Transport {
    val name: String
    suspend fun init()
    fun setOnDataListener(listener: (String) -> Unit)
    fun setOnCloseListener(listener: () -> Unit)
    suspend fun sendLine(line: String)
    suspend fun close()
}

interface RawByteTransport {
    val name: String
    suspend fun init()
    suspend fun write(bytes: ByteArray)
    suspend fun readUntil(n: Int, timeoutMs: Long): ByteArray
    fun drain()
    suspend fun close()
    fun setOnCloseListener(listener: () -> Unit)
}
