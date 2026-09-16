package dev.gpxit.app.data.transit

/**
 * Means of transport, independent of any backend. The constant names match
 * public-transport-enabler's `Product`, so product names already persisted in
 * preferences and in `StationCandidate.products` stay valid.
 */
enum class TransitMode {
    HIGH_SPEED_TRAIN,
    REGIONAL_TRAIN,
    SUBURBAN_TRAIN,
    SUBWAY,
    TRAM,
    BUS,
    FERRY,
    CABLECAR,
    ON_DEMAND;

    companion object {
        val TRAINS: Set<TransitMode> = setOf(HIGH_SPEED_TRAIN, REGIONAL_TRAIN, SUBURBAN_TRAIN)

        /** Modes for the given names, ignoring unknown ones; regional + suburban trains if none remain. */
        fun fromNames(names: Collection<String>): Set<TransitMode> {
            val modes = names.mapNotNullTo(LinkedHashSet()) { name ->
                entries.firstOrNull { it.name == name }
            }
            return modes.ifEmpty { setOf(REGIONAL_TRAIN, SUBURBAN_TRAIN) }
        }
    }
}
