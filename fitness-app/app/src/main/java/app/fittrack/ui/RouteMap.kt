package app.fittrack.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.fittrack.data.TrackPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * OpenStreetMap-Karte mit der gelaufenen Strecke (ein Linienzug je Segment).
 * follow = true: Karte folgt dem letzten Punkt (Live-Ansicht), sonst wird die ganze Strecke eingepasst.
 */
@Composable
fun RouteMap(points: List<TrackPoint>, modifier: Modifier = Modifier, follow: Boolean = false) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { mv ->
        mv.overlays.removeAll { it is Polyline }
        points.groupBy { it.seg }.values.forEach { seg ->
            if (seg.size >= 2) {
                val line = Polyline(mv)
                line.setPoints(seg.map { GeoPoint(it.lat, it.lon) })
                line.outlinePaint.color = Color.rgb(255, 109, 0)
                line.outlinePaint.strokeWidth = 12f
                line.outlinePaint.isAntiAlias = true
                mv.overlays.add(line)
            }
        }
        val geo = points.map { GeoPoint(it.lat, it.lon) }
        if (geo.isNotEmpty()) {
            if (follow) {
                mv.controller.setCenter(geo.last())
            } else if (mv.tag != points.size) {
                mv.tag = points.size
                val fit = {
                    val box = BoundingBox.fromGeoPoints(geo)
                    if (box.latitudeSpan < 0.0005 && box.longitudeSpan < 0.0005) {
                        mv.controller.setZoom(17.0)
                        mv.controller.setCenter(box.centerWithDateLine)
                    } else {
                        mv.zoomToBoundingBox(box.increaseByScale(1.2f), false)
                    }
                }
                if (mv.width > 0 && mv.height > 0) fit() else mv.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
            }
        }
        mv.invalidate()
    })
}
