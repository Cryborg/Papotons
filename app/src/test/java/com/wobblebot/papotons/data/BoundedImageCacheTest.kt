package com.wobblebot.papotons.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedImageCacheTest {
    @Test
    fun `below the bound nothing is evicted`() {
        val cache = mapOf(1L to "a").plusEvictingOldest(2L, "b", maxSize = 2)

        assertEquals(mapOf(1L to "a", 2L to "b"), cache)
    }

    @Test
    fun `beyond the bound the oldest entry leaves first`() {
        val cache = (1L..3L).fold(emptyMap<Long, String>()) { images, id -> images.plusEvictingOldest(id, "image $id", maxSize = 2) }

        assertEquals(listOf(2L, 3L), cache.keys.toList())
    }

    @Test
    fun `the default bound keeps exactly MAX_DECODED_IMAGES images`() {
        val cache = (1L..MAX_DECODED_IMAGES + 10L).fold(emptyMap<Long, Long>()) { images, id -> images.plusEvictingOldest(id, id) }

        assertEquals(MAX_DECODED_IMAGES, cache.size)
        assertEquals(11L, cache.keys.first())
    }
}
