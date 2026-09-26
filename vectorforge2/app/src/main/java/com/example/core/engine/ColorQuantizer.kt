package com.example.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stage 3: Adaptive Color Quantization & Palette Extraction.
 */
object ColorQuantizer {

    data class QuantizationResult(
        val palette: List<Int>, // ARGB integers (transparency included)
        val pixelClusterMap: IntArray, // Index into palette for each pixel (width * height)
        val width: Int,
        val height: Int
    )

    private data class ColorBox(
        val pixels: List<Int>,
        val rMin: Int, val rMax: Int,
        val gMin: Int, val gMax: Int,
        val bMin: Int, val bMax: Int
    ) {
        val volume: Int get() = (rMax - rMin + 1) * (gMax - gMin + 1) * (bMax - bMin + 1)
        val longestAxis: Int
            get() {
                val rSpan = rMax - rMin
                val gSpan = gMax - gMin
                val bSpan = bMax - bMin
                return when {
                    rSpan >= gSpan && rSpan >= bSpan -> 0 // Red
                    gSpan >= rSpan && gSpan >= bSpan -> 1 // Green
                    else -> 2 // Blue
                }
            }

        fun averageColor(): Int {
            if (pixels.isEmpty()) return Color.BLACK
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (p in pixels) {
                sumR += Color.red(p)
                sumG += Color.green(p)
                sumB += Color.blue(p)
            }
            val count = pixels.size
            return Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
        }
    }

    /**
     * Edge-preserving bilateral filter applied prior to color quantization for photographic images.
     * Smooths noise and fine sensor texture within tonal regions while preserving high-contrast edge boundaries.
     */
    fun applyEdgePreservingFilter(
        bitmap: Bitmap,
        sigmaSpatial: Double = 1.8,
        sigmaColor: Double = 26.0
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height
        val inPixels = IntArray(total)
        val outPixels = IntArray(total)
        bitmap.getPixels(inPixels, 0, width, 0, 0, width, height)

        val radius = 2
        val twoSigmaSpatialSq = 2.0 * sigmaSpatial * sigmaSpatial
        val twoSigmaColorSq = 2.0 * sigmaColor * sigmaColor

        // Precompute spatial Gaussian weights for 5x5 kernel
        val spatialWeights = Array(5) { DoubleArray(5) }
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val d2 = (dx * dx + dy * dy).toDouble()
                spatialWeights[dy + radius][dx + radius] = Math.exp(-d2 / twoSigmaSpatialSq)
            }
        }

        // Color difference LUT (0..255)
        val colorWeightLUT = DoubleArray(256)
        for (diff in 0..255) {
            colorWeightLUT[diff] = Math.exp(-(diff * diff).toDouble() / twoSigmaColorSq)
        }

        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val centerPx = inPixels[yOffset + x]
                val ca = (centerPx ushr 24) and 0xFF
                if (ca < 10) {
                    outPixels[yOffset + x] = centerPx
                    continue
                }

                val cr = (centerPx ushr 16) and 0xFF
                val cg = (centerPx ushr 8) and 0xFF
                val cb = centerPx and 0xFF

                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var sumW = 0.0

                val minY = (y - radius).coerceAtLeast(0)
                val maxY = (y + radius).coerceAtMost(height - 1)
                val minX = (x - radius).coerceAtLeast(0)
                val maxX = (x + radius).coerceAtMost(width - 1)

                for (ny in minY..maxY) {
                    val nyOffset = ny * width
                    val sWy = spatialWeights[ny - y + radius]
                    for (nx in minX..maxX) {
                        val neighborPx = inPixels[nyOffset + nx]
                        val nr = (neighborPx ushr 16) and 0xFF
                        val ng = (neighborPx ushr 8) and 0xFF
                        val nb = neighborPx and 0xFF

                        val diff = maxOf(abs(cr - nr), abs(cg - ng), abs(cb - nb))
                        val w = sWy[nx - x + radius] * colorWeightLUT[diff]

                        sumR += nr * w
                        sumG += ng * w
                        sumB += nb * w
                        sumW += w
                    }
                }

                val invW = if (sumW > 1e-6) 1.0 / sumW else 1.0
                val finalR = (sumR * invW).toInt().coerceIn(0, 255)
                val finalG = (sumG * invW).toInt().coerceIn(0, 255)
                val finalB = (sumB * invW).toInt().coerceIn(0, 255)

                outPixels[yOffset + x] = (ca shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        val smoothedBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        smoothedBmp.setPixels(outPixels, 0, width, 0, 0, width, height)
        return smoothedBmp
    }

    /**
     * Post-quantization micro-region merging pass.
     * Identifies connected components in pixelClusterMap smaller than minRegionPixels and
     * merges them into their largest neighboring region (by shared perimeter/border length).
     * Eliminates tens of thousands of single-pixel noisy islands while preserving 100% solid coverage.
     */
    fun mergeMicroRegions(
        quantResult: QuantizationResult,
        minRegionPixels: Int
    ): QuantizationResult {
        if (minRegionPixels <= 1) return quantResult

        val width = quantResult.width
        val height = quantResult.height
        val total = width * height
        val clusterMap = quantResult.pixelClusterMap.clone()

        // 1. Identify 4-connected components
        val compId = IntArray(total) { -1 }
        val compPaletteIdx = ArrayList<Int>()
        val compSizes = ArrayList<Int>()

        var numComps = 0
        val queue = IntArray(total)

        for (i in 0 until total) {
            if (compId[i] != -1) continue

            val pIdx = clusterMap[i]
            val currentComp = numComps++
            compPaletteIdx.add(pIdx)

            var head = 0
            var tail = 0
            queue[tail++] = i
            compId[i] = currentComp
            var size = 0

            while (head < tail) {
                val curr = queue[head++]
                size++
                val cx = curr % width
                val cy = curr / width

                if (cx > 0) {
                    val left = curr - 1
                    if (compId[left] == -1 && clusterMap[left] == pIdx) {
                        compId[left] = currentComp
                        queue[tail++] = left
                    }
                }
                if (cx < width - 1) {
                    val right = curr + 1
                    if (compId[right] == -1 && clusterMap[right] == pIdx) {
                        compId[right] = currentComp
                        queue[tail++] = right
                    }
                }
                if (cy > 0) {
                    val up = curr - width
                    if (compId[up] == -1 && clusterMap[up] == pIdx) {
                        compId[up] = currentComp
                        queue[tail++] = up
                    }
                }
                if (cy < height - 1) {
                    val down = curr + width
                    if (compId[down] == -1 && clusterMap[down] == pIdx) {
                        compId[down] = currentComp
                        queue[tail++] = down
                    }
                }
            }
            compSizes.add(size)
        }

        // 2. Build adjacency border counts between components
        val borderCounts = Array(numComps) { HashMap<Int, Int>() }
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val idx = yOffset + x
                val c = compId[idx]
                if (x + 1 < width) {
                    val right = idx + 1
                    val cr = compId[right]
                    if (c != cr) {
                        borderCounts[c][cr] = (borderCounts[c][cr] ?: 0) + 1
                        borderCounts[cr][c] = (borderCounts[cr][c] ?: 0) + 1
                    }
                }
                if (y + 1 < height) {
                    val down = idx + width
                    val cd = compId[down]
                    if (c != cd) {
                        borderCounts[c][cd] = (borderCounts[c][cd] ?: 0) + 1
                        borderCounts[cd][c] = (borderCounts[cd][c] ?: 0) + 1
                    }
                }
            }
        }

        // 3. Union-Find merge pass. Two independent conditions trigger a merge:
        //    (a) plain size cutoff: component smaller than minRegionPixels, OR
        //    (b) "isolated speck": component is small-ish (< 3x minRegionPixels) AND
        //        >=85% of its border touches a single dominant neighbor - i.e. it reads
        //        as a lone speck sitting inside one much larger flat region (e.g. a JPEG
        //        noise dot in open sky), even though its raw pixel count alone wouldn't
        //        have tripped the plain size cutoff.
        val parent = IntArray(numComps) { it }
        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        val isolatedSpeckThreshold = minRegionPixels * 3
        val candidateComps = (0 until numComps).filter { compSizes[it] < isolatedSpeckThreshold }
            .sortedBy { compSizes[it] }

        for (c in candidateComps) {
            val rootC = find(c)
            val rootSize = compSizes[rootC]
            if (rootSize >= isolatedSpeckThreshold) continue // already absorbed into something large

            var bestNeighbor = -1
            var maxBorder = -1
            var totalBorder = 0
            for ((nbr, count) in borderCounts[c]) {
                val rootNbr = find(nbr)
                if (rootNbr == rootC) continue
                totalBorder += count
                if (count > maxBorder) {
                    maxBorder = count
                    bestNeighbor = rootNbr
                }
            }
            if (bestNeighbor == -1) continue

            val enclosureFraction = if (totalBorder > 0) maxBorder.toDouble() / totalBorder else 0.0
            val shouldMerge = rootSize < minRegionPixels || enclosureFraction >= 0.85

            if (shouldMerge) {
                parent[rootC] = bestNeighbor
                compSizes[bestNeighbor] += rootSize
            }
        }

        // 4. Update pixel cluster map with merged component colors
        for (i in 0 until total) {
            val finalComp = find(compId[i])
            clusterMap[i] = compPaletteIdx[finalComp]
        }

        return quantResult.copy(pixelClusterMap = clusterMap)
    }

    /**
     * Post-quantization perceptual palette consolidation (photographic/complex-art mode only).
     * Merges palette entries whose color distance is below deltaEThreshold into one weighted-average
     * representative color. Prevents many near-duplicate dark/shadow tones (very common across
     * photographic shadow gradients, e.g. tree canopy interiors) from tracing as separate hard-edged
     * regions that visually fuse into a single flat blob instead of a few soft tonal bands.
     *
     * NOTE: distance uses the same redmean-weighted metric as colorDistanceSquared (not a strict
     * CIE ΔE formula), so deltaEThreshold is on that metric's scale, not literal CIELAB ΔE units.
     * Tune conservatively - start low and increase only after visually confirming no loss of
     * genuinely distinct color transitions (e.g. a real highlight-vs-shadow edge on a leaf).
     */
    fun consolidatePerceptuallySimilarColors(
        quantResult: QuantizationResult,
        deltaEThreshold: Double = 6.0
    ): QuantizationResult {
        val palette = quantResult.palette
        val n = palette.size
        if (n <= 2) return quantResult

        val hasTransparency = palette.isNotEmpty() && palette[0] == Color.TRANSPARENT
        val startIdx = if (hasTransparency) 1 else 0
        if (n - startIdx <= 1) return quantResult

        val counts = LongArray(n)
        for (idx in quantResult.pixelClusterMap) counts[idx]++

        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        for (i in startIdx until n) {
            for (j in i + 1 until n) {
                val dist = sqrt(colorDistanceSquared(palette[i], palette[j]))
                if (dist < deltaEThreshold) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
        }

        val groupSumR = DoubleArray(n)
        val groupSumG = DoubleArray(n)
        val groupSumB = DoubleArray(n)
        val groupCount = LongArray(n)
        for (i in startIdx until n) {
            val root = find(i)
            val w = counts[i].coerceAtLeast(1)
            groupSumR[root] += Color.red(palette[i]) * w
            groupSumG[root] += Color.green(palette[i]) * w
            groupSumB[root] += Color.blue(palette[i]) * w
            groupCount[root] += w
        }

        val newPalette = ArrayList<Int>()
        val remap = IntArray(n)
        if (hasTransparency) {
            newPalette.add(Color.TRANSPARENT)
            remap[0] = 0
        }
        val rootToNewIdx = HashMap<Int, Int>()
        for (i in startIdx until n) {
            val root = find(i)
            val newIdx = rootToNewIdx.getOrPut(root) {
                val cnt = groupCount[root].coerceAtLeast(1)
                val avgColor = Color.rgb(
                    (groupSumR[root] / cnt).toInt().coerceIn(0, 255),
                    (groupSumG[root] / cnt).toInt().coerceIn(0, 255),
                    (groupSumB[root] / cnt).toInt().coerceIn(0, 255)
                )
                newPalette.add(avgColor)
                newPalette.size - 1
            }
            remap[i] = newIdx
        }

        val newClusterMap = IntArray(quantResult.pixelClusterMap.size) { i ->
            remap[quantResult.pixelClusterMap[i]]
        }

        return quantResult.copy(palette = newPalette, pixelClusterMap = newClusterMap)
    }

    /**
     * Quantizes bitmap pixels into a compact, distinct color palette.
     */
    fun quantize(
        bitmap: Bitmap,
        targetColors: Int = 16,
        transparentThreshold: Int = 40
    ): QuantizationResult {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val opaquePixels = ArrayList<Int>(totalPixels)
        val isTransparent = BooleanArray(totalPixels)

        for (i in 0 until totalPixels) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a < transparentThreshold) {
                isTransparent[i] = true
            } else {
                opaquePixels.add(c)
            }
        }

        val hasTransparency = opaquePixels.size < totalPixels
        val effectiveColorBudget = if (hasTransparency) (targetColors - 1).coerceAtLeast(2) else targetColors

        val palette = ArrayList<Int>()
        if (hasTransparency) {
            palette.add(Color.TRANSPARENT)
        }

        if (opaquePixels.isNotEmpty()) {
            val opaquePalette = medianCut(opaquePixels, effectiveColorBudget)
            palette.addAll(opaquePalette)
        }

        if (palette.isEmpty()) {
            palette.add(Color.BLACK)
        }

        // Map every pixel to closest palette entry
        val clusterMap = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            if (isTransparent[i]) {
                clusterMap[i] = 0 // Transparent index
            } else {
                val pixel = pixels[i]
                var bestDist = Double.MAX_VALUE
                var bestIdx = if (hasTransparency) 1 else 0
                val startIdx = if (hasTransparency) 1 else 0

                for (pIdx in startIdx until palette.size) {
                    val dist = colorDistanceSquared(pixel, palette[pIdx])
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = pIdx
                    }
                }
                clusterMap[i] = bestIdx
            }
        }

        return QuantizationResult(
            palette = palette,
            pixelClusterMap = clusterMap,
            width = width,
            height = height
        )
    }

    private fun medianCut(pixels: List<Int>, maxColors: Int): List<Int> {
        if (pixels.isEmpty()) return emptyList()

        val initialBox = createBox(pixels)
        val boxes = arrayListOf(initialBox)

        while (boxes.size < maxColors) {
            // Pick box with highest pixel count and non-zero volume
            var splitIdx = -1
            var maxScore = -1

            for (i in 0 until boxes.size) {
                val box = boxes[i]
                if (box.pixels.size > 1 && box.volume > 1) {
                    val score = box.pixels.size
                    if (score > maxScore) {
                        maxScore = score
                        splitIdx = i
                    }
                }
            }

            if (splitIdx == -1) break // Cannot split further

            val boxToSplit = boxes.removeAt(splitIdx)
            val axis = boxToSplit.longestAxis
            val sorted = boxToSplit.pixels.sortedBy { p ->
                when (axis) {
                    0 -> Color.red(p)
                    1 -> Color.green(p)
                    else -> Color.blue(p)
                }
            }

            val mid = sorted.size / 2
            val box1 = createBox(sorted.subList(0, mid))
            val box2 = createBox(sorted.subList(mid, sorted.size))

            if (box1.pixels.isNotEmpty()) boxes.add(box1)
            if (box2.pixels.isNotEmpty()) boxes.add(box2)
        }

        return boxes.map { it.averageColor() }
    }

    private fun createBox(pixels: List<Int>): ColorBox {
        var rMin = 255
        var rMax = 0
        var gMin = 255
        var gMax = 0
        var bMin = 255
        var bMax = 0

        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            if (r < rMin) rMin = r
            if (r > rMax) rMax = r
            if (g < gMin) gMin = g
            if (g > gMax) gMax = g
            if (b < bMin) bMin = b
            if (b > bMax) bMax = b
        }

        return ColorBox(pixels, rMin, rMax, gMin, gMax, bMin, bMax)
    }

    private fun colorDistanceSquared(c1: Int, c2: Int): Double {
        val r1 = Color.red(c1)
        val g1 = Color.green(c1)
        val b1 = Color.blue(c1)
        val r2 = Color.red(c2)
        val g2 = Color.green(c2)
        val b2 = Color.blue(c2)

        // Perceptually weighted Euclidean RGB distance (redmean formula)
        val rMean = (r1 + r2) / 2
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2

        val weightR = 2 + (rMean / 256.0)
        val weightG = 4.0
        val weightB = 2 + ((255 - rMean) / 256.0)

        return weightR * dr * dr + weightG * dg * dg + weightB * db * db
    }
}
