package com.mozhi.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAttachmentTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val attachments = listOf(
            MessageAttachment(MessageAttachment.TYPE_IMAGE, "attachments/3/img_1.jpg", "image/jpeg"),
            MessageAttachment(
                MessageAttachment.TYPE_TEXT_FILE,
                "attachments/3/file_1.txt",
                "text/plain",
                name = "笔记.txt"
            )
        )

        val encoded = MessageAttachment.encode(attachments)
        val decoded = MessageAttachment.decode(encoded)

        assertEquals(attachments, decoded)
    }

    @Test
    fun emptyListEncodesToNull() {
        assertNull(MessageAttachment.encode(emptyList()))
    }

    @Test
    fun malformedJsonDecodesToEmpty() {
        assertTrue(MessageAttachment.decode("not-json").isEmpty())
        assertTrue(MessageAttachment.decode(null).isEmpty())
    }
}
