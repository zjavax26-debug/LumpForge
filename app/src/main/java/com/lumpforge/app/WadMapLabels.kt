package com.lumpforge.app

data class DecodedMapFlags(
    val labels: List<String>,
    val unknownMask: Int
)

/**
 * Vanilla Doom/Doom II editor-number labels and classic map flag decoding.
 * Unknown/custom IDs and flag bits are deliberately preserved for modded WADs.
 */
object WadMapLabels {
    private val thingNames = mapOf(
        1 to "Inicio del jugador 1",
        2 to "Inicio del jugador 2",
        3 to "Inicio del jugador 3",
        4 to "Inicio del jugador 4",
        5 to "Tarjeta azul",
        6 to "Tarjeta amarilla",
        7 to "Spider Mastermind",
        8 to "Mochila de munición",
        9 to "Zombie con escopeta",
        10 to "Restos sangrientos",
        11 to "Inicio de combate a muerte",
        12 to "Restos sangrientos",
        13 to "Tarjeta roja",
        14 to "Destino de teletransporte",
        15 to "Cadáver de jugador",
        16 to "Cyberdemon",
        17 to "Mochila de celdas de energía",
        18 to "Cadáver de zombi",
        19 to "Cadáver de soldado con escopeta",
        20 to "Cadáver de imp",
        21 to "Cadáver de demonio",
        22 to "Cadáver de cacodemon",
        23 to "Cadáver de Lost Soul invisible",
        24 to "Charco de sangre y restos",
        25 to "Humano empalado",
        26 to "Humano empalado retorciéndose",
        27 to "Cráneo sobre un poste",
        28 to "Cinco cráneos empalados",
        29 to "Montón de cráneos y velas",
        30 to "Columna verde alta",
        31 to "Columna verde baja",
        32 to "Columna roja alta",
        33 to "Columna roja baja",
        34 to "Vela",
        35 to "Candelabro",
        36 to "Columna verde con corazón",
        37 to "Columna roja con cráneo",
        38 to "Llave de calavera roja",
        39 to "Llave de calavera amarilla",
        40 to "Llave de calavera azul",
        41 to "Ojo maligno decorativo",
        42 to "Calavera flotante decorativa",
        43 to "Columna con corazón",
        44 to "Columna con calavera",
        45 to "Cuerpo colgante",
        46 to "Cuerpo colgante",
        47 to "Cuerpo colgante",
        48 to "Cuerpo colgante",
        49 to "Víctima colgante",
        50 to "Charco de sangre",
        51 to "Cerebros en el suelo",
        52 to "Arma: escopeta de dos cañones",
        53 to "Megasfera",
        54 to "Soldado SS",
        55 to "Árbol decorativo",
        56 to "Antorcha azul",
        57 to "Antorcha verde",
        58 to "Demonio espectral",
        59 to "Víctima colgante",
        60 to "Piernas colgantes",
        61 to "Víctima colgante",
        62 to "Pierna colgante",
        63 to "Víctima colgante retorciéndose",
        64 to "Arch-vile",
        65 to "Soldado con ametralladora",
        66 to "Revenant",
        67 to "Mancubus",
        68 to "Arachnotron",
        69 to "Hell Knight",
        71 to "Pain Elemental",
        72 to "Commander Keen",
        73 to "Árbol grande",
        74 to "Árbol pequeño",
        75 to "Antorcha roja",
        76 to "Antorcha verde",
        77 to "Antorcha azul",
        78 to "Árbol decorativo",
        79 to "Charco de sangre",
        80 to "Charco de sangre",
        81 to "Charco de cerebros",
        82 to "Arma: superescopeta",
        83 to "Megasfera",
        84 to "Soldado SS de Wolfenstein",
        85 to "Columna decorativa",
        86 to "Columna decorativa",
        87 to "Punto de aparición",
        88 to "Cerebro del jefe final",
        89 to "Emisor de aparición",
        2001 to "Arma: escopeta",
        2002 to "Arma: ametralladora",
        2003 to "Arma: lanzacohetes",
        2004 to "Arma: rifle de plasma",
        2005 to "Arma: motosierra",
        2006 to "Arma: BFG 9000",
        2007 to "Munición: cargador",
        2008 to "Munición: cartuchos de escopeta",
        2010 to "Munición: cohete",
        2011 to "Botiquín pequeño",
        2012 to "Botiquín",
        2013 to "Esfera de alma",
        2014 to "Bonificación de salud",
        2015 to "Bonificación de armadura",
        2018 to "Armadura verde",
        2019 to "Armadura azul",
        2022 to "Invulnerabilidad",
        2023 to "Berserk",
        2024 to "Invisibilidad parcial",
        2025 to "Traje antirradiación",
        2026 to "Mapa del ordenador",
        2028 to "Lámpara de suelo",
        2035 to "Barril explosivo",
        2045 to "Amplificador de luz",
        2046 to "Caja de cohetes",
        2047 to "Carga de celdas de energía",
        2048 to "Caja de munición",
        2049 to "Caja de cartuchos"
    )

    fun thingName(type: Int): String =
        thingNames[type] ?: "Tipo personalizado o desconocido"

    fun thingCategory(type: Int): String = when (type) {
        in 1..4, 11, 14 -> "Inicio / marcador"
        in 5..6, 13, in 17..17, in 2007..2015, in 2018..2019,
        in 2022..2026, 2045, in 2046..2049, 38, 39, 40, 52, 53, 82, 83 -> "Objeto / recogible"
        in 7..9, 16, 58, in 64..72, 84, in 3001..3006 -> "Enemigo"
        in 2001..2006 -> "Arma"
        else -> "Decoración / otro"
    }

    fun thingFlags(flags: Int): DecodedMapFlags {
        val labels = mutableListOf<String>()
        val difficulty = flags and 0x0007
        if (difficulty == 0) {
            labels += "Todas las dificultades"
        } else {
            if (difficulty and 0x0001 != 0) labels += "Fácil (niveles 1–2)"
            if (difficulty and 0x0002 != 0) labels += "Media (nivel 3)"
            if (difficulty and 0x0004 != 0) labels += "Difícil (niveles 4–5)"
        }
        if (flags and 0x0008 != 0) labels += "Emboscada (no despierta por sonido)"
        if (flags and 0x0010 != 0) labels += "Solo multijugador"
        if (flags and 0x0020 != 0) labels += "No aparece en combate a muerte (Boom)"
        if (flags and 0x0040 != 0) labels += "No aparece en cooperativo (Boom)"
        if (flags and 0x0080 != 0) labels += "Amistoso (MBF)"
        return DecodedMapFlags(labels, flags and 0x00ff.inv())
    }

    fun lineFlags(flags: Int): DecodedMapFlags {
        val labels = mutableListOf<String>()
        if (flags and 0x0001 != 0) labels += "Impasable: bloquea jugadores, monstruos y proyectiles"
        if (flags and 0x0002 != 0) labels += "Bloquea monstruos"
        if (flags and 0x0004 != 0) labels += "Dos lados"
        if (flags and 0x0008 != 0) labels += "Textura superior no anclada"
        if (flags and 0x0010 != 0) labels += "Textura inferior no anclada"
        if (flags and 0x0020 != 0) labels += "Secreto en el automapa"
        if (flags and 0x0040 != 0) labels += "Bloquea el sonido"
        if (flags and 0x0080 != 0) labels += "No mostrar en el automapa"
        if (flags and 0x0100 != 0) labels += "Siempre mostrar en el automapa"
        return DecodedMapFlags(labels, flags and 0x01ff.inv())
    }

    fun sectorSpecialName(special: Int): String = when (special) {
        0 -> "Normal"
        9 -> "Secreto"
        else -> "Especial de sector $special"
    }

    fun unknownFlags(mask: Int): String =
        "Bits no interpretados: 0x" + mask.toString(16).uppercase().padStart(4, '0')
}
