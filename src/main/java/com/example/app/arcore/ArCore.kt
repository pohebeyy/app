package com.example.app.arcore

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import com.example.app.M4
import com.example.app.V2A
import com.example.app.count
import com.example.app.createExternalTextureId
import com.example.app.dimenV2A
import com.example.app.filament.Filament
import com.example.app.m4Identity
import com.example.app.matrix
import com.example.app.projectionMatrix
import com.example.app.readUncompressedAsset
import com.example.app.rotate
import com.example.app.set
import com.example.app.toDoubleArray
import com.example.app.toFloatBuffer
import com.example.app.toShortBuffer
import com.example.app.translate
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlin.math.roundToInt

class ModelBuffers(val clipPosition: V2A, val uvs: V2A, val triangleIndices: ShortArray)

@SuppressLint("MissingPermission")
class ArCore(private val activity: Activity, val filament: Filament, private val view: View) {
    companion object {
        const val NEAR: Float = .1f
        const val FAR: Float = 30f
        private const val POSITION_BUFFER_INDEX: Int = 0
        private const val UV_BUFFER_INDEX: Int = 1
    }

    private val cameraStreamTextureId: Int = createExternalTextureId()
    private lateinit var stream: Stream
    private lateinit var depthMaterialInstance: MaterialInstance
    private lateinit var flatMaterialInstance: MaterialInstance

    @Entity
    var depthRenderable: Int = 0

    @Entity
    var flatRenderable: Int = 0

    val session: Session = Session(activity)
        .also { session ->
            session.config
                .apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = Config.FocusMode.AUTO

                    // reading depth information is broken with latest filament
                    depthMode =
                        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
                        else Config.DepthMode.DISABLED

                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    // getting ar frame doesn't block and gives last frame
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                .let(session::configure)

            session.setCameraTextureName(cameraStreamTextureId)
        }

    private val cameraId: String = session.cameraConfig.cameraId

    private val cameraManager: CameraManager =
        ContextCompat.getSystemService(activity, CameraManager::class.java)!!

    var timestamp: Long = 0L

    lateinit var frame: Frame

    private lateinit var depthTexture: Texture

    fun destroy() {
        session.close()
    }

    private var displayRotationDegrees: Int = 0

    fun configurationChange() {
        if (this::frame.isInitialized.not()) return

        val intrinsics = frame.camera.textureIntrinsics
        val dimensions = intrinsics.imageDimensions

        val displayWidth: Int
        val displayHeight: Int
        val displayRotation: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Новый способ для Android 11+ (API 30 и выше)
            val windowMetrics = activity.windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            displayWidth = windowMetrics.bounds.width() - insets.left - insets.right
            displayHeight = windowMetrics.bounds.height() - insets.top - insets.bottom
            displayRotation = activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            // Старый способ для Android до 11
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            displayRotation = display.rotation
        }

        displayRotationDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> throw Exception("Invalid Display Rotation")
        }

        // Определяем размеры камеры относительно экрана
        val cameraWidth: Int
        val cameraHeight: Int

        when (cameraManager
            .getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION)!!) {
            0, 180 -> when (displayRotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    cameraWidth = dimensions[0]
                    cameraHeight = dimensions[1]
                }

                else -> {
                    cameraWidth = dimensions[1]
                    cameraHeight = dimensions[0]
                }
            }

            else -> when (displayRotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    cameraWidth = dimensions[1]
                    cameraHeight = dimensions[0]
                }

                else -> {
                    cameraWidth = dimensions[0]
                    cameraHeight = dimensions[1]
                }
            }
        }

        // Соотношение сторон экрана
        val displayAspectRatio = displayWidth.toFloat() / displayHeight.toFloat()

        // Соотношение сторон камеры
        val cameraAspectRatio = cameraWidth.toFloat() / cameraHeight.toFloat()

        // Определяем максимальный размер для SurfaceView с сохранением соотношения сторон
        val viewWidth: Int
        val viewHeight: Int

        if (displayAspectRatio > cameraAspectRatio) {
            // Экран шире камеры
            viewWidth = displayWidth
            viewHeight = (viewWidth / cameraAspectRatio).toInt()
        } else {
            // Экран уже камеры
            viewHeight = displayHeight
            viewWidth = (viewHeight * cameraAspectRatio).toInt()
        }

        // Обновляем размеры SurfaceView, чтобы они занимали весь экран
        view.updateLayoutParams<FrameLayout.LayoutParams> {
            width = viewWidth
            height = viewHeight
        }

        // Устанавливаем геометрию отображения камеры
        session.setDisplayGeometry(displayRotation, viewWidth, viewHeight)
    }



























    private var hasDepthImage: Boolean = false

    fun update(frame: Frame, filament: Filament) {
        val firstFrame = this::frame.isInitialized.not()
        this.frame = frame

        if (firstFrame) {
            configurationChange()
            val camera = frame.camera
            val intrinsics = camera.textureIntrinsics
            val dimensions = intrinsics.imageDimensions
            val width = dimensions[0]
            val height = dimensions[1]

            stream = Stream
                .Builder()
                .stream(SurfaceTexture(cameraStreamTextureId))
                .width(width)
                .height(height)
                .build(filament.engine)

            flatMaterialInstance = activity
                .readUncompressedAsset("materials/flat.filamat")
                .let { byteBuffer ->
                    Material
                        .Builder()
                        .payload(byteBuffer, byteBuffer.remaining())
                }
                .build(filament.engine)
                .createInstance()
                .also { materialInstance ->
                    materialInstance.setParameter(
                        "cameraTexture",
                        Texture
                            .Builder()
                            .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
                            .importTexture(cameraStreamTextureId.toLong())
                            .build(filament.engine)
                            .apply { setExternalStream(filament.engine, stream) },
                        TextureSampler(
                            TextureSampler.MinFilter.LINEAR,
                            TextureSampler.MagFilter.LINEAR,
                            TextureSampler.WrapMode.CLAMP_TO_EDGE,
                        )
                    )

                    materialInstance.setParameter(
                        "uvTransform",
                        MaterialInstance.FloatElement.FLOAT4,
                        m4Identity().floatArray,
                        0,
                        4,
                    )
                }

            initFlat()
        }

        // reading depth information is broken with latest filament
        (if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Unit else null)
            ?.let {
                if (hasDepthImage.not()) {
                    try {
                        val depthImage = frame.acquireDepthImage16Bits()

                        if (depthImage.planes[0].buffer[0] != 0.toByte()) {
                            hasDepthImage = true

                            if (this::depthTexture.isInitialized.not()) {
                                initDepthTextures(depthImage)
                            }

                            depthTexture.setImage(
                                filament.engine,
                                0,
                                Texture.PixelBufferDescriptor(
                                    depthImage.planes[0].buffer,
                                    Texture.Format.RG,
                                    Texture.Type.UBYTE,
                                    1,
                                    0,
                                    0,
                                    0,
                                    @Suppress("DEPRECATION")
                                    Handler(),
                                ) {
                                    depthImage.close()
                                    hasDepthImage = false
                                }
                            )

                            depthMaterialInstance.setParameter(
                                "uvTransform",
                                MaterialInstance.FloatElement.FLOAT4,
                                uvTransform().floatArray,
                                0,
                                4,
                            )

                            filament.scene.removeEntity(flatRenderable)
                            filament.scene.addEntity(depthRenderable)
                        } else {
                            null
                        }
                    } catch (error: Throwable) {
                        null
                    }
                } else Unit
            }
            ?: run {
                flatMaterialInstance.setParameter(
                    /* name = */ "uvTransform",
                    /* type = */ MaterialInstance.FloatElement.FLOAT4,
                    /* v = */ uvTransform().floatArray,
                    /* offset = */ 0,
                    /* count = */ 4,
                )

                filament.scene.removeEntity(depthRenderable)
                filament.scene.addEntity(flatRenderable)
            }

        // update camera projection
        filament.camera.setCustomProjection(
            /* inProjection = */ frame.projectionMatrix().floatArray.toDoubleArray(),
            /* near = */ NEAR.toDouble(),
            /* far = */ FAR.toDouble(),
        )

        val cameraTransform = frame.camera.displayOrientedPose.matrix()
        filament.camera.setModelMatrix(cameraTransform.floatArray)
        val instance = filament.engine.transformManager.create(depthRenderable)
        filament.engine.transformManager.setTransform(instance, cameraTransform.floatArray)
    }

    private fun initFlat() {
        val tes = tessellation()

        RenderableManager
            .Builder(1)
            .castShadows(false)
            .receiveShadows(false)
            .culling(false)
            .geometry(
                /* index = */ 0,
                /* type = */ PrimitiveType.TRIANGLES,
                /* vertices = */
                VertexBuffer
                    .Builder()
                    .vertexCount(tes.clipPosition.count())
                    .bufferCount(2)
                    .attribute(
                        /* attribute = */ VertexAttribute.POSITION,
                        /* bufferIndex = */ POSITION_BUFFER_INDEX,
                        /* attributeType = */ AttributeType.FLOAT2,
                        /* byteOffset = */ 0,
                        /* byteStride = */ 0,
                    )
                    .attribute(
                        /* attribute = */ VertexAttribute.UV0,
                        /* bufferIndex = */ UV_BUFFER_INDEX,
                        /* attributeType = */ AttributeType.FLOAT2,
                        /* byteOffset = */ 0,
                        /* byteStride = */ 0,
                    )
                    .build(filament.engine)
                    .also { vertexBuffer ->
                        vertexBuffer.setBufferAt(
                            /* engine = */ filament.engine,
                            /* bufferIndex = */ POSITION_BUFFER_INDEX,
                            /* buffer = */ tes.clipPosition.floatArray.toFloatBuffer(),
                        )

                        vertexBuffer.setBufferAt(
                            /* engine = */ filament.engine,
                            /* bufferIndex = */ UV_BUFFER_INDEX,
                            /* buffer = */ tes.uvs.floatArray.toFloatBuffer(),
                        )
                    },
                /* indices = */
                IndexBuffer
                    .Builder()
                    .indexCount(tes.triangleIndices.size)
                    .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                    .build(filament.engine)
                    .apply { setBuffer(filament.engine, tes.triangleIndices.toShortBuffer()) },
            )
            .material(0, flatMaterialInstance)
            .build(filament.engine, EntityManager.get().create().also { flatRenderable = it })
    }

    private fun initDepthTextures(depthImage: Image) {
        val tes = tessellation()

        depthMaterialInstance = activity
            .readUncompressedAsset("materials/depth.filamat")
            .let { byteBuffer ->
                Material
                    .Builder()
                    .payload(byteBuffer, byteBuffer.remaining())
            }
            .build(filament.engine)
            .createInstance()
            .also { materialInstance ->
                materialInstance.setParameter(
                    /* name = */ "depthTexture",
                    /* texture = */
                    Texture
                        .Builder()
                        .width(depthImage.width)
                        .height(depthImage.height)
                        .sampler(Texture.Sampler.SAMPLER_2D)
                        .format(Texture.InternalFormat.RG8)
                        .levels(1)
                        .build(filament.engine)
                        .also { depthTexture = it },
                    /* sampler = */ TextureSampler(), //.also { it.anisotropy = 8.0f }
                )

                materialInstance.setParameter(
                    /* name = */ "uvTransform",
                    /* type = */ MaterialInstance.FloatElement.FLOAT4,
                    /* v = */ m4Identity().floatArray,
                    /* offset = */ 0,
                    /* count = */ 4,
                )
            }

        RenderableManager
            .Builder(1)
            .castShadows(false)
            .receiveShadows(false)
            .culling(false)
            .geometry(
                0,
                PrimitiveType.TRIANGLES,
                VertexBuffer
                    .Builder()
                    .vertexCount(tes.clipPosition.count())
                    .bufferCount(2)
                    .attribute(
                        /* attribute = */ VertexAttribute.POSITION,
                        /* bufferIndex = */ POSITION_BUFFER_INDEX,
                        /* attributeType = */ AttributeType.FLOAT2,
                        /* byteOffset = */ 0,
                        /* byteStride = */ 0,
                    )
                    .attribute(
                        /* attribute = */ VertexAttribute.UV0,
                        /* bufferIndex = */ UV_BUFFER_INDEX,
                        /* attributeType = */ AttributeType.FLOAT2,
                        /* byteOffset = */ 0,
                        /* byteStride = */ 0,
                    )
                    .build(filament.engine)
                    .also { vertexBuffer ->
                        vertexBuffer.setBufferAt(
                            /* engine = */ filament.engine,
                            /* bufferIndex = */ POSITION_BUFFER_INDEX,
                            /* buffer = */ tes.clipPosition.floatArray.toFloatBuffer()
                        )

                        vertexBuffer.setBufferAt(
                            /* engine = */ filament.engine,
                            /* bufferIndex = */ UV_BUFFER_INDEX,
                            /* buffer = */ tes.uvs.floatArray.toFloatBuffer()
                        )
                    },
                IndexBuffer
                    .Builder()
                    .indexCount(tes.triangleIndices.size)
                    .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                    .build(filament.engine)
                    .apply { setBuffer(filament.engine, tes.triangleIndices.toShortBuffer()) },
            )
            .material(0, depthMaterialInstance)
            .build(filament.engine, EntityManager.get().create().also { depthRenderable = it })
    }

    @Suppress("KotlinConstantConditions")
    private fun tessellation(): ModelBuffers {
        val tesWidth = 1
        val tesHeight = 1

        val clipPosition =
            V2A(FloatArray((((tesWidth * tesHeight) + tesWidth + tesHeight + 1) * dimenV2A)))

        val uvs =
            V2A(FloatArray((((tesWidth * tesHeight) + tesWidth + tesHeight + 1) * dimenV2A)))

        for (k in 0..tesHeight) {
            val v = k.toFloat() / tesHeight.toFloat()
            val y = (k.toFloat() / tesHeight.toFloat()) * 2f - 1f

            for (i in 0..tesWidth) {
                val u = i.toFloat() / tesWidth.toFloat()
                val x = (i.toFloat() / tesWidth.toFloat()) * 2f - 1f
                clipPosition.set(k * (tesWidth + 1) + i, x, y)
                uvs.set(k * (tesWidth + 1) + i, u, v)
            }
        }

        val triangleIndices = ShortArray(tesWidth * tesHeight * 6)

        for (k in 0 until tesHeight) {
            for (i in 0 until tesWidth) {
                triangleIndices[((k * tesWidth + i) * 6) + 0] =
                    ((k * (tesWidth + 1)) + i + 0).toShort()
                triangleIndices[((k * tesWidth + i) * 6) + 1] =
                    ((k * (tesWidth + 1)) + i + 1).toShort()
                triangleIndices[((k * tesWidth + i) * 6) + 2] =
                    ((k + 1) * (tesWidth + 1) + i).toShort()

                triangleIndices[((k * tesWidth + i) * 6) + 3] =
                    ((k + 1) * (tesWidth + 1) + i).toShort()
                triangleIndices[((k * tesWidth + i) * 6) + 4] =
                    ((k * (tesWidth + 1)) + i + 1).toShort()
                triangleIndices[((k * tesWidth + i) * 6) + 5] =
                    ((k + 1) * (tesWidth + 1) + i + 1).toShort()
            }
        }

        return ModelBuffers(clipPosition, uvs, triangleIndices)
    }

    private fun uvTransform(): M4 = m4Identity()
        .translate(.5f, .5f, 0f)
        .rotate(imageRotation().toFloat(), 0f, 0f, -1f)
        .translate(-.5f, -.5f, 0f)

    private fun imageRotation(): Int = (cameraManager
        .getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.SENSOR_ORIENTATION)!! +
            when (displayRotationDegrees) {
                0 -> 90
                90 -> 0
                180 -> 270
                270 -> 180
                else -> throw Exception()
            } + 270) % 360
}
