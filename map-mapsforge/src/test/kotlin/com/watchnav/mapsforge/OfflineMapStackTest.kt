package com.watchnav.mapsforge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OfflineMapStackTest {
    private fun layer(id: String, kind: VectorMapLayerKind, hash: String) = VectorMapLayer(
        id = id,
        name = id,
        kind = kind,
        file = File("$id.map"),
        sha256 = hash.padEnd(64, '0'),
        bytes = 100,
        importedAtMillis = 1,
    )

    @Test
    fun cacheIdentityChangesWhenContourChanges() {
        val base = layer("base", VectorMapLayerKind.BASE, "base")
        val first = OfflineMapStack(base, layer("contour-a", VectorMapLayerKind.CONTOUR, "aaa"))
        val second = OfflineMapStack(base, layer("contour-b", VectorMapLayerKind.CONTOUR, "bbb"))

        assertNotEquals(first.cacheIdentity, second.cacheIdentity)
    }

    @Test
    fun stackKeepsBaseBeforeContourAndDeduplicatesFiles() {
        val shared = File("shared.map")
        val base = layer("base", VectorMapLayerKind.BASE, "base").copy(file = shared)
        val contour = layer("contour", VectorMapLayerKind.CONTOUR, "contour").copy(file = shared)

        assertEquals(listOf(shared), OfflineMapStack(base, contour).files)
    }
}
