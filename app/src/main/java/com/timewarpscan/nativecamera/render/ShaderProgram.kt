package com.timewarpscan.nativecamera.render

import android.opengl.GLES20
import android.util.Log

/**
 * OpenGL ES 2.0 shader compilation and program linking utilities.
 *
 * Contains GLSL source constants for all shader programs used by the renderer:
 *   1. Camera shader  — samples OES external texture (CameraX preview)
 *   2. Texture shader — samples standard 2D texture (composite FBO)
 *   3. Color shader   — renders solid-color geometry (scan line)
 */
object ShaderProgram {

    private const val TAG = "ShaderProgram"

    // -----------------------------------------------------------------------
    // 1. Camera shader — external OES texture from CameraX SurfaceTexture
    // -----------------------------------------------------------------------

    /** Vertex shader shared by camera and texture programs.
     *  Applies a texture matrix to support SurfaceTexture coordinate transforms,
     *  plus a horizontal flip for front-camera mirroring. */
    const val CAMERA_VERTEX = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            // Flip Y from GL coords (Y=0 bottom) to Android coords (Y=0 top)
            // before applying SurfaceTexture transform, then mirror X for front camera
            vec2 flipped = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            vec2 tc = (uTexMatrix * vec4(flipped, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            vTexCoord = tc;
        }
    """

    /** Fragment shader for OES external texture (camera frames). */
    const val CAMERA_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // -----------------------------------------------------------------------
    // 2. Texture shader — standard sampler2D for composite FBO texture
    // -----------------------------------------------------------------------

    /** Simple vertex shader for full-screen quad with 2D texture. */
    const val TEXTURE_VERTEX = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = aTexCoord;
        }
    """

    /** Fragment shader sampling a standard 2D texture (composite). */
    const val TEXTURE_FRAGMENT = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // -----------------------------------------------------------------------
    // 3. Effect shaders — camera effects applied to OES external texture
    //    Each effect remaps screen UV coordinates before sampling, using
    //    a helper function toOES() that applies the SurfaceTexture transform.
    // -----------------------------------------------------------------------

    /** Shared vertex shader for all camera effects.
     *  Passes raw quad UV (0-1) as vScreenUV — the fragment shader handles
     *  UV manipulation and OES coordinate conversion. */
    const val EFFECT_VERTEX = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vScreenUV;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            // Flip Y so effects work in screen-space (Y=0 at top)
            vScreenUV = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
        }
    """

    /** Swirl / vortex distortion centred on screen. */
    const val EFFECT_SWIRL_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        vec2 toOES(vec2 uv) {
            vec2 tc = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            return tc;
        }
        void main() {
            vec2 uv = vScreenUV;
            vec2 center = vec2(0.5, 0.5);
            vec2 delta = uv - center;
            float dist = length(delta);
            float angle = atan(delta.y, delta.x);
            float swirl = 3.0 * (1.0 - smoothstep(0.0, 0.5, dist));
            angle += swirl;
            uv = center + dist * vec2(cos(angle), sin(angle));
            uv = clamp(uv, 0.0, 1.0);
            gl_FragColor = texture2D(uTexture, toOES(uv));
        }
    """

    /** 3×3 tiled grid — repeats the camera feed in a grid. */
    const val EFFECT_GRID_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        vec2 toOES(vec2 uv) {
            vec2 tc = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            return tc;
        }
        void main() {
            vec2 uv = fract(vScreenUV * 3.0);
            gl_FragColor = texture2D(uTexture, toOES(uv));
        }
    """

    /** Horizontal mirror — left half reflected to right. */
    const val EFFECT_MIRROR_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        vec2 toOES(vec2 uv) {
            vec2 tc = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            return tc;
        }
        void main() {
            vec2 uv = vScreenUV;
            uv.x = uv.x > 0.5 ? 1.0 - uv.x : uv.x;
            gl_FragColor = texture2D(uTexture, toOES(uv));
        }
    """

    /** Double — vertically duplicated (top/bottom mirror). */
    const val EFFECT_DOUBLE_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        vec2 toOES(vec2 uv) {
            vec2 tc = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            return tc;
        }
        void main() {
            vec2 uv = vScreenUV;
            uv.y = uv.y > 0.5 ? 1.0 - uv.y : uv.y;
            uv.y = uv.y * 2.0;
            gl_FragColor = texture2D(uTexture, toOES(uv));
        }
    """

    /** Chromatic aberration — RGB channel offset. */
    const val EFFECT_WATERFALL_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        vec2 toOES(vec2 uv) {
            vec2 tc = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            return tc;
        }
        void main() {
            vec2 uv = vScreenUV;
            float offset = 0.008;
            vec2 rUV = clamp(uv + vec2(offset, 0.0), 0.0, 1.0);
            vec2 bUV = clamp(uv - vec2(offset, 0.0), 0.0, 1.0);
            float r = texture2D(uTexture, toOES(rUV)).r;
            float g = texture2D(uTexture, toOES(uv)).g;
            float b = texture2D(uTexture, toOES(bUV)).b;
            gl_FragColor = vec4(r, g, b, 1.0);
        }
    """

    /** Split — top/bottom halves offset horizontally. */
    const val EFFECT_SPLIT_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        vec2 toOES(vec2 uv) {
            vec2 tc = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            return tc;
        }
        void main() {
            vec2 uv = vScreenUV;
            if (uv.y > 0.5) {
                uv.x = uv.x + 0.1;
            } else {
                uv.x = uv.x - 0.1;
            }
            uv = clamp(uv, 0.0, 1.0);
            gl_FragColor = texture2D(uTexture, toOES(uv));
        }
    """

    /** Single — grayscale / monochrome. */
    const val EFFECT_SINGLE_FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        vec2 toOES(vec2 uv) {
            vec2 tc = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            tc.x = 1.0 - tc.x;
            return tc;
        }
        void main() {
            vec4 color = texture2D(uTexture, toOES(vScreenUV));
            float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
            gl_FragColor = vec4(vec3(gray), 1.0);
        }
    """

    // -----------------------------------------------------------------------
    // 4. Color shader — solid color for scan line quad
    // -----------------------------------------------------------------------

    const val COLOR_VERTEX = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
        }
    """

    const val COLOR_FRAGMENT = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """

    // -----------------------------------------------------------------------
    // Compilation utilities
    // -----------------------------------------------------------------------

    /**
     * Compile a single shader (vertex or fragment).
     * @param type GLES20.GL_VERTEX_SHADER or GLES20.GL_FRAGMENT_SHADER
     * @param source GLSL source string
     * @return shader handle, or 0 on failure
     */
    fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "glCreateShader failed for type $type")
            return 0
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile error: $info")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * Link a vertex + fragment shader into a program.
     * @return program handle, or 0 on failure
     */
    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }

        val program = GLES20.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "glCreateProgram failed")
            return 0
        }

        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Program link error: $info")
            GLES20.glDeleteProgram(program)
            return 0
        }

        // Shaders are attached; safe to flag for deletion (freed when program is deleted)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        return program
    }
}
