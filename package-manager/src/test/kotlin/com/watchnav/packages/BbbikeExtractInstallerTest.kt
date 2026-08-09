package com.watchnav.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BbbikeExtractInstallerTest {
    @Test
    fun `parses request token`() {
        val html = """<input type="hidden" name="expire" value="1786198505" />"""
        assertEquals("1786198505", BbbikeExtractInstaller.parseExpiry(html))
    }

    @Test
    fun `finds only matching mapsforge result`() {
        val html = """
            <table><tr>
              <td><span title="watchnav-123">watchnav-123</span></td>
              <td><span class="format_mapsforge_osm_zip">Mapsforge OSM</span></td>
              <td><a href="/osm/extract/planet_120,30_121,31.osm.mapsforge-osm.zip">download</a></td>
            </tr></table>
        """.trimIndent()

        assertEquals(
            "/osm/extract/planet_120,30_121,31.osm.mapsforge-osm.zip",
            BbbikeExtractInstaller.parseDownloadUrl(html, "watchnav-123"),
        )
        assertNull(BbbikeExtractInstaller.parseDownloadUrl(html, "watchnav-456"))
    }
}
