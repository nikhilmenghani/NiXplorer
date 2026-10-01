package com.nixplorer.screen.main.tab.files.misc

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TransportStreamTest {
    private fun detect(bytes: ByteArray): Boolean {
        val file = File.createTempFile("recording", ".ts")
        return try {
            file.writeBytes(bytes)
            file.isTransportStream()
        } finally { file.delete() }
    }

    @Test fun recognizesTransportPacketFormats() {
        for (size in listOf(188, 192, 204)) {
            val bytes = ByteArray(size * 5)
            val offset = if (size == 192) 4 else 0
            repeat(5) { bytes[it * size + offset] = 0x47 }
            assertTrue(detect(bytes))
        }
    }

    @Test fun doesNotTreatTypeScriptOrTruncatedDataAsVideo() {
        assertFalse(detect("export const name = 'Google';".toByteArray()))
        assertFalse(detect(byteArrayOf(0x47)))
        assertFalse(detect(ByteArray(1024)))
    }
}
