package com.example.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.model.AspectRatioMode
import com.example.model.RecompConfig
import com.example.model.RuntimeTelemetry
import com.example.model.UpscalerMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class PowerMoonGlRenderer(
    private val onTelemetryUpdate: (RuntimeTelemetry) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var config: RecompConfig = RecompConfig()

    @Volatile
    var renderMode: com.example.model.RuntimeRenderMode = com.example.model.RuntimeRenderMode.SHADED

    @Volatile
    private var pendingCaptureCallback: ((android.graphics.Bitmap) -> Unit)? = null

    fun requestFrameCapture(onCaptured: (android.graphics.Bitmap) -> Unit) {
        pendingCaptureCallback = onCaptured
    }

    @Volatile
    private var inputLeftX = 0f

    @Volatile
    private var inputLeftY = 0f

    @Volatile
    private var inputRightX = 0f

    @Volatile
    private var inputRightY = 0f

    @Volatile
    private var gyroOffsetX = 0f

    @Volatile
    private var gyroOffsetY = 0f

    @Volatile
    private var spinBoostVelocity = 0f

    @Volatile
    private var shockwaveProgress = 0f

    @Volatile
    private var jumpOffset = 0f

    @Volatile
    private var jumpVelocity = 0f

    private var surfaceWidth = 1280
    private var surfaceHeight = 720
    private var fboId = 0
    private var fboColorTex = 0
    private var fboDepthRb = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var sceneProgram = 0
    private var postProgram = 0

    private lateinit var moonBuffer: FloatBuffer
    private lateinit var moonIndexBuffer: ShortBuffer
    private var moonIndexCount = 0

    private lateinit var ringBuffer: FloatBuffer
    private lateinit var ringIndexBuffer: ShortBuffer
    private var ringIndexCount = 0

    private lateinit var starBuffer: FloatBuffer
    private var starCount = 0

    private lateinit var quadBuffer: FloatBuffer

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    private var cameraYaw = 0f
    private var cameraPitch = 18f
    private var moonSpin = 0f
    private var elapsedTime = 0f
    private var lastFrameNanos = System.nanoTime()
    private var fpsAccumulator = 0f
    private var fpsFrameCount = 0

    fun updateInputs(
        lx: Float,
        ly: Float,
        rx: Float,
        ry: Float,
        gx: Float,
        gy: Float
    ) {
        inputLeftX = lx
        inputLeftY = ly
        inputRightX = rx
        inputRightY = ry
        gyroOffsetX = gx
        gyroOffsetY = gy
    }

    fun triggerJumpBoost() {
        if (jumpOffset <= 0.05f) {
            jumpVelocity = 4.2f
        }
        spinBoostVelocity = 12.0f
    }

    fun triggerShockwave() {
        shockwaveProgress = 0.01f
    }

    fun resetCamera() {
        cameraYaw = 0f
        cameraPitch = 18f
        moonSpin = 0f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        sceneProgram = createProgram(SCENE_VERTEX_SHADER, SCENE_FRAGMENT_SHADER)
        postProgram = createProgram(POST_VERTEX_SHADER, POST_FRAGMENT_SHADER)

        buildCrescentMoonMesh()
        buildRingPlatformMesh()
        buildStarfield()
        buildFullscreenQuad()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        ensureFboSize()
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNanos = System.nanoTime()
        val dt = ((frameStartNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = frameStartNanos
        elapsedTime += dt

        val cfg = this.config
        val mode = this.renderMode
        ensureFboSize()

        // Update camera and physics from live gamepad/touch/gyro inputs
        cameraYaw += inputRightX * 115f * dt
        cameraPitch = (cameraPitch + inputRightY * 75f * dt).coerceIn(-15f, 65f)

        val gyroTiltX = if (cfg.gyroMotionCapEnabled) gyroOffsetX * 14f * cfg.gyroSensitivity else 0f
        val gyroTiltY = if (cfg.gyroMotionCapEnabled) gyroOffsetY * 14f * cfg.gyroSensitivity else 0f

        moonSpin += (42f + spinBoostVelocity * 40f) * dt
        spinBoostVelocity = max(0f, spinBoostVelocity - 14f * dt)

        if (jumpOffset > 0f || jumpVelocity != 0f) {
            jumpOffset += jumpVelocity * dt
            jumpVelocity -= 11.5f * dt
            if (jumpOffset <= 0f) {
                jumpOffset = 0f
                jumpVelocity = 0f
            }
        }

        if (shockwaveProgress > 0f) {
            shockwaveProgress += dt * 1.45f
            if (shockwaveProgress >= 1f) shockwaveProgress = 0f
        }

        var drawCalls = 0
        val drawPrimitive = if (mode == com.example.model.RuntimeRenderMode.WIREFRAME) {
            GLES30.GL_LINES
        } else {
            GLES30.GL_TRIANGLES
        }
        val auraBoost = if (mode == com.example.model.RuntimeRenderMode.NIGHT_AURA) 1.55f else 1.0f

        // 1. Render 3D scene to scaled offscreen FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, fboWidth, fboHeight)
        GLES30.glClearColor(0.035f, 0.045f, 0.085f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val effectiveAspect = computeViewportAspect(cfg.aspectRatio)
        Matrix.perspectiveM(projMatrix, 0, 48f, effectiveAspect, 0.1f, 80f)

        val yawRad = Math.toRadians((cameraYaw + gyroTiltY).toDouble())
        val pitchRad = Math.toRadians((cameraPitch + gyroTiltX).toDouble())
        val camDist = 5.4f
        val eyeX = (camDist * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (camDist * sin(pitchRad) + 0.6f).toFloat()
        val eyeZ = (camDist * cos(pitchRad) * cos(yawRad)).toFloat()
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0.45f, 0f, 0f, 1f, 0f)

        GLES30.glUseProgram(sceneProgram)
        val uMvpLoc = GLES30.glGetUniformLocation(sceneProgram, "uMvpMatrix")
        val uModelLoc = GLES30.glGetUniformLocation(sceneProgram, "uModelMatrix")
        val uColorLoc = GLES30.glGetUniformLocation(sceneProgram, "uBaseColor")
        val uGlowLoc = GLES30.glGetUniformLocation(sceneProgram, "uGlowStrength")
        val uTimeLoc = GLES30.glGetUniformLocation(sceneProgram, "uTime")
        val uCamPosLoc = GLES30.glGetUniformLocation(sceneProgram, "uCameraPos")
        GLES30.glUniform1f(uTimeLoc, elapsedTime)
        GLES30.glUniform3f(uCamPosLoc, eyeX, eyeY, eyeZ)

        // Draw orbiting starfield
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, elapsedTime * 2.5f, 0f, 1f, 0f)
        computeMvp()
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        GLES30.glUniform3f(uColorLoc, 0.72f, 0.88f, 1.0f)
        GLES30.glUniform1f(uGlowLoc, 1.25f * auraBoost)
        bindVertexAttributes(starBuffer)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starCount)
        drawCalls++

        // Draw Odyssey crimson floating ring platform (tilted by Left Stick)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, inputLeftX * 0.45f, -1.05f, inputLeftY * 0.45f)
        Matrix.rotateM(modelMatrix, 0, inputLeftY * 14f, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, -inputLeftX * 14f, 0f, 0f, 1f)
        Matrix.rotateM(modelMatrix, 0, -elapsedTime * 18f, 0f, 1f, 0f)
        computeMvp()
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        if (mode == com.example.model.RuntimeRenderMode.NIGHT_AURA) {
            GLES30.glUniform3f(uColorLoc, 0.22f, 0.74f, 0.97f)
        } else {
            GLES30.glUniform3f(uColorLoc, 0.90f, 0.18f, 0.24f)
        }
        GLES30.glUniform1f(uGlowLoc, 0.35f * auraBoost)
        bindVertexAttributes(ringBuffer)
        GLES30.glDrawElements(drawPrimitive, ringIndexCount, GLES30.GL_UNSIGNED_SHORT, ringIndexBuffer)
        drawCalls++

        // Draw Cappy Cyan Shockwave Ring if active
        if (shockwaveProgress > 0f) {
            val scale = 0.8f + shockwaveProgress * 3.4f
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, inputLeftX * 0.45f, -0.7f, inputLeftY * 0.45f)
            Matrix.scaleM(modelMatrix, 0, scale, 0.35f, scale)
            computeMvp()
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
            GLES30.glUniform3f(uColorLoc, 0.22f, 0.74f, 0.97f)
            GLES30.glUniform1f(uGlowLoc, 1.4f * (1f - shockwaveProgress))
            bindVertexAttributes(ringBuffer)
            GLES30.glDrawElements(drawPrimitive, ringIndexCount, GLES30.GL_UNSIGNED_SHORT, ringIndexBuffer)
            drawCalls++
        }

        // Draw Golden Crescent Power Moon
        val floatBob = sin(elapsedTime * 2.4f) * 0.14f + jumpOffset
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, inputLeftX * 0.55f, 0.45f + floatBob, inputLeftY * 0.55f)
        Matrix.rotateM(modelMatrix, 0, moonSpin, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, 12f * sin(elapsedTime * 1.4f), 0f, 0f, 1f)
        computeMvp()
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        GLES30.glUniform3f(uColorLoc, 1.0f, 0.82f, 0.23f)
        GLES30.glUniform1f(uGlowLoc, (0.65f + spinBoostVelocity * 0.05f) * auraBoost)
        bindVertexAttributes(moonBuffer)
        GLES30.glDrawElements(drawPrimitive, moonIndexCount, GLES30.GL_UNSIGNED_SHORT, moonIndexBuffer)
        drawCalls++

        // 2. Blit FBO to screen with Aspect Ratio, FSR/SMAA post-pass, and Color Banding Dithering
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0.02f, 0.025f, 0.045f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val (vpX, vpY, vpW, vpH) = computeAspectRect(surfaceWidth, surfaceHeight, cfg.aspectRatio)
        GLES30.glViewport(vpX, vpY, vpW, vpH)

        GLES30.glUseProgram(postProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboColorTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(postProgram, "uTexture"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(postProgram, "uTexelSize"), 1f / fboWidth, 1f / fboHeight)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(postProgram, "uUpscalerMode"),
            when (cfg.upscalerMode) {
                UpscalerMode.NONE -> 0
                UpscalerMode.SMAA -> 1
                UpscalerMode.AMD_FSR_1 -> 2
            }
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(postProgram, "uSharpness"), cfg.fsrSharpness)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(postProgram, "uBandingFix"), if (cfg.colorBandingFix) 1 else 0)

        quadBuffer.position(0)
        val posLoc = GLES30.glGetAttribLocation(postProgram, "aPos")
        val uvLoc = GLES30.glGetAttribLocation(postProgram, "aUv")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(uvLoc)
        GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(uvLoc)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        drawCalls++

        pendingCaptureCallback?.let { callback ->
            pendingCaptureCallback = null
            val w = surfaceWidth
            val h = surfaceHeight
            val pixelBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuf)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            pixelBuf.rewind()
            bmp.copyPixelsFromBuffer(pixelBuf)
            val flip = android.graphics.Matrix().apply { postScale(1f, -1f, w / 2f, h / 2f) }
            val upright = android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h, flip, true)
            bmp.recycle()
            callback(upright)
        }

        // 3. Target FPS Pacing (respects 30/60/120 FPS setting)
        val targetFps = cfg.targetFramerate.fps.coerceAtLeast(30)
        val targetFrameNanos = 1_000_000_000L / targetFps
        val elapsedNanos = System.nanoTime() - frameStartNanos
        val sleepNanos = targetFrameNanos - elapsedNanos
        if (sleepNanos > 1_000_000L) {
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (sleepNanos % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
            }
        }

        // 4. Report live telemetry every ~0.35s
        fpsAccumulator += dt
        fpsFrameCount++
        if (fpsAccumulator >= 0.35f) {
            val fps = fpsFrameCount / fpsAccumulator
            val ms = 1000f / max(1f, fps)
            onTelemetryUpdate(
                RuntimeTelemetry(
                    fps = fps,
                    frameTimeMs = ms,
                    renderWidth = fboWidth,
                    renderHeight = fboHeight,
                    drawCalls = drawCalls
                )
            )
            fpsAccumulator = 0f
            fpsFrameCount = 0
        }
    }

    private fun computeViewportAspect(mode: AspectRatioMode): Float {
        val screenAspect = surfaceWidth.toFloat() / max(1, surfaceHeight)
        return when (mode) {
            AspectRatioMode.RATIO_16_9 -> min(screenAspect, 16f / 9f)
            AspectRatioMode.RATIO_16_10 -> min(screenAspect, 16f / 10f)
            AspectRatioMode.RATIO_ULTRAWIDE_21_9 -> screenAspect
        }
    }

    private fun computeAspectRect(w: Int, h: Int, mode: AspectRatioMode): IntArray {
        if (mode == AspectRatioMode.RATIO_ULTRAWIDE_21_9) return intArrayOf(0, 0, w, h)
        val target = mode.ratio
        val current = w.toFloat() / max(1, h)
        return if (current > target) {
            val rw = (h * target).toInt()
            intArrayOf((w - rw) / 2, 0, rw, h)
        } else {
            val rh = (w / target).toInt()
            intArrayOf(0, (h - rh) / 2, w, rh)
        }
    }

    private fun ensureFboSize() {
        val scale = config.resolutionScale.factor
        val desiredW = max(160, (surfaceWidth * scale).toInt().coerceAtMost(3840))
        val desiredH = max(90, (surfaceHeight * scale).toInt().coerceAtMost(2160))
        if (fboId != 0 && desiredW == fboWidth && desiredH == fboHeight) return

        if (fboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(fboColorTex), 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(fboDepthRb), 0)
        }

        fboWidth = desiredW
        fboHeight = desiredH

        val fbos = IntArray(1)
        val texs = IntArray(1)
        val rbs = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        GLES30.glGenTextures(1, texs, 0)
        GLES30.glGenRenderbuffers(1, rbs, 0)

        fboId = fbos[0]
        fboColorTex = texs[0]
        fboDepthRb = rbs[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboColorTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, fboWidth, fboHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, fboDepthRb)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, fboWidth, fboHeight)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboColorTex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, fboDepthRb)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun computeMvp() {
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, tempMatrix, 0)
    }

    private fun bindVertexAttributes(buffer: FloatBuffer) {
        val posLoc = GLES30.glGetAttribLocation(sceneProgram, "aPosition")
        val normLoc = GLES30.glGetAttribLocation(sceneProgram, "aNormal")
        buffer.position(0)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 24, buffer)
        buffer.position(3)
        GLES30.glEnableVertexAttribArray(normLoc)
        GLES30.glVertexAttribPointer(normLoc, 3, GLES30.GL_FLOAT, false, 24, buffer)
    }

    private fun buildCrescentMoonMesh() {
        val segments = 36
        val verts = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        val outerR = 1.05f
        val innerR = 0.72f
        val halfDepth = 0.22f
        val startAngle = -Math.PI * 0.68
        val endAngle = Math.PI * 0.68

        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val angle = startAngle + (endAngle - startAngle) * t
            val thicknessProfile = sin(t * PI).toFloat().coerceAtLeast(0.06f)

            val ox = (-cos(angle) * outerR).toFloat()
            val oy = (sin(angle) * outerR).toFloat()

            val ix = (-cos(angle) * (outerR - (outerR - innerR) * thicknessProfile) + 0.22f).toFloat()
            val iy = (sin(angle) * (outerR * 0.92f)).toFloat()

            val nxOut = -cos(angle).toFloat()
            val nyOut = sin(angle).toFloat()

            // 4 vertices per ring slice: outerFront, outerBack, innerFront, innerBack
            verts.addAll(listOf(ox, oy, halfDepth * thicknessProfile, nxOut, nyOut, 0.55f))
            verts.addAll(listOf(ox, oy, -halfDepth * thicknessProfile, nxOut, nyOut, -0.55f))
            verts.addAll(listOf(ix, iy, halfDepth * 0.65f * thicknessProfile, -nxOut, -nyOut, 0.55f))
            verts.addAll(listOf(ix, iy, -halfDepth * 0.65f * thicknessProfile, -nxOut, -nyOut, -0.55f))
        }

        for (i in 0 until segments) {
            val base = (i * 4).toShort()
            val next = ((i + 1) * 4).toShort()
            // Front face
            indices.addAll(listOf(base, next, (base + 2).toShort(), (base + 2).toShort(), next, (next + 2).toShort()))
            // Back face
            indices.addAll(listOf((base + 1).toShort(), (base + 3).toShort(), (next + 1).toShort(), (base + 3).toShort(), (next + 3).toShort(), (next + 1).toShort()))
            // Outer rim
            indices.addAll(listOf(base, (base + 1).toShort(), next, (base + 1).toShort(), (next + 1).toShort(), next))
            // Inner rim
            indices.addAll(listOf((base + 2).toShort(), (next + 2).toShort(), (base + 3).toShort(), (base + 3).toShort(), (next + 2).toShort(), (next + 3).toShort()))
        }

        moonBuffer = toFloatBuffer(verts.toFloatArray())
        moonIndexBuffer = toShortBuffer(indices.toShortArray())
        moonIndexCount = indices.size
    }

    private fun buildRingPlatformMesh() {
        val majorSegs = 40
        val minorSegs = 12
        val majorR = 1.45f
        val minorR = 0.14f
        val verts = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        for (i in 0..majorSegs) {
            val u = i.toFloat() / majorSegs * 2f * PI.toFloat()
            val cu = cos(u)
            val su = sin(u)
            for (j in 0..minorSegs) {
                val v = j.toFloat() / minorSegs * 2f * PI.toFloat()
                val cv = cos(v)
                val sv = sin(v)
                val r = majorR + minorR * cv
                val x = r * cu
                val y = minorR * sv
                val z = r * su
                verts.addAll(listOf(x, y, z, cv * cu, sv, cv * su))
            }
        }

        val stride = minorSegs + 1
        for (i in 0 until majorSegs) {
            for (j in 0 until minorSegs) {
                val a = (i * stride + j).toShort()
                val b = ((i + 1) * stride + j).toShort()
                val c = ((i + 1) * stride + j + 1).toShort()
                val d = (i * stride + j + 1).toShort()
                indices.addAll(listOf(a, b, d, b, c, d))
            }
        }

        ringBuffer = toFloatBuffer(verts.toFloatArray())
        ringIndexBuffer = toShortBuffer(indices.toShortArray())
        ringIndexCount = indices.size
    }

    private fun buildStarfield() {
        val count = 180
        val data = FloatArray(count * 6)
        for (i in 0 until count) {
            val theta = (i * 2.39996f)
            val radius = 5.5f + (i % 9) * 1.4f
            val y = ((i % 19) - 9) * 0.65f
            data[i * 6] = cos(theta) * radius
            data[i * 6 + 1] = y
            data[i * 6 + 2] = sin(theta) * radius
            data[i * 6 + 3] = 0f
            data[i * 6 + 4] = 1f
            data[i * 6 + 5] = 0f
        }
        starBuffer = toFloatBuffer(data)
        starCount = count
    }

    private fun buildFullscreenQuad() {
        val quad = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        quadBuffer = toFloatBuffer(quad)
    }

    private fun toFloatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr)
            position(0)
        }

    private fun toShortBuffer(arr: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(arr.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(arr)
            position(0)
        }

    private fun createProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSource)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        return shader
    }

    companion object {
        const val SCENE_VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            out vec3 vWorldPos;
            out vec3 vNormal;
            void main() {
                vec4 worldPos = uModelMatrix * vec4(aPosition, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                gl_PointSize = 4.5;
            }
        """

        const val SCENE_FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec3 vWorldPos;
            in vec3 vNormal;
            uniform vec3 uBaseColor;
            uniform float uGlowStrength;
            uniform float uTime;
            uniform vec3 uCameraPos;
            out vec4 fragColor;
            void main() {
                vec3 N = normalize(vNormal);
                vec3 L = normalize(vec3(2.5, 4.0, 3.0));
                vec3 V = normalize(uCameraPos - vWorldPos);
                vec3 H = normalize(L + V);
                float diff = max(dot(N, L), 0.18);
                float spec = pow(max(dot(N, H), 0.0), 36.0);
                float rim = pow(1.0 - max(dot(N, V), 0.0), 2.5);
                float pulse = 0.85 + 0.15 * sin(uTime * 3.0);
                vec3 col = uBaseColor * diff + vec3(1.0, 0.96, 0.82) * spec * 0.75 + uBaseColor * rim * uGlowStrength * pulse;
                fragColor = vec4(col, 1.0);
            }
        """

        const val POST_VERTEX_SHADER = """#version 300 es
            in vec2 aPos;
            in vec2 aUv;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val POST_FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform int uUpscalerMode;
            uniform float uSharpness;
            uniform int uBandingFix;
            out vec4 fragColor;
            void main() {
                vec3 c = texture(uTexture, vUv).rgb;
                if (uUpscalerMode == 2) {
                    vec3 n = texture(uTexture, vUv + vec2(0.0, uTexelSize.y)).rgb;
                    vec3 s = texture(uTexture, vUv - vec2(0.0, uTexelSize.y)).rgb;
                    vec3 e = texture(uTexture, vUv + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 w = texture(uTexture, vUv - vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 blur = (n + s + e + w) * 0.25;
                    c = clamp(c + (c - blur) * (uSharpness * 1.6), 0.0, 1.0);
                } else if (uUpscalerMode == 1) {
                    vec3 nw = texture(uTexture, vUv + vec2(-uTexelSize.x, uTexelSize.y) * 0.5).rgb;
                    vec3 se = texture(uTexture, vUv + vec2(uTexelSize.x, -uTexelSize.y) * 0.5).rgb;
                    c = mix(c, (nw + se) * 0.5, 0.28);
                }
                if (uBandingFix == 1) {
                    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                    c += dither / 255.0;
                }
                fragColor = vec4(c, 1.0);
            }
        """
    }
}
