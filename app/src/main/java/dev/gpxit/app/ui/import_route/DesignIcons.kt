package dev.gpxit.app.ui.import_route

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * ImageVector renditions of the stroke-only icons shipped with the
 * design handoff (`gpxit-map-ui/project/components/icons.jsx`). Paths
 * are ported verbatim where possible — circles become SVG arcs so
 * we can live inside a single ImageVector per icon. All strokes are
 * painted in black; use `Icon(..., tint = ...)` to colour them.
 */
object DesignIcons {

    /** Outline-only icon from a single path string. */
    private fun outline(name: String, d: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    /** Multi-path outline icon — each path string gets its own stroke. */
    private fun outlineMulti(name: String, vararg ds: String): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        )
        for (d in ds) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /**
     * Settings cog — the detailed eight-tooth gear used in the
     * design's IconSettings, plus a central 3-radius circle.
     */
    val Settings: ImageVector = outlineMulti(
        "Settings",
        // Centre circle as an SVG arc pair.
        "M 15 12 A 3 3 0 1 1 9 12 A 3 3 0 1 1 15 12 Z",
        // Eight-tooth gear outline — this is the exact path from the
        // design handoff's icons.jsx.
        "M 19.4 15 A 1.7 1.7 0 0 0 19.6 16.7 L 19.7 16.8 " +
            "A 2 2 0 1 1 16.9 19.6 L 16.8 19.5 " +
            "A 1.7 1.7 0 0 0 14.1 20.7 " +
            "A 2 2 0 1 1 10.1 20.7 " +
            "A 1.7 1.7 0 0 0 7.4 19.5 L 7.3 19.6 " +
            "A 2 2 0 1 1 4.5 16.8 L 4.6 16.7 " +
            "A 1.7 1.7 0 0 0 4.8 15 " +
            "A 1.7 1.7 0 0 0 3.2 14 H 3 " +
            "A 2 2 0 1 1 3 10 H 3.2 " +
            "A 1.7 1.7 0 0 0 4.8 9 " +
            "A 1.7 1.7 0 0 0 4.6 7.3 L 4.5 7.2 " +
            "A 2 2 0 1 1 7.3 4.4 L 7.4 4.5 " +
            "A 1.7 1.7 0 0 0 9 4.7 " +
            "A 1.7 1.7 0 0 0 10 3.1 V 3 " +
            "A 2 2 0 1 1 14 3 V 3.1 " +
            "A 1.7 1.7 0 0 0 15 4.7 " +
            "A 1.7 1.7 0 0 0 16.7 4.5 L 16.8 4.4 " +
            "A 2 2 0 1 1 19.6 7.2 L 19.5 7.3 " +
            "A 1.7 1.7 0 0 0 19.3 9 " +
            "A 1.7 1.7 0 0 0 20.9 10 H 21 " +
            "A 2 2 0 1 1 21 14 H 20.9 " +
            "A 1.7 1.7 0 0 0 19.4 15 Z"
    )

    /**
     * IconRoute — two waypoint circles connected by an S-curve.
     * Circles at (6, 5) and (18, 19), radius 2, turned into SVG arcs.
     */
    val Route: ImageVector = outlineMulti(
        "Route",
        "M 8 5 A 2 2 0 1 0 4 5 A 2 2 0 1 0 8 5 Z",
        "M 20 19 A 2 2 0 1 0 16 19 A 2 2 0 1 0 20 19 Z",
        "M 6 7 C 6 12, 14 8, 14 13 S 18 17, 18 17",
    )

    /** IconLayers — three stacked diamonds. */
    val Layers: ImageVector = outline(
        "Layers",
        "M 12 3 L 3 8 L 12 13 L 21 8 Z M 3 13 L 12 18 L 21 13 M 3 17 L 12 22 L 21 17"
    )

    /** IconPlus — vertical + horizontal crossbars. */
    val Plus: ImageVector = outline(
        "Plus",
        "M 12 5 V 19 M 5 12 H 19"
    )

    /** IconHome — simple roofline house outline. */
    val Home: ImageVector = outline(
        "Home",
        "M 4 11 L 12 4 L 20 11 V 20 H 14 V 14 H 10 V 20 H 4 Z"
    )
}
