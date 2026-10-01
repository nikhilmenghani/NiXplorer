package com.nixplorer.screen.main.tab.files.misc

import java.io.File

internal fun File.isTransportStream(): Boolean = runCatching {
    inputStream().use { input ->
        val header = ByteArray(1024)
        var count = 0
        while (count < header.size) {
            val read = input.read(header, count, header.size - count)
            if (read < 0) break
            count += read
        }
        // MPEG-TS uses a sync byte every 188 bytes. Also accept 192-byte
        // timestamped packets and 204-byte packets with error correction.
        listOf(188, 192, 204).any { packetSize ->
            (0 until packetSize).any { offset ->
                offset + packetSize * 3 < count && (0..3).all { packet ->
                    header[offset + packet * packetSize] == 0x47.toByte()
                }
            }
        }
    }
}.getOrDefault(false)
