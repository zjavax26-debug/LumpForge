package com.lumpforge.app

/**
 * Precomputed sector boundary index for classic Doom maps.
 *
 * A linedef belongs to the boundary of the sector referenced by either of its
 * sidedefs. Each linedef is added at most once per sector, including the rare
 * case where both sidedefs reference the same sector.
 */
class WadMapGeometryIndex private constructor(
    val sectorBoundaryLineIndices: List<Set<Int>>
) {
    /**
     * Returns the sector containing the supplied map-space point, or null when
     * the point lies outside all indexed sector boundaries.
     *
     * The even/odd ray-crossing rule supports sectors with multiple contours
     * and holes. It does not assume that all vertices belong to one polygon.
     */
    fun findSectorAtPoint(
        data: WadMapPreviewData,
        x: Float,
        y: Float
    ): Int? {
        val matches = sectorBoundaryLineIndices.indices.filter { sectorIndex ->
            val boundary = sectorBoundaryLineIndices[sectorIndex]
            boundary.isNotEmpty() && isInsideSector(data, boundary, x, y)
        }

        // Valid classic Doom maps normally yield a single matching sector.
        // If a custom map has overlapping sector contours, prefer the
        // smallest boundary as a conservative tie-breaker.
        return matches.minByOrNull { sectorBoundaryLineIndices[it].size }
    }

    private fun isInsideSector(
        data: WadMapPreviewData,
        lineIndices: Set<Int>,
        pointX: Float,
        pointY: Float
    ): Boolean {
        var inside = false

        for (lineIndex in lineIndices) {
            val line = data.lines.getOrNull(lineIndex) ?: continue
            val start = data.vertices.getOrNull(line.startVertex) ?: continue
            val end = data.vertices.getOrNull(line.endVertex) ?: continue

            val startY = start.y.toFloat()
            val endY = end.y.toFloat()

            // Horizontal edges do not cross the ray. The strict comparison
            // avoids counting a shared polygon vertex twice.
            if ((startY > pointY) != (endY > pointY)) {
                val intersectionX =
                    start.x + (end.x - start.x) *
                        (pointY - startY) / (endY - startY)
                if (pointX < intersectionX) {
                    inside = !inside
                }
            }
        }

        return inside
    }

    companion object {
        fun build(data: WadMapPreviewData): WadMapGeometryIndex {
            val boundaries = List(data.sectors.size) { linkedSetOf<Int>() }

            data.lines.forEachIndexed { lineIndex, line ->
                val adjacentSectorIndices = listOfNotNull(
                    data.sideDefs.getOrNull(line.rightSide)?.sectorIndex,
                    data.sideDefs.getOrNull(line.leftSide)?.sectorIndex
                ).distinct()

                adjacentSectorIndices.forEach { sectorIndex ->
                    if (sectorIndex in boundaries.indices) {
                        boundaries[sectorIndex].add(lineIndex)
                    }
                }
            }

            return WadMapGeometryIndex(boundaries.map { it.toSet() })
        }
    }
}
