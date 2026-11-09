package com.lumpforge.app

/**
 * Parser for ZDoom/GZDoom-style text definitions stored in a TEXTURES lump.
 *
 * This first pass supports composite WallTexture/Texture/Flat/Sprite/Graphic
 * blocks with Patch or Graphic placements. Patch sub-blocks are skipped safely;
 * advanced transforms are not applied yet.
 */
object WadTextTextureReader {
    private val definitionTypes = setOf(
        "WALLTEXTURE", "TEXTURE", "FLAT", "SPRITE", "GRAPHIC"
    )

    private val tokenRegex = Regex(
        """\"(?:\\.|[^\"\\])*\"|[A-Za-z_$][A-Za-z0-9_.$-]*|-?\d+(?:\.\d+)?|[{}(),;]"""
    )

    fun parse(data: ByteArray, sourceName: String = "TEXTURES"): List<WadTextureDefinition> {
        require(data.isNotEmpty()) { "$sourceName está vacío." }
        require(data.size <= 8 * 1024 * 1024) { "$sourceName supera el límite de lectura." }

        val text = String(data, Charsets.UTF_8).removePrefix("\uFEFF")
        val withoutComments = text
            .replace(Regex("""(?s)/\*.*?\*/"""), " ")
            .replace(Regex("""//[^\r\n]*"""), " ")
        val tokens = tokenRegex.findAll(withoutComments).map { it.value }.toList()

        val textures = ArrayList<WadTextureDefinition>()
        var cursor = 0

        while (cursor < tokens.size) {
            val type = tokens[cursor].uppercase()
            if (type !in definitionTypes) {
                cursor++
                continue
            }

            val parsed = parseDefinition(tokens, cursor, sourceName)
            if (parsed == null) {
                cursor++
            } else {
                textures += parsed.first
                cursor = parsed.second
            }
        }
        return textures
    }

    private fun parseDefinition(
        tokens: List<String>,
        start: Int,
        sourceName: String
    ): Pair<WadTextureDefinition, Int>? {
        var i = start + 1
        if (i + 4 >= tokens.size) return null

        val name = unquote(tokens[i++])
        if (tokens.getOrNull(i++) != ",") return null
        val width = parseDimension(tokens.getOrNull(i++)) ?: return null
        if (tokens.getOrNull(i++) != ",") return null
        val height = parseDimension(tokens.getOrNull(i++)) ?: return null
        if (tokens.getOrNull(i) != "{") return null
        i++

        require(width in 1..4096 && height in 1..4096) {
            "La textura $name tiene dimensiones no válidas ($width × $height)."
        }
        require(width.toLong() * height.toLong() <= 4_194_304L) {
            "La textura $name supera el límite seguro de memoria."
        }

        var depth = 1
        val patches = ArrayList<WadTexturePatch>()
        while (i < tokens.size && depth > 0) {
            val token = tokens[i]
            when {
                token == "{" -> {
                    depth++
                    i++
                }
                token == "}" -> {
                    depth--
                    i++
                }
                depth == 1 && token.equals("Patch", true) ->
                    i = parsePatch(tokens, i, patches)
                depth == 1 && token.equals("Graphic", true) ->
                    i = parsePatch(tokens, i, patches)
                else -> i++
            }
            require(patches.size <= 4096) {
                "La textura $name contiene demasiados parches."
            }
        }

        if (depth != 0) {
            throw IllegalArgumentException("La definición de $name en $sourceName no está cerrada.")
        }

        return WadTextureDefinition(
            name = name,
            width = width,
            height = height,
            patches = patches,
            sourceLump = sourceName
        ) to i
    }

    private fun parsePatch(
        tokens: List<String>,
        start: Int,
        output: MutableList<WadTexturePatch>
    ): Int {
        var i = start + 1
        val nameToken = tokens.getOrNull(i) ?: return start + 1
        val name = unquote(nameToken)
        i++
        if (tokens.getOrNull(i) != ",") return i
        i++
        val x = parseCoordinate(tokens.getOrNull(i)) ?: return i
        i++
        if (tokens.getOrNull(i) != ",") return i
        i++
        val y = parseCoordinate(tokens.getOrNull(i)) ?: return i
        i++

        output += WadTexturePatch(name = name, originX = x, originY = y)

        // Skip the optional per-patch properties block (FlipX, Rotate, Blend, etc.).
        if (tokens.getOrNull(i) == "{") {
            var depth = 0
            while (i < tokens.size) {
                when (tokens[i]) {
                    "{" -> depth++
                    "}" -> depth--
                }
                i++
                if (depth == 0) break
            }
        }
        return i
    }

    private fun parseDimension(token: String?): Int? {
        val value = token?.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value % 1.0 != 0.0) return null
        return value.toInt()
    }

    private fun parseCoordinate(token: String?): Int? {
        val value = token?.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value % 1.0 != 0.0) return null
        return value.toInt()
    }

    private fun unquote(value: String): String {
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            value
        }
    }
}
