package com.watchnav.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContourMapCatalogTest {
    @Test
    fun `hangzhou resolves to current province bundle`() {
        val item = requireNotNull(ContourMapCatalog.covering(30.2741, 120.1551))

        assertEquals("china-jiangsu-anhui-zhejiang", item.id)
        assertTrue(item.url.path.endsWith("/mapsV5/china/Ch-Jiangsu-Anhui-Zhejiang.zip"))
    }

    @Test
    fun `catalog supports additional mainland regions`() {
        assertEquals("china-beijing-hebei-shandong", ContourMapCatalog.covering(39.9042, 116.4074)?.id)
        assertEquals("china-yunnan", ContourMapCatalog.covering(25.0389, 102.7183)?.id)
    }

    @Test
    fun `unverified discontinuous catalog regions fall back safely`() {
        assertNull(ContourMapCatalog.covering(36.0611, 103.8343))
    }
}
