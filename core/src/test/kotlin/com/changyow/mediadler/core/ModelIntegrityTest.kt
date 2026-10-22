package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.ModelIntegrity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ModelIntegrityTest {
    @Test fun exactSizeAndDigestRequired() {
        val file = Files.createTempFile("asr-integrity", ".bin").toFile()
        try {
            file.writeText("abc")
            val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            ModelIntegrity.verify(file, 3, expected)
            assertFailsWith<IllegalStateException> { ModelIntegrity.verify(file, 4, expected) }
            file.writeText("abd")
            assertFailsWith<IllegalStateException> { ModelIntegrity.verify(file, 3, expected) }
        } finally { file.delete() }
    }
}
