package com.changyow.mediadler.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MimeTypesTest {
    @Test fun extensionlessJpegGetsAnImageExtensionAndMime() {
        val header = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
            0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        withFile(header) { file ->
            val ext = MimeTypes.imageExtensionFromContent(file)
            assertEquals("jpg", ext)
            assertEquals("image/jpeg", MimeTypes.fromExtension(checkNotNull(ext)))
        }
    }

    @Test fun webpIsRecognizedWithoutAFileExtension() {
        withFile("RIFF1234WEBPVP8 ".toByteArray()) { assertEquals("webp", MimeTypes.imageExtensionFromContent(it)) }
    }

    @Test fun htmlResponseIsNotSavedAsAnImage() {
        withFile("<!doctype html><html>Access denied</html>".toByteArray()) {
            assertNull(MimeTypes.imageExtensionFromContent(it))
        }
    }

    private fun withFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("image-content-", ".unknown_video")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
