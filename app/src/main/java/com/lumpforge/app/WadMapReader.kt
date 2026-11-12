package com.lumpforge.app

data class WadMapVertex(val x: Int, val y: Int)

data class WadMapLine(
    val startVertex: Int,
    val endVertex: Int,
    val flags: Int,
    val special: Int,
    val tag: Int,
    val rightSide: Int,
    val leftSide: Int
)

data class WadMapSector(
    val floorHeight: Int,
    val ceilingHeight: Int,
    val floorTexture: String,
    val ceilingTexture: String,
    val lightLevel: Int,
    val special: Int,
    val tag: Int
)

data class WadMapSideDef(val sectorIndex: Int)

data class WadMapThing(
    val x: Int,
    val y: Int,
    val angle: Int,
    val type: Int,
    val flags: Int
)

data class WadMapPreviewData(
    val mapName: String,
    val vertices: List<WadMapVertex>,
    val lines: List<WadMapLine>,
    val things: List<WadMapThing>,
    val sideDefs: List<WadMapSideDef>,
    val sectors: List<WadMapSector>,
    val sectorCount: Int,
    val invalidLineCount: Int
)

/**
 * Reads the classic binary Doom map lumps needed for a first 2D overview.
 * Supported map layout: THINGS (10-byte records), LINEDEFS (14-byte records),
 * VERTEXES (4-byte records), and optional SECTORS (26-byte records).
 *
 * Hexen/UDMF maps and advanced GL node formats are intentionally not interpreted
 * by this first reader.
 */
object WadMapReader {
    private const val MAX_VERTICES = 200_000
    private const val MAX_LINES = 250_000
    private const val MAX_THINGS = 100_000
    private const val MAX_LUMP_BYTES = 32 * 1024 * 1024

    fun parse(
        mapName: String,
        mapLumps: List<WadLump>,
        readLump: (WadLump) -> ByteArray
    ): WadMapPreviewData {
        require(mapName.matches(Regex("(?i)(MAP[0-9]{2}|E[1-4]M[1-9][0-9]?)"))) {
            "El nombre seleccionado no parece ser un marcador de mapa Doom clásico."
        }

        require(mapLumps.none { it.name.equals("TEXTMAP", true) }) {
            "Este mapa utiliza UDMF (TEXTMAP). Su geometría requiere un lector específico."
        }
        val behavior = mapLumps.firstOrNull { it.name.equals("BEHAVIOR", true) }
        require(behavior == null) {
            "Este mapa contiene BEHAVIOR (formato Hexen). Su geometría requiere un lector específico."
        }

        fun required(name: String): WadLump =
            mapLumps.lastOrNull { it.name.equals(name, true) }
                ?: throw IllegalArgumentException("No se encontró el recurso $name dentro de $mapName.")

        fun bytes(lump: WadLump): ByteArray {
            require(lump.size in 1L..MAX_LUMP_BYTES.toLong()) {
                "El recurso ${lump.name} está vacío o supera el límite de lectura."
            }
            return readLump(lump)
        }

        val vertexBytes = bytes(required("VERTEXES"))
        require(vertexBytes.size % 4 == 0) {
            "VERTEXES tiene una longitud que no coincide con registros Doom de 4 bytes."
        }
        val vertexCount = vertexBytes.size / 4
        require(vertexCount in 2..MAX_VERTICES) {
            "El mapa declara una cantidad de vértices no válida: $vertexCount."
        }

        val vertices = ArrayList<WadMapVertex>(vertexCount)
        var offset = 0
        while (offset < vertexBytes.size) {
            vertices += WadMapVertex(
                x = signed16(vertexBytes, offset),
                y = signed16(vertexBytes, offset + 2)
            )
            offset += 4
        }

        val lineBytes = bytes(required("LINEDEFS"))
        require(lineBytes.size % 14 == 0) {
            "LINEDEFS tiene una longitud que no coincide con registros Doom de 14 bytes."
        }
        val rawLineCount = lineBytes.size / 14
        require(rawLineCount in 1..MAX_LINES) {
            "El mapa declara una cantidad de líneas no válida: $rawLineCount."
        }

        val lines = ArrayList<WadMapLine>(rawLineCount)
        var invalidLines = 0
        offset = 0
        while (offset < lineBytes.size) {
            val start = unsigned16(lineBytes, offset)
            val end = unsigned16(lineBytes, offset + 2)
            val flags = unsigned16(lineBytes, offset + 4)
            if (start < vertices.size && end < vertices.size && start != end) {
                lines += WadMapLine(
                    startVertex = start,
                    endVertex = end,
                    flags = flags,
                    special = unsigned16(lineBytes, offset + 6),
                    tag = unsigned16(lineBytes, offset + 8),
                    rightSide = unsigned16(lineBytes, offset + 10),
                    leftSide = unsigned16(lineBytes, offset + 12)
                )
            } else {
                invalidLines++
            }
            offset += 14
        }
        require(lines.isNotEmpty()) {
            "No se encontraron líneas válidas para dibujar en $mapName."
        }

        val thingLump = mapLumps.lastOrNull { it.name.equals("THINGS", true) }
        val things = if (thingLump == null || thingLump.size == 0L) {
            emptyList()
        } else {
            val thingBytes = bytes(thingLump)
            require(thingBytes.size % 10 == 0) {
                "THINGS no coincide con registros Doom clásicos de 10 bytes."
            }
            val count = thingBytes.size / 10
            require(count <= MAX_THINGS) {
                "El mapa contiene demasiados objetos para la vista previa."
            }
            buildList(count) {
                var p = 0
                while (p < thingBytes.size) {
                    add(
                        WadMapThing(
                            x = signed16(thingBytes, p),
                            y = signed16(thingBytes, p + 2),
                            angle = unsigned16(thingBytes, p + 4),
                            type = unsigned16(thingBytes, p + 6),
                            flags = unsigned16(thingBytes, p + 8)
                        )
                    )
                    p += 10
                }
            }
        }

        val sideDefLump = mapLumps.lastOrNull { it.name.equals("SIDEDEFS", true) }
        val sideDefs = if (sideDefLump == null || sideDefLump.size == 0L) {
            emptyList()
        } else {
            val sideBytes = bytes(sideDefLump)
            require(sideBytes.size % 30 == 0) {
                "SIDEDEFS no coincide con registros Doom clásicos de 30 bytes."
            }
            buildList(sideBytes.size / 30) {
                var p = 0
                while (p < sideBytes.size) {
                    add(WadMapSideDef(sectorIndex = unsigned16(sideBytes, p + 28)))
                    p += 30
                }
            }
        }

        val sectorLump = mapLumps.lastOrNull { it.name.equals("SECTORS", true) }
        val sectors = if (sectorLump == null || sectorLump.size == 0L) {
            emptyList()
        } else {
            val sectorBytes = bytes(sectorLump)
            require(sectorBytes.size % 26 == 0) {
                "SECTORS no coincide con registros Doom clásicos de 26 bytes."
            }
            buildList(sectorBytes.size / 26) {
                var p = 0
                while (p < sectorBytes.size) {
                    add(
                        WadMapSector(
                            floorHeight = signed16(sectorBytes, p),
                            ceilingHeight = signed16(sectorBytes, p + 2),
                            floorTexture = fixedName(sectorBytes, p + 4, 8),
                            ceilingTexture = fixedName(sectorBytes, p + 12, 8),
                            lightLevel = unsigned16(sectorBytes, p + 20),
                            special = unsigned16(sectorBytes, p + 22),
                            tag = unsigned16(sectorBytes, p + 24)
                        )
                    )
                    p += 26
                }
            }
        }

        return WadMapPreviewData(
            mapName = mapName,
            vertices = vertices,
            lines = lines,
            things = things,
            sideDefs = sideDefs,
            sectors = sectors,
            sectorCount = sectors.size,
            invalidLineCount = invalidLines
        )
    }

    fun isMapMarker(name: String): Boolean =
        name.matches(Regex("(?i)(MAP[0-9]{2}|E[1-4]M[1-9][0-9]?)"))

    private fun fixedName(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length)
            .takeWhile { it.toInt() != 0 }
            .toByteArray()
            .toString(Charsets.US_ASCII)
            .trim()

    private fun unsigned16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun signed16(bytes: ByteArray, offset: Int): Int {
        val value = unsigned16(bytes, offset)
        return if (value >= 0x8000) value - 0x1_0000 else value
    }
}
