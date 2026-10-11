package com.nixplorer.screen.storage

import com.nixplorer.screen.archive.ArchivePasswordRequired
import com.nixplorer.screen.archive.unlockSevenZip
import com.nixplorer.screen.archive.unlockRar
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdditionalArchivePasswordTest {
    // Official Apache Commons Compress bla.encrypted.7z fixture (Apache-2.0).
    @Test fun encryptedSevenZipCanRetryPassword() = runBlocking {
        val folder = Files.createTempDirectory("seven-password").toFile()
        try {
            val source = folder.resolve("protected.7z").apply { writeBytes(Base64.getDecoder().decode("N3q8ryccAAPOr6vM4AEAAAAAAAA2AAAAAAAAAMPV/c15gWF4/yjZnVseP50CUVynWCTYmSp6Ke098yYgF/M6rarfE3VIk5F/VKhRnpC3g8FD942gEXEDLnd12CRgkhC4qrpznDYACE9oBNV+7PMngR+vT6qzXjm/S08zuAzS7kP6aINNqGgZl3g9JTwGgqDCfkC35h3sjatUkdayb13cMgUiSA0sB7tR2E2lxhH/Hm30QeqD4dRDXT6l81+9PEOeQSNNZkrZfSy8lRJT5MKMAQtwUajG+BlU19/tUokPbvptGnP5PjZAHGEQrlK/avb4U0GKB12F4KU4v0zskuJwfX84UYn8fymldsL2Bn9pKrRQWx9b2AxZ97CUxdydqSv8Cc2U8gw1gJBMqOy7Af8JFTjOleTEou4N+ZdZw29Sf2QM1jKMJPSjGoVkwe2T8BmB4jM1PV+Hn8PAsrwN1K7vIMhOZEQyvSmXQFDSfDudsK6kFCdyZmqJjREA5baUovT+CsnOtEOy65EqnQmHtOh0ZYHPNn2UMuvzje+LVpw5OQaHXLyqSgA9PYSEuy9fqr+exsGfYZ6FE+K7UKPc5XLI60V7z6+4Z7em7vD6qG8IQYWNdQVJDXZbTmpDw/7nk+tXITiSCnowCfCNmLHbVSAubMECBTPUE2kDx01w49txxyIXBoFgAQmAgAAHCwEAAiQG8QcBClMHznmlTwXNceUjAwEBBV0AEAAAAQAMd4CRCgFx7ooQAAA=")) }
            assertTrue(runCatching { unlockSevenZip(source, null) }.exceptionOrNull() is ArchivePasswordRequired)
            assertTrue(runCatching { unlockSevenZip(source, "wrong".toCharArray()) }.exceptionOrNull() is ArchivePasswordRequired)
            val opened = unlockSevenZip(source, "foo".toCharArray())
            java.util.zip.ZipFile(opened).use { archive -> assertTrue(archive.entries().asSequence().filter { !it.isDirectory }.sumOf { archive.getInputStream(it).use { stream -> stream.readBytes().size } } > 0) }
        } finally { folder.deleteRecursively() }
    }
    // Official Junrar v8.1.2 rar5-encrypted-junrar.rar fixture.
    @Test fun encryptedRarFiveCanRetryPassword() = runBlocking {
        val folder = Files.createTempDirectory("rar-password").toFile()
        try {
            val source = folder.resolve("protected.rar").apply { writeBytes(Base64.getDecoder().decode("UmFyIRoHAQAYOJrPIQQAAAEPprqRs1Vs70VeAnJr65GiUWzJnBs88EB6pEDZCDMNebpM1FRWRidOoP8NEKunwvQXSE6qyWZSmFdTmJz5B4PRrGmc/9wgf07nAr0VnT/SUD7KGRm04mC2+uJap3bok3fPNwjtWnVbqxga+30ke8uVJYZkiuuGhz7dmPmsjcbbifv8JRtif4lMcFsoiFclxaKGgHWEAQ5iUr3A418NqLr87fq2lB4LpFyCVjVgrfNS3Ou5IdI1MBz0SPemsbqYy9mjOR0uTISWgDqgl9qBApSZxzov0pm4HbMDGpR2jEAzLnvZFtWMyUYPoVpguDM=")) }
            assertTrue(runCatching { unlockRar(source, null) }.exceptionOrNull() is ArchivePasswordRequired)
            assertTrue(runCatching { unlockRar(source, "wrong".toCharArray()) }.exceptionOrNull() is ArchivePasswordRequired)
            val opened = unlockRar(source, "junrar".toCharArray())
            java.util.zip.ZipFile(opened).use { archive -> assertTrue(archive.entries().asSequence().filter { !it.isDirectory }.sumOf { archive.getInputStream(it).use { stream -> stream.readBytes().size } } > 0) }
        } finally { folder.deleteRecursively() }
    }
}
