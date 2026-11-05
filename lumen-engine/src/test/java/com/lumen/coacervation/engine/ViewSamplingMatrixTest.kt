package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.geometry.SamplingMatrixMath
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before

class ViewSamplingMatrixTest {
    private fun identity() = FloatArray(9).also(SamplingMatrixMath::identity)

    private fun transform(tx: Float, ty: Float, sx: Float = 1f, sy: Float = 1f,
        px: Float = 0f, py: Float = 0f, degrees: Float = 0f): FloatArray {
        val radians = degrees * Math.PI.toFloat() / 180f
        val a = cos(radians) * sx; val b = -sin(radians) * sy
        val d = sin(radians) * sx; val e = cos(radians) * sy
        return floatArrayOf(a, b, tx + px - a * px - b * py,
            d, e, ty + py - d * px - e * py, 0f, 0f, 1f)
    }

    private fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>) {
        assertEquals(x, actual.first, .002f)
        assertEquals(y, actual.second, .002f)
    }

    @Test fun nonCentralPivotAndUnequalScaleDoNotScaleTheBackdropWithTheButton() {
        val source = transform(12f, 24f)
        val target = transform(100f, 200f, .8f, 1.25f, 10f, 30f)
        val shader = FloatArray(9)
        assertTrue(SamplingMatrixMath.bitmapToTarget(source, target, 5f, 4f, shader, FloatArray(9)))
        // Bitmap (40,75) must still land at source screen (212,324), despite the button transform.
        val local = map(shader, 40f, 75f)
        assertPoint((212f - 102f) / .8f, (324f - 192.5f) / 1.25f, local)
        assertPoint(212f, 324f, map(target, local.first, local.second))
    }

    @Test fun ancestorRotationTranslationAndScrollComposeInTheCorrectOrder() {
        val child = transform(8f, -3f, 1.3f, .7f, 7f, 19f, 25f)
        // Layout left/top minus the parent's scroll are outside the child's pivot transform.
        SamplingMatrixMath.translateAfter(child, 40f - 11f, 70f - 23f)
        val parent = transform(15f, 26f, .9f, 1.2f, 31f, 5f, -40f)
        SamplingMatrixMath.translateAfter(parent, 100f, 180f)
        val target = FloatArray(9)
        SamplingMatrixMath.multiply(parent, child, target)
        SamplingMatrixMath.translateAfter(target, 20f, 35f)
        val childPoint = map(child, 11f, 27f)
        val parentPoint = map(parent, childPoint.first, childPoint.second)
        assertPoint(parentPoint.first + 20f, parentPoint.second + 35f, map(target, 11f, 27f))
        val source = transform(-18f, 42f, 1.1f, .85f, 12f, 18f, 12f)
        val shader = FloatArray(9)
        assertTrue(SamplingMatrixMath.bitmapToTarget(source, target, 3f, 6f, shader, FloatArray(9)))
        for ((x, y) in listOf(0f to 0f, 17f to 29f, 103f to 84f)) {
            val expectedScreen = map(source, x * 3f, y * 6f)
            val targetLocal = map(shader, x, y)
            assertPoint(expectedScreen.first, expectedScreen.second, map(target, targetLocal.first, targetLocal.second))
        }
    }

    @Test fun separateDialogAndActivityWindowsUseTheirOwnOffsets() {
        val source = transform(18f, 46f)
        val target = transform(-27f, 191f, 1.2f, .6f, 13f, 8f)
        val shader = FloatArray(9)
        assertTrue(SamplingMatrixMath.bitmapToTarget(source, target, 2f, 3f, shader, FloatArray(9)))
        val local = map(shader, 40f, 50f)
        assertPoint(98f, 196f, map(target, local.first, local.second))
    }

    @Test fun rootScreenCalibrationRetainsFractionalPivotsAndWindowPan() {
        val root = transform(10.3f, -8.6f, .85f, 1.1f, 13f, 7f, 12f)
        val actual = root.copyOf()
        val origin = map(root, 0f, 0f)
        val screenX = Math.round(origin.first) + 83
        val screenY = Math.round(origin.second) + 29 - 47 // window offset plus ViewRoot pan
        assertTrue(SamplingMatrixMath.alignRootToScreen(actual, root, screenX, screenY))
        val expected = map(root, 15f, 21f)
        assertPoint(expected.first + 83f, expected.second - 18f, map(actual, 15f, 21f))
    }

    @Test fun perspectiveAndAliasedScratchRemainInvertibleWhileInvalidTransformsFailClosed() {
        val projective = floatArrayOf(1.1f, .2f, 7f, -.1f, .8f, 9f, .001f, -.0004f, 1f)
        val inverse = projective.copyOf()
        assertTrue(SamplingMatrixMath.invert(inverse, inverse))
        val identityResult = projective.copyOf()
        SamplingMatrixMath.multiply(inverse, identityResult, identityResult)
        assertPoint(20f, 30f, map(identityResult, 20f, 30f))
        val unchanged = FloatArray(9) { 123f }
        assertFalse(SamplingMatrixMath.invert(FloatArray(9), unchanged))
        assertArrayEquals(FloatArray(9) { 123f }, unchanged, 0f)
        assertFalse(SamplingMatrixMath.bitmapToTarget(identity(), transform(0f, 0f, 0f, 1f), 1f, 1f, FloatArray(9), FloatArray(9)))
        assertFalse(SamplingMatrixMath.bitmapToTarget(identity(), identity(), Float.NaN, 1f, FloatArray(9), FloatArray(9)))
    }

    @Test fun fullFootprintsDetectScaleChangesEvenWhenTheScreenOriginDoesNotMove() {
        val first = transform(100f, 200f)
        val pressed = transform(100f, 200f, .98f, .98f)
        assertEquals(map(first, 0f, 0f), map(pressed, 0f, 0f))
        assertFalse(SamplingMatrixMath.equal(first, pressed))
        assertTrue(SamplingMatrixMath.equal(first, first.copyOf()))
    }

    @Test fun rendererUsesTheSharedTransformForDrawingAndFullFootprintsWithoutNewCapture() {
        fun source(relative: String): String {
            val path = "src/main/java/com/lumen/coacervation/engine/$relative.kt"
            return SourceContract.read(path)
        }
        val renderer = source("material/FrostedMaterialRenderer")
        val draw = renderer.after("internal fun drawSample(").before("internal fun register(")
        assertTrue(draw.contains("samplingMatrices.bitmapToTarget(sourceRoot, view,"))
        assertFalse(draw.contains("getLocationOnScreen"))
        assertFalse(draw.contains("postTranslate"))
        assertTrue(renderer.contains("SamplingMatrixMath.equal(position.target, movedTransform)"))
        assertTrue(renderer.contains("SamplingMatrixMath.equal(position.source, sourceTransform)"))
        assertFalse(renderer.contains("PixelCopy"))
        val helper = source("geometry/ViewSamplingMatrix")
        // 逐层仍取 View 自己的矩阵（含旋转、缩放与轴心），单位矩阵只是省掉 getValues 拷贝。
        assertTrue(helper.contains("viewStep(current, step)"))
        assertTrue(helper.contains("val transform = view.matrix"))
        assertTrue(helper.contains("if (transform.isIdentity) SamplingMatrixMath.identity(out) else transform.getValues(out)"))
        assertTrue(helper.contains("parent?.scrollX"))
        assertTrue(helper.contains("parent?.scrollY"))
        assertFalse(helper.contains(".transformMatrixToGlobal("))
    }

    /** 纯 Kotlin 包围盒与逐角映射一致；透视矩阵交还给 Skia。 */
    @Test fun mapBoundsMatchesCornerMappingForAffineAndRejectsPerspective() {
        val out = FloatArray(4)
        val cases = listOf(identity(), transform(12.5f, -3f), transform(4f, 9f, sx = 1.07f, sy = 0.92f, px = 40f, py = 20f),
            transform(-7f, 2f, sx = -1f), transform(0f, 0f, degrees = 3.5f, px = 60f, py = 24f),
            transform(30f, 30f, sx = 0.8f, sy = 1.2f, degrees = 135f))
        for (m in cases) {
            assertTrue(SamplingMatrixMath.mapBounds(m, 120f, 48f, out))
            val corners = listOf(map(m, 0f, 0f), map(m, 120f, 0f), map(m, 0f, 48f), map(m, 120f, 48f))
            assertEquals(corners.minOf { it.first }, out[0], 1e-3f); assertEquals(corners.minOf { it.second }, out[1], 1e-3f)
            assertEquals(corners.maxOf { it.first }, out[2], 1e-3f); assertEquals(corners.maxOf { it.second }, out[3], 1e-3f)
        }
        // 只有平移与缩放时与 Skia 的乘加快路径同序运算，结果逐位确定。
        assertTrue(SamplingMatrixMath.mapBounds(transform(0.5f, 0.25f), 20f, 20f, out))
        assertArrayEquals(floatArrayOf(0.5f, 0.25f, 20.5f, 20.25f), out, 0f)
        assertFalse(SamplingMatrixMath.mapBounds(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f), 10f, 10f, out))
    }

    /** 版本签名的逐 View 路径不再经 Matrix JNI，目标逆矩阵每次快照只求一次。 */
    @Test fun viewportVersionTraversalStaysOnFloatArrays() {
        val source = SourceContract.read("host/LumenViewportContentSource.kt")
        val visit = source.after("private fun visitVersion(").before("private val consumeRegion")
        assertTrue(visit.contains("SamplingMatrixMath.multiply(targetInverse, global, matrixValues)"))
        assertTrue(visit.contains("SamplingMatrixMath.mapBounds("))
        // 子 View 直接用父全局矩阵：与祖先备忘同一个 step 公式（视图矩阵 + left/top − 父滚动）与乘法次序。
        assertTrue(visit.contains("mapping.viewStep(view, stepScratch)"))
        assertTrue(visit.contains("(view.left.toLong() - parent.scrollX).toFloat(), (view.top.toLong() - parent.scrollY).toFloat())"))
        assertTrue(visit.contains("SamplingMatrixMath.multiply(parentGlobal, stepScratch, global)"))
        assertTrue(visit.contains("visitVersion(view.getChildAt(index), depth + 1, global)"))
        val memo = SourceContract.read("geometry/ViewSamplingMatrix.kt").after("private fun memoizedLocalToScreen(")
            .before("internal fun viewStep(")
        assertTrue(memo.contains("SamplingMatrixMath.multiply(parentGlobal, step, global)"))
        assertFalse(visit.contains("sourceToTarget("))
        assertFalse(visit.contains("getValues("))
        val snapshot = source.after("private val snapshotVersion").before("private var unsafe")
        assertTrue(snapshot.contains("SamplingMatrixMath.invert(targetGlobal, targetInverse)"))
    }
}
