package app.mantrailing.gpx

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Le lecteur GPX avec l'analyseur XML d'Android : espaces de noms, préfixes, entités externes. */
@RunWith(AndroidJUnit4::class)
class GpxAndroidTest {
    @Test
    fun trace_garmin_avec_espaces_de_noms_et_extensions() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx:gpx version="1.1" creator="Garmin" xmlns:gpx="http://www.topografix.com/GPX/1/1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <gpx:trk><gpx:name>Rex</gpx:name><gpx:trkseg>
                <gpx:trkpt lat="48.4254" lon="2.6402"><gpx:ele>81</gpx:ele><gpx:time>2026-11-17T09:00:00Z</gpx:time>
                  <gpx:extensions><gpxtpx:TrackPointExtension><gpxtpx:speed>1.2</gpxtpx:speed></gpxtpx:TrackPointExtension></gpx:extensions>
                </gpx:trkpt>
                <gpx:trkpt lat="48.4255" lon="2.6403"><gpx:time>2026-11-17T09:00:05Z</gpx:time></gpx:trkpt>
              </gpx:trkseg></gpx:trk>
            </gpx:gpx>"""
        val track = Gpx.parse(xml.byteInputStream())
        assertEquals("Rex", track.name)
        assertEquals(2, track.points.size)
        assertEquals(1_794_906_000L, track.startedAt)
    }

    @Test
    fun entite_externe_jamais_lue() {
        val evil = """<?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///proc/self/status">]>
            <gpx><trk><name>&x;</name><trkseg><trkpt lat="48" lon="2"/></trkseg></trk></gpx>"""
        val result = runCatching { Gpx.parse(evil.byteInputStream()) }
        assertTrue(result.isFailure || result.getOrNull()?.name.isNullOrEmpty())
    }
}
