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

    /** IconMinus — single horizontal bar. */
    val Minus: ImageVector = outline(
        "Minus",
        "M 5 12 H 19"
    )

    /** IconFullscreen — four corner brackets. */
    val Fullscreen: ImageVector = outline(
        "Fullscreen",
        "M 4 9 V 4 H 9 M 20 9 V 4 H 15 M 4 15 V 20 H 9 M 20 15 V 20 H 15"
    )

    /** IconSearch — circle with handle. */
    val Search: ImageVector = outlineMulti(
        "Search",
        "M 17 11 A 6 6 0 1 1 5 11 A 6 6 0 1 1 17 11 Z",
        "M 16 16 L 20 20",
    )

    /** IconMenu — three horizontal bars (Material hamburger). */
    val Menu: ImageVector = outline(
        "Menu",
        "M 4 7 H 20 M 4 12 H 20 M 4 17 H 20"
    )

    /** IconTrain — rounded rect body + window + legs. */
    val Train: ImageVector = outlineMulti(
        "Train",
        "M 8 3 H 16 A 3 3 0 0 1 19 6 V 14 A 3 3 0 0 1 16 17 H 8 A 3 3 0 0 1 5 14 V 6 A 3 3 0 0 1 8 3 Z",
        "M 5 11 H 19",
        "M 9 17 L 7 21",
        "M 15 17 L 17 21",
    )

    /**
     * IconBike — bicycle silhouette: two wheels (3-radius circles
     * at (5.5,17) and (18.5,17)) plus the frame triangle, top-tube
     * and seatpost flourish. Wheels become SVG arcs so we keep a
     * single ImageVector.
     */
    val Bike: ImageVector = outlineMulti(
        "Bike",
        "M 8.5 17 A 3 3 0 1 1 2.5 17 A 3 3 0 1 1 8.5 17 Z",
        "M 21.5 17 A 3 3 0 1 1 15.5 17 A 3 3 0 1 1 21.5 17 Z",
        "M 5.5 17 L 10 10 L 15 10 L 18.5 17 M 10 10 L 14 10 M 13 6 H 16 L 15 10",
    )

    /**
     * IconTrash — wastebasket silhouette: lid bar + pull-tab + bin
     * body with two interior bars. Stroke-only to match the rest of
     * the icon set.
     */
    val Trash: ImageVector = outlineMulti(
        "Trash",
        "M 4 7 H 20",
        "M 9 7 V 5 A 1 1 0 0 1 10 4 H 14 A 1 1 0 0 1 15 5 V 7",
        "M 6 7 L 7 20 A 1 1 0 0 0 8 21 H 16 A 1 1 0 0 0 17 20 L 18 7",
        "M 10 11 V 17",
        "M 14 11 V 17",
    )

    /** IconMountain — two peaks. */
    val Mountain: ImageVector = outline(
        "Mountain",
        "M 3 20 L 9 10 L 13 15 L 17 8 L 21 20 Z"
    )

    /** IconClock — circle with hour/minute hands. */
    val Clock: ImageVector = outlineMulti(
        "Clock",
        "M 21 12 A 9 9 0 1 1 3 12 A 9 9 0 1 1 21 12 Z",
        "M 12 7 V 12 L 15 14",
    )

    /** IconHourglass — wait buffer icon for Settings. */
    val Hourglass: ImageVector = outlineMulti(
        "Hourglass",
        "M 6 4 H 18 V 8 H 6 Z",
        "M 6 16 H 18 V 20 H 6 Z",
        "M 6 8 L 12 12 L 18 8 M 6 16 L 12 12 L 18 16",
    )

    /** IconChevronUp — caret up. */
    val ChevronUp: ImageVector = outline(
        "ChevronUp",
        "M 6 15 L 12 9 L 18 15"
    )

    /** IconChevronDown — caret down. */
    val ChevronDown: ImageVector = outline(
        "ChevronDown",
        "M 6 9 L 12 15 L 18 9"
    )

    /** IconChevronLeft — caret left. */
    val ChevronLeft: ImageVector = outline(
        "ChevronLeft",
        "M 15 6 L 9 12 L 15 18"
    )

    /** IconChevronRight — caret right. */
    val ChevronRight: ImageVector = outline(
        "ChevronRight",
        "M 9 6 L 15 12 L 9 18"
    )

    /**
     * IconRefresh — single rotating arrow. Two short arc segments
     * forming a near-circle with arrowhead chevrons at one end so it
     * reads as "reload" at small sizes (used in the route card menu).
     */
    val Refresh: ImageVector = outlineMulti(
        "Refresh",
        // Top half of the loop, ending in an arrowhead at the right.
        "M 4 12 A 8 8 0 0 1 18 7",
        // Arrowhead at the right end of the top arc.
        "M 18 4 V 7 H 15",
        // Bottom half of the loop, ending in an arrowhead at the left.
        "M 20 12 A 8 8 0 0 1 6 17",
        // Arrowhead at the left end of the bottom arc.
        "M 6 20 V 17 H 9",
    )

    /**
     * IconAlert — warning triangle with bang. Used by the offline
     * station-discovery retry banner inside the route card.
     */
    val Alert: ImageVector = outlineMulti(
        "Alert",
        "M 12 4 L 22 20 H 2 Z",
        "M 12 10 V 14",
        "M 12 17 V 17.5",
    )
}
