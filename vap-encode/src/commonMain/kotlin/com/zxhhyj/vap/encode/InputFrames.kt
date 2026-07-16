package com.zxhhyj.vap.encode

internal fun resolveInputFrames(inputDir: String): List<String> {
    val base = PlatformFs.ensureTrailingSeparator(inputDir)
    val byIndex = LinkedHashMap<Int, String>()
    for (name in PlatformFs.listFileNames(inputDir)) {
        if (!name.endsWith(".png", ignoreCase = true)) continue
        val stem = name.substringBeforeLast('.')
        if (stem.isEmpty() || stem.any { !it.isDigit() }) continue
        val index = stem.toIntOrNull() ?: continue
        byIndex.putIfAbsent(index, base + name)
    }
    require(0 in byIndex) {
        "first frame not found (need 0.png / 00.png / 000.png … under $inputDir)"
    }
    val frames = ArrayList<String>()
    var i = 0
    while (i in byIndex) {
        frames += byIndex.getValue(i)
        i++
    }
    require(frames.isNotEmpty()) { "no frames found under $inputDir" }
    return frames
}
