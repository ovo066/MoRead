package com.mozhi.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationMediaTest {
    @Test
    fun roundTripsAndBadJsonFallsBackToEmpty() {
        val media = AnnotationMedia(audioPath = "/tmp/a.mp3", illustrationId = 8)
        assertEquals(media, AnnotationMedia.decode(media.encode()))
        assertTrue(AnnotationMedia.decode("not-json").isEmpty)
    }
}
