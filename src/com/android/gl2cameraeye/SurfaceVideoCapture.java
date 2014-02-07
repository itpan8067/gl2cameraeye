package com.android.gl2cameraeye;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.hardware.Camera;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import android.hardware.Camera;
import android.hardware.Camera.Face;


/**
 **/
class SurfaceVideoCapture extends Thread
                          implements OnFrameAvailableListener {
    private Thread mCallbackThread = null;

    private final VideoCapture mVideoCapture;
    private final Context mContext;

    private int[] mGlTextures = null;
    private int mCaptureTextureID;
    private int mRenderTextureID;
    private SurfaceTexture mCaptureSurfaceTexture;
    private SurfaceTexture mRenderSurfaceTexture;

    private ImageReader mImageReader = null;

    private final int mWidth, mHeight;

    private boolean mUpdateSurface;

    // Following two are used to make getCaptureSurfaceTexture() to wait until
    // the GLThread actually creates it. This will make the owner class
    // effectively wait until the GL thread is correctly started. Perhaps move
    // to timeout'ed. Reconsider alloc/free in constructor/destructor.
    private Object mFinishedConfiguration = new Object();
    private boolean mIsFinishedConfiguration;
    private long mPreviousTimestamp;

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final String TAG = "SurfaceVideoCapture";

    public SurfaceVideoCapture(VideoCapture videoCapture,
                               Context context,
                               int width,
                               int height) {
        Log.d(TAG, "constructor");
        mVideoCapture = videoCapture;
        mContext = context;
        mWidth = width;
        mHeight = height;
    }

    public SurfaceTexture blockAndGetCaptureSurfaceTexture() {
        Log.d(TAG, "getCaptureSurfaceTexture: wait for configuration finished");
        synchronized (mFinishedConfiguration) {
            // http://docs.oracle.com/javase/7/docs/api/java/lang/Object.html
            try {
                while (!mIsFinishedConfiguration)
                    mFinishedConfiguration.wait();
            } catch (Exception e) {
                Log.e(TAG, " Couldn't notify finished cosnfiguration: " + e);
            }
        }
        Log.d(TAG, " Configuration finished");
        return mCaptureSurfaceTexture;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
      // SurfaceTexture calls here when it has new data available. Call may
      // come in from some random thread, so let's be safe and use synchronize.
      // (Experimentally it's been seen that Thread.currentThread().toString())
      // gives "main" as the thread calling). No OpenGL calls can be done here,
      // in particular mCaptureSurfaceTexture.updateTexImage(), we need to signal to
      // the thread that owns the off-screen rendering context.
      mUpdateSurface = true;
      Log.d(TAG, "onFrameAvailable, thread " +
              Thread.currentThread().toString());
    }

    @Override
    public void run() {
        Log.d(TAG, "run");
        mCallbackThread = Thread.currentThread();

        // Moves the current Thread into the background
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND);

        // Need to create contexs etc _in this run_ method. DELETEME
        if (!(makeMeAnEglContextBaby() &&
                createCaptureAndRenderTexturesAndSurfaceTextures() &&
                createFramebufferObject() &&
                compileAndLoadGles20Shaders()))
           return;
        synchronized (this) {
            mUpdateSurface = false;
        }
        Log.d(TAG, " EGL and GLES2 OK, running on thread: "
                + mCallbackThread.toString());


        synchronized (mFinishedConfiguration) {
            try {
                mFinishedConfiguration.notify();
            } catch (Exception e) {
                Log.e(TAG, "Couldn't notify finished configuration: " + e);
            }
            mIsFinishedConfiguration = true;
        }

        for (;;) {
            synchronized (this) {
                if (mUpdateSurface) {
                    guardedRun();
                }
            }
        }
    }

    private void guardedRun() {

        mCaptureSurfaceTexture.updateTexImage();
        mCaptureSurfaceTexture.getTransformMatrix(mSTMatrix);

        if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            dumpEGLError("eglMakeCurrent");
            return;
        }
        //GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer[0]);
        //if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) !=
        //        GLES20.GL_FRAMEBUFFER_COMPLETE){
        //    Log.e(TAG, " Framebuffer fecked up");
        //    mUpdateSurface = false;
        //    return;
        //}

        long timestamp = mCaptureSurfaceTexture.getTimestamp();
        Log.d(TAG, "frame received, updating texture, fps~=" +
                1000000000L / (timestamp - mPreviousTimestamp));
        mPreviousTimestamp = timestamp;

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        dumpGLErrorIfAny("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mCaptureTextureID);

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT,
                false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        dumpGLErrorIfAny("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        dumpGLErrorIfAny("glEnableVertexAttribArray maPositionHandle");

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        dumpGLErrorIfAny("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        dumpGLErrorIfAny("glEnableVertexAttribArray maTextureHandle");

        // Create a rotation for the geometry.
        float vflip = -1.0f;
        int orientation = mVideoCapture.getDeviceOrientation();

        Matrix.setRotateM(mRotationMatrix, 0, orientation, 0, 0, vflip);

        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mRotationMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, mMVPMatrix, 0, mVMatrix, 0);

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        GLES20.glUniform1f(muCRatioHandle, (float) mWidth / mHeight);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        dumpGLErrorIfAny("glDrawArrays");

        mUpdateSurface = false;
    }

    public void finishme() {
        deleteEglContext();
        if (mGlTextures != null)
            GLES20.glDeleteTextures(1, mGlTextures, 0);
    }

    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    static final int EGL_OPENGL_ES2_BIT = 4;

    private EGL10 mEgl;
    private EGLDisplay mEglDisplay = EGL10.EGL_NO_DISPLAY;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext = EGL10.EGL_NO_CONTEXT;
    private EGLSurface mEglSurface = EGL10.EGL_NO_SURFACE;

    private boolean makeMeAnEglContextBaby() {
        Log.d(TAG, "makeMeAnEglContextBaby");
        mEgl = (EGL10) EGLContext.getEGL();

        ////////////////////////////////////////////////////////////////////////
        // DISPLAY create-initialize
        mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
            dumpEGLError("eglGetDisplay");
            return false;
        }

        int[] version = new int[2];
        if (!mEgl.eglInitialize(mEglDisplay, version)) {
            dumpEGLError("eglInitialize");
            return false;
        }
        Log.d(TAG, " eglInitialize version " + version[0] + "." + version[1]);

        ////////////////////////////////////////////////////////////////////////
        // Config create-search-use
        int[] eglConfigSpec = {
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,  // Very important
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_NONE
        };
        int[] configsCount = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        if (!mEgl.eglChooseConfig(mEglDisplay,
                                  eglConfigSpec,
                                  configs,
                                  1,
                                  configsCount)) {
            dumpEGLError("eglChooseConfig");
            return false;
        }
        if (configsCount[0] == 0) {
            dumpEGLError("eglChooseConfig didn't find a suitable config.");
            return false;
        }
        mEglConfig = configs[0];

        ////////////////////////////////////////////////////////////////////////
        // Create the context with the previous display & config.
        int[] eglContextAttrib =
                { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
        mEglContext = mEgl.eglCreateContext(mEglDisplay,
                                            mEglConfig,
                                            EGL10.EGL_NO_CONTEXT,
                                            eglContextAttrib);
        if (mEglContext == EGL10.EGL_NO_CONTEXT) {
            dumpEGLError("eglCreateContext");
            return false;
        }

        ////////////////////////////////////////////////////////////////////////
        // Surface. For Pbuffer we need its attributes, mainly width and height.
        //int[] eglSurfaceAttribList = {
        //        EGL10.EGL_WIDTH, 1024,
        //        EGL10.EGL_HEIGHT, 1024,
        //        EGL10.EGL_NONE
        //};
        //mEglSurface = mEgl.eglCreatePbufferSurface(mEglDisplay,
        //                                           mEglConfig,
        //                                           eglSurfaceAttribList);
        int[] eglSurfaceAttribList = {
                EGL10.EGL_NONE
        };
        // NOTE(mcasas): We need a context created before we can create and
        // configure the textures. But for the context to be created, a
        // Surface, SurfaceTexture, SurfaceHolder or SurfaceView is needed, by
        // preference the second, and to create a SurfaceTexture, a texture id
        // is needed! Circular dependency!
        // Hack: hardcode texture id to number 2, create a SurfaceTexture with
        // that id, and use it to create a context.
        // Alternative idea: Make a context with a Pbuffer and change it to
        // FBO-WindowSurface afterwards.
        mRenderTextureID = 2;
        mRenderSurfaceTexture = new SurfaceTexture(mRenderTextureID);
        mRenderSurfaceTexture.setDefaultBufferSize(512, 512);
        mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay,
                                                  mEglConfig,
                                                  mRenderSurfaceTexture,
                                                  eglSurfaceAttribList);

        //mImageReader = ImageReader.newInstance(640,480,PixelFormat.RGB_565,2);
        //mRenderSurface = mImageReader.getSurface();
        //mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay,
        //                                          mEglConfig,
        //                                          mRenderSurface,
        //                                          eglSurfaceAttribList);

        if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
            dumpEGLError("createPbufferSurface");
            return false;
        }

        ////////////////////////////////////////////////////////////////////////
        // Finally make the context current.
        if (!mEgl.eglMakeCurrent(mEglDisplay,
                                 mEglSurface,
                                 mEglSurface,
                                 mEglContext)) {
            dumpEGLError("eglMakeCurrent");
            return false;
        }

        if (!mEglContext.equals(mEgl.eglGetCurrentContext()) ||
            !mEglSurface.equals(mEgl.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
            dumpEGLError("eglMakeCurrent");
            return false;
        }

        return true;
    }

    private void deleteEglContext() {
          mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
          mEgl.eglDestroyContext(mEglDisplay, mEglContext);
    }

    private void dumpEGLError(String op) {
        Log.e(TAG, op + " :" + GLUtils.getEGLErrorString(mEgl.eglGetError()));
    }

    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    private boolean createCaptureAndRenderTexturesAndSurfaceTextures() {
        Log.d(TAG, "createCaptureAndRenderTexturesAndSurfaceTextures");

        mGlTextures = new int[1];
        GLES20.glGenTextures(1, mGlTextures, 0);
        mCaptureTextureID = mGlTextures[0];
        // Create and allocate the special texture id and associated
        // SurfaceTexture for video capture. Special means type EXTERNAL_OES.
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mGlTextures[0]);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        mCaptureSurfaceTexture = new SurfaceTexture(mCaptureTextureID);

        // Create and allocate a normal texture, that will be used to render the
        // capture texture id onto. This is a hack but there's an explanation in
        //  makeMeAnEglContextBaby().
        mRenderTextureID = 2; //!!!!!!!!!!!!!!!!!!!!!!!HACKKKKKK!!!!!!!!!!!!!!!!
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mRenderTextureID);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, mWidth,
                mHeight, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);

        return true;
    }

    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    private int[] mFramebuffer = null;

    boolean createFramebufferObject() {
        Log.d(TAG, "createFramebufferObject");

        mFramebuffer = new int[1];
        GLES20.glGenFramebuffers(1, mFramebuffer, 0);
        dumpGLErrorIfAny("glGenFramebuffers");

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer[0]);
        dumpGLErrorIfAny("glBindFramebuffer");
        // Qualcomm recommends clear after glBindFrameBuffer().
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        ////////////////////////////////////////////////////////////////////////
        // Bind the texture to the generated Framebuffer Object.
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                                      GLES20.GL_COLOR_ATTACHMENT0,
                                      GLES20.GL_TEXTURE_2D,
                                      mRenderTextureID,
                                      0);
        dumpGLErrorIfAny("glFramebufferTexture2D");

        if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) !=
                GLES20.GL_FRAMEBUFFER_COMPLETE){
            Log.e(TAG, " Created Framebuffer and attached to texture");
        } else {
            Log.d(TAG, " Framebuffer created and attached to texture.");
        }

        return true;
    }

    private void deleteFrameBufferObject() {
        GLES20.glDeleteFramebuffers(1, mFramebuffer, 0);
    }

    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    // Only call this after the EGL context has been created and made current.
    boolean compileAndLoadGles20Shaders() {
        mTriangleVertices = ByteBuffer
                .allocateDirect(mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);

        mPos[0] = 0.f;
        mPos[1] = 0.f;
        mPos[2] = 0.f;

        // Set up alpha blending and an Android background color.
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        // Set up shaders and handles to their variables.
        mProgram = createProgram(mVertexShader, mFragmentShader);
        if (mProgram == 0) {
            return false;
        }

        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        dumpGLErrorIfAny("glGetAttribLocation aPosition");
        if (maPositionHandle == -1)
            return false;
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        dumpGLErrorIfAny("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1)
          return false;

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        dumpGLErrorIfAny("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1)
            return false;

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        dumpGLErrorIfAny("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1)
            return false;

        muCRatioHandle = GLES20.glGetUniformLocation(mProgram, "uCRatio");
        dumpGLErrorIfAny("glGetUniformLocation uCRatio");
        if (muCRatioHandle == -1)
            return false;

        Matrix.setLookAtM(mVMatrix, 0, 0, 0, 5f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);

        return true;
    }

    private int mProgram;
    private final String mVertexShader =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "uniform float uCRatio;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTextureCoord;\n" +
        "varying vec2 vTextureCoord;\n" +
        "void main() {\n" +
        "  vec4 scaledPos = aPosition;\n" +
        "  scaledPos.x = scaledPos.x * uCRatio;\n" +
        "  gl_Position = uMVPMatrix * scaledPos;\n" +
        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
        "}\n";
    private final String mFragmentShader =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTextureCoord;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
        "}\n";

    private float[] mMVPMatrix = new float[16];
    private float[] mProjMatrix = new float[16];
    private float[] mVMatrix = new float[16];
    private float[] mSTMatrix = new float[16];
    private float[] mRotationMatrix = new float[16];

    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int muCRatioHandle;
    private int maPositionHandle;
    private int maTextureHandle;
    private float[] mPos = new float[3];

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES =
            5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
    private final float[] mTriangleVerticesData = {
            // X ---- Y- Z -- U -- V
            -1.0f, -1.0f, 0, 0.f, 0.f,
             1.0f, -1.0f, 0, 1.f, 0.f,
            -1.0f,  1.0f, 0, 0.f, 1.f,
             1.0f,  1.0f, 0, 1.f, 1.f,
    };

    private FloatBuffer mTriangleVertices;

    private int createProgram(String vertexSource, String fragmentSource) {
        Log.d(TAG, "createProgram");
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0)
            return 0;
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0)
            return 0;

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            dumpGLErrorIfAny("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            dumpGLErrorIfAny("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            } else {
              Log.d(TAG, GLES20.glGetProgramInfoLog(program));
            }
        }
        return program;
    }

    private int loadShader(int shaderType, String source) {
        Log.d(TAG, "loadShader " + shaderType);
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            } else {
              Log.d(TAG, GLES20.glGetShaderInfoLog(shader));
            }
        } else {
            Log.e(TAG, "Could not create shader " + shaderType + ":");
        }
        return shader;
    }

    private void dumpGLErrorIfAny(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR)
            Log.e(TAG, "**" + op + ": glError " + error);
    }
}