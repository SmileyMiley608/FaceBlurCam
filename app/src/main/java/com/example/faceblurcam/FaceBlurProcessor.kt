package com.example.faceblurcam

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Receives every camera frame on the GPU, blurs the regions that contain faces,
 * and writes the result to the preview and video-recording surfaces. Because
 * the blur happens before the encoder, the saved file never has a clear face.
 *
 * Fail-closed: until CameraX has told us how sensor coordinates map onto the
 * frame, the WHOLE frame is blurred rather than risk showing a face.
 */
class FaceBlurProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        const val TARGETS = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
        const val MAX_FACES = 8

        // Keep blurring this long after the last detection (head turns, missed frames).
        private const val HOLD_NANOS = 400_000_000L

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTexMatrix * aTex).xy;
            }
        """

        // vUv is the sampled coordinate in the camera buffer, normalized, with
        // (0,0) at the buffer's top-left. Face rects use the same space.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTex;
            uniform int uCount;
            uniform float uAll;
            uniform vec4 uFaces[8];
            varying vec2 vUv;

            void main() {
                float hit = uAll;
                vec2 spread = vec2(0.03);
                if (uAll < 0.5) {
                    for (int i = 0; i < 8; i++) {
                        if (i >= uCount) break;
                        vec4 f = uFaces[i];
                        vec2 c = 0.5 * (f.xy + f.zw);
                        vec2 h = max(0.5 * (f.zw - f.xy), vec2(0.0001));
                        vec2 d = (vUv - c) / h;
                        if (dot(d, d) <= 1.0) {
                            hit = 1.0;
                            spread = h * 0.4;   // blur strength: fraction of face half-size
                        }
                    }
                }
                if (hit < 0.5) {
                    gl_FragColor = texture2D(uTex, vUv);
                    return;
                }
                vec4 sum = vec4(0.0);
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        sum += texture2D(uTex, vUv + vec2(float(x), float(y)) * spread);
                    }
                }
                gl_FragColor = sum / 25.0;
            }
        """
    }

    private class FaceData(val count: Int, val rects: FloatArray, val blurAll: Boolean)

    private val glThread = HandlerThread("FaceBlurGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    /** Run CameraX callbacks on the GL thread so all GL/EGL calls stay on one thread. */
    val glExecutor = Executor { glHandler.post(it) }

    // EGL / GL state (GL thread only)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uTex = 0
    private var uCount = 0
    private var uAll = 0
    private var uFaces = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var inputSize = Size(1, 1)
    private var sensorToBuffer: Matrix? = null
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private var released = false

    private val vertexBuf = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texBuf = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    // Written from the analyzer thread, read on the GL thread.
    @Volatile private var sensorFaces: List<RectF> = emptyList()
    @Volatile private var lastFaceNanos = 0L

    init {
        glHandler.post { initGl() }
    }

    /** Face boxes in SENSOR coordinates (what MlKitAnalyzer gives with COORDINATE_SYSTEM_SENSOR). */
    fun updateFaces(boxes: List<RectF>) {
        if (boxes.isEmpty()) return
        sensorFaces = boxes
        lastFaceNanos = System.nanoTime()
    }

    fun release() {
        glHandler.post {
            released = true
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                outputs.values.forEach { EGL14.eglDestroySurface(display, it) }
                outputs.clear()
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
            glThread.quitSafely()
        }
    }

    // ---- SurfaceProcessor -------------------------------------------------

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        inputSize = request.resolution
        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(inputSize.width, inputSize.height)
        st.setOnFrameAvailableListener(this, glHandler)
        val surface = Surface(st)
        surfaceTexture = st

        request.setTransformationInfoListener(glExecutor) { info ->
            sensorToBuffer = Matrix(info.sensorToBufferTransform)
        }
        request.provideSurface(surface, glExecutor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            if (surfaceTexture === st) surfaceTexture = null
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released) {
            output.close()
            return
        }
        val surface = output.getSurface(glExecutor) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
            output.close()
        }
        val eglSurface = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        outputs[output] = eglSurface
    }

    // ---- Rendering --------------------------------------------------------

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released || st !== surfaceTexture) return

        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val timestamp = st.timestamp
        val faces = computeFaces()

        for ((output, eglSurface) in outputs.entries.toList()) {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            output.updateTransformMatrix(outMatrix, texMatrix)
            GLES20.glViewport(0, 0, output.size.width, output.size.height)
            draw(faces)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestamp)
            EGL14.eglSwapBuffers(display, eglSurface)
        }
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun computeFaces(): FaceData {
        val rects = FloatArray(MAX_FACES * 4)
        val m = sensorToBuffer ?: return FaceData(0, rects, true) // fail closed
        if (System.nanoTime() - lastFaceNanos > HOLD_NANOS) return FaceData(0, rects, false)

        val w = inputSize.width.toFloat()
        val h = inputSize.height.toFloat()
        val tmp = RectF()
        var n = 0
        for (r in sensorFaces) {
            if (n >= MAX_FACES) break
            m.mapRect(tmp, r)
            rects[n * 4] = tmp.left / w
            rects[n * 4 + 1] = tmp.top / h
            rects[n * 4 + 2] = tmp.right / w
            rects[n * 4 + 3] = tmp.bottom / h
            n++
        }
        return FaceData(n, rects, false)
    }

    private fun draw(d: FaceData) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, outMatrix, 0)
        GLES20.glUniform1i(uCount, d.count)
        GLES20.glUniform1f(uAll, if (d.blurAll) 1f else 0f)
        GLES20.glUniform4fv(uFaces, MAX_FACES, d.rects, 0)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    // ---- GL setup ---------------------------------------------------------

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        config = configs[0]

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uCount = GLES20.glGetUniformLocation(program, "uCount")
        uAll = GLES20.glGetUniformLocation(program, "uAll")
        uFaces = GLES20.glGetUniformLocation(program, "uFaces[0]")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $log")
        }
        return p
    }

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a)
            position(0)
        }
}
