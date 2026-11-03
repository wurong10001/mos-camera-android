package org.mos.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;

import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.Executor;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GlPreviewView extends GLSurfaceView implements Preview.SurfaceProvider {
    private final PreviewRenderer renderer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> mainHandler.post(command);

    public GlPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new PreviewRenderer(this, mainExecutor);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onSurfaceRequested(SurfaceRequest request) {
        request.setTransformationInfoListener(mainExecutor,
                info -> renderer.setRotationDegrees(info.getRotationDegrees()));
        queueEvent(() -> renderer.provideSurface(request));
    }

    public void setColorAdjustments(float exposure, float contrast, float saturation) {
        renderer.setColorAdjustments(exposure, contrast, saturation);
    }

    private static final class PreviewRenderer implements GLSurfaceView.Renderer {
        private static final String TAG = "MosCameraGL";
        private static final float[] POSITIONS = {
                -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        };
        private static final float[] TEX_COORDS = {
                0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
        };
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n"
                        + "attribute vec2 aTexCoord;\n"
                        + "uniform mat4 uTexMatrix;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "  gl_Position = aPosition;\n"
                        + "  vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n"
                        + "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n"
                        + "uniform samplerExternalOES uTexture;\n"
                        + "uniform mat4 uColorMatrix;\n"
                        + "uniform vec4 uColorOffset;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "  vec4 color = texture2D(uTexture, vTexCoord);\n"
                        + "  gl_FragColor = clamp(uColorMatrix * color + uColorOffset, "
                        + "0.0, 1.0);\n"
                        + "}\n";

        private final GlPreviewView view;
        private final Executor resultExecutor;
        private final FloatBuffer positionBuffer = floatBuffer(POSITIONS);
        private final FloatBuffer texCoordBuffer = floatBuffer(TEX_COORDS);
        private final float[] surfaceTextureMatrix = new float[16];
        private final float[] rotationMatrix = new float[16];
        private final float[] textureMatrix = new float[16];
        private final float[] colorMatrix = new float[16];
        private final float[] colorOffset = new float[4];
        private SurfaceRequest pendingRequest;
        private SurfaceRequest activeRequest;
        private Surface cameraSurface;
        private SurfaceTexture surfaceTexture;
        private int textureId;
        private int program;
        private int positionLocation;
        private int texCoordLocation;
        private int textureMatrixLocation;
        private int colorMatrixLocation;
        private int colorOffsetLocation;
        private int textureLocation;
        private volatile boolean frameAvailable;
        private float exposure;
        private float contrast;
        private float saturation = 1f;
        private int rotationDegrees;

        PreviewRenderer(GlPreviewView view, Executor resultExecutor) {
            this.view = view;
            this.resultExecutor = resultExecutor;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            if (cameraSurface != null) {
                cameraSurface.release();
                cameraSurface = null;
                activeRequest = null;
            }
            if (surfaceTexture != null) {
                surfaceTexture.setOnFrameAvailableListener(null);
                surfaceTexture.release();
            }

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                Log.e(TAG, "Could not create preview shader program");
                return;
            }

            positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
            textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix");
            colorMatrixLocation = GLES20.glGetUniformLocation(program, "uColorMatrix");
            colorOffsetLocation = GLES20.glGetUniformLocation(program, "uColorOffset");
            textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
            textureId = createExternalTexture();
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(ignored -> {
                frameAvailable = true;
                view.requestRender();
            });
            providePendingSurface();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program == 0 || surfaceTexture == null) {
                return;
            }
            if (frameAvailable) {
                surfaceTexture.updateTexImage();
                frameAvailable = false;
            }

            surfaceTexture.getTransformMatrix(surfaceTextureMatrix);
            Matrix.setIdentityM(rotationMatrix, 0);
            Matrix.translateM(rotationMatrix, 0, 0.5f, 0.5f, 0f);
            Matrix.rotateM(rotationMatrix, 0, rotationDegrees, 0f, 0f, 1f);
            Matrix.translateM(rotationMatrix, 0, -0.5f, -0.5f, 0f);
            Matrix.multiplyMM(textureMatrix, 0, surfaceTextureMatrix, 0,
                    rotationMatrix, 0);
            updateColorMatrix();

            GLES20.glUseProgram(program);
            positionBuffer.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT,
                    false, 0, positionBuffer);
            texCoordBuffer.position(0);
            GLES20.glEnableVertexAttribArray(texCoordLocation);
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT,
                    false, 0, texCoordBuffer);
            GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false,
                    textureMatrix, 0);
            GLES20.glUniformMatrix4fv(colorMatrixLocation, 1, false,
                    colorMatrix, 0);
            GLES20.glUniform4fv(colorOffsetLocation, 1, colorOffset, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionLocation);
            GLES20.glDisableVertexAttribArray(texCoordLocation);
        }

        void provideSurface(SurfaceRequest request) {
            if (surfaceTexture == null) {
                if (pendingRequest != null) {
                    pendingRequest.willNotProvideSurface();
                }
                pendingRequest = request;
                return;
            }
            if (activeRequest != null) {
                if (pendingRequest != null) {
                    pendingRequest.willNotProvideSurface();
                }
                pendingRequest = request;
                return;
            }
            provideSurfaceNow(request);
        }

        void setRotationDegrees(int degrees) {
            view.queueEvent(() -> rotationDegrees = degrees);
            view.requestRender();
        }

        void setColorAdjustments(float exposureValue, float contrastValue,
                                 float saturationValue) {
            float safeExposure = clamp(exposureValue, -2f, 2f);
            float safeContrast = clamp(contrastValue, -1f, 1f);
            float safeSaturation = clamp(saturationValue, 0f, 2f);
            view.queueEvent(() -> {
                exposure = safeExposure;
                contrast = safeContrast;
                saturation = safeSaturation;
            });
            view.requestRender();
        }

        private void providePendingSurface() {
            if (pendingRequest != null && activeRequest == null) {
                SurfaceRequest request = pendingRequest;
                pendingRequest = null;
                provideSurfaceNow(request);
            }
        }

        private void provideSurfaceNow(SurfaceRequest request) {
            android.util.Size resolution = request.getResolution();
            surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            cameraSurface = new Surface(surfaceTexture);
            activeRequest = request;
            Surface providedSurface = cameraSurface;
            request.provideSurface(providedSurface, resultExecutor, result -> {
                if (result.getResultCode()
                        != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                    Log.w(TAG, "CameraX surface result: " + result.getResultCode());
                }
                providedSurface.release();
                view.queueEvent(() -> {
                    if (activeRequest == request) {
                        activeRequest = null;
                        cameraSurface = null;
                        providePendingSurface();
                    }
                });
            });
        }

        private void updateColorMatrix() {
            float exposureScale = (float) Math.pow(2.0, exposure);
            float contrastScale = 1f + contrast;
            float scale = exposureScale * contrastScale;
            float bias = exposureScale * (0.5f - 0.5f * contrastScale);

            float redRed = 0.213f + 0.787f * saturation;
            float redGreen = 0.715f - 0.715f * saturation;
            float redBlue = 0.072f - 0.072f * saturation;
            float greenRed = 0.213f - 0.213f * saturation;
            float greenGreen = 0.715f + 0.285f * saturation;
            float greenBlue = 0.072f - 0.072f * saturation;
            float blueRed = 0.213f - 0.213f * saturation;
            float blueGreen = 0.715f - 0.715f * saturation;
            float blueBlue = 0.072f + 0.928f * saturation;

            colorMatrix[0] = redRed * scale;
            colorMatrix[1] = greenRed * scale;
            colorMatrix[2] = blueRed * scale;
            colorMatrix[3] = 0f;
            colorMatrix[4] = redGreen * scale;
            colorMatrix[5] = greenGreen * scale;
            colorMatrix[6] = blueGreen * scale;
            colorMatrix[7] = 0f;
            colorMatrix[8] = redBlue * scale;
            colorMatrix[9] = greenBlue * scale;
            colorMatrix[10] = blueBlue * scale;
            colorMatrix[11] = 0f;
            colorMatrix[12] = 0f;
            colorMatrix[13] = 0f;
            colorMatrix[14] = 0f;
            colorMatrix[15] = 1f;
            colorOffset[0] = bias;
            colorOffset[1] = bias;
            colorOffset[2] = bias;
            colorOffset[3] = 0f;
        }

        private int createExternalTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return textures[0];
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (vertexShader == 0 || fragmentShader == 0) {
                if (vertexShader != 0) {
                    GLES20.glDeleteShader(vertexShader);
                }
                if (fragmentShader != 0) {
                    GLES20.glDeleteShader(fragmentShader);
                }
                return 0;
            }
            int shaderProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(shaderProgram, vertexShader);
            GLES20.glAttachShader(shaderProgram, fragmentShader);
            GLES20.glLinkProgram(shaderProgram);
            int[] status = new int[1];
            GLES20.glGetProgramiv(shaderProgram, GLES20.GL_LINK_STATUS, status, 0);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            if (status[0] == 0) {
                Log.e(TAG, "Shader link failed: " + GLES20.glGetProgramInfoLog(shaderProgram));
                GLES20.glDeleteProgram(shaderProgram);
                return 0;
            }
            return shaderProgram;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private static FloatBuffer floatBuffer(float[] values) {
            FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            buffer.put(values);
            buffer.position(0);
            return buffer;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
