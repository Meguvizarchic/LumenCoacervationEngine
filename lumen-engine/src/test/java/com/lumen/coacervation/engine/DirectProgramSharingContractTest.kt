package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局部增强的直接着色程序（`DirectSurfaceProgramApi33`）在进程内只编译一次。共享与「每绑定一份」逐位一致的前提由这里守住：
 * - [draw] 在 `drawRect` 之前写全 PROGRAM 声明的每一个 uniform 与输入着色器，不依赖上一个使用者留下的值；
 * - 写入不放在条件分支里（早退出只发生在第一次写入之前）；
 * - 关闭时把输入换成占位，不经由共享程序留住已关闭绑定的采样位图；
 * - 绘制抛错时丢弃共享程序，恢复「下一个绑定重新编译」的失败隔离。
 */
class DirectProgramSharingContractTest {
    private val source = SourceContract.read("host/DirectSurfaceProgramApi33.kt")
    private val program = source.after("private const val PROGRAM=\"\"\"").before("\"\"\"")
    private val draw = source.after("fun draw(canvas: Canvas").before("override fun close()")

    private fun declaredUniforms(): List<Pair<String, String>> =
        Regex("""^(?:layout\(color\)\s+)?uniform\s+(\w+)\s+(\w+)\s*;""", RegexOption.MULTILINE)
            .findAll(program).map { it.groupValues[1] to it.groupValues[2] }.toList()

    @Test fun programIsCompiledOncePerProcess() {
        assertEquals(1, source.split("RuntimeShader(PROGRAM)").size - 1)
        val acquire = source.after("private fun acquire(): RuntimeShader =").before("\n}")
        assertTrue(acquire.contains("Shared.program as? RuntimeShader?:RuntimeShader(PROGRAM).also {Shared.program=it;counters.shaderCompiled()}"))
        assertTrue(source.contains("private var shader: RuntimeShader? = acquire()"))
        assertFalse(source.contains("companion object"))
        // 共享句柄用 Any 保存：API 33 类型只出现在本隔离类里。
        val shared = source.after("private object Shared {").before("}")
        assertTrue(shared.contains("var program: Any?=null"))
        assertFalse(shared.contains("RuntimeShader"))
    }

    @Test fun drawWritesEveryDeclaredUniformBeforeDrawing() {
        val uniforms = declaredUniforms()
        assertEquals("PROGRAM uniform count changed; re-check sharing", 34, uniforms.size)
        val beforeDraw = draw.before("paint.shader=s;canvas.drawRect(bounds,paint)")
        for ((type, name) in uniforms) {
            val setter = when {
                type == "shader" -> "s.setInputShader(\"$name\","
                type == "half4" -> "s.setColorUniform(\"$name\","
                else -> "s.setFloatUniform(\"$name\","
            }
            // ripple 四组在循环里按下标逐一写入，循环体覆盖 0..3。
            assertTrue("$name is not written by draw()", beforeDraw.contains(setter))
        }
        assertTrue(beforeDraw.contains("for(i in 0..3){"))
        assertTrue(beforeDraw.contains("when(i){0->s.setFloatUniform(\"rippleA\",ripple);1->s.setFloatUniform(\"rippleB\",ripple);2->s.setFloatUniform(\"rippleC\",ripple);else->s.setFloatUniform(\"rippleD\",ripple)}"))
    }

    @Test fun writesAreNotConditional() {
        // 早退出都在第一次写入之前；之后不得再出现 return，也不得把 setter 放进 if 分支。
        val firstWrite = draw.indexOf("s.set")
        assertTrue(firstWrite > 0)
        val afterFirst = draw.substring(firstWrite).before("paint.shader=s;canvas.drawRect(bounds,paint)")
        assertFalse(afterFirst.contains("return"))
        for (line in afterFirst.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("if(") || trimmed.startsWith("if (")) assertFalse(trimmed, trimmed.contains("s.set"))
        }
    }

    @Test fun closeDropsInputsAndFailureDiscardsTheSharedProgram() {
        val close = source.after("override fun close(){").before("fun discard()")
        for (name in listOf("clearInput", "weakInput", "strongInput")) assertTrue(name, close.contains("s.setInputShader(\"$name\",empty)"))
        val discard = source.after("fun discard(){").before("private object Shared")
        assertTrue(discard.contains("if(s!=null&&Shared.program===s)Shared.program=null"))
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        assertTrue(session.contains("}catch(_:Throwable){entry.gpuFailed=true;entry.direct?.discard();entry.direct=null;reason=LumenSurfaceFailure.GPU_FAILED}"))
    }
}
