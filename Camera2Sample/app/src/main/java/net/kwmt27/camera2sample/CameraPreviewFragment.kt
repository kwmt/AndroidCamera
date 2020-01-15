package net.kwmt27.camera2sample

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.ajalt.timberkt.Timber.d
import com.github.ajalt.timberkt.Timber.e
import com.github.ajalt.timberkt.Timber.i
import com.github.ajalt.timberkt.Timber.w
import kotlinx.android.synthetic.main.camera_preview_fragment.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraPreviewFragment : Fragment() {

    private val viewModel: CameraPreviewViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.camera_preview_fragment, container, false)

    lateinit var cameraSource: CameraSource
    override fun onResume() {
        super.onResume()

        GlobalScope.launch(Dispatchers.Main) {
            cameraSource = CameraSource(this@CameraPreviewFragment, texture)
            cameraSource.startPreview()
        }
    }

    override fun onPause() {
        super.onPause()

        GlobalScope.launch(Dispatchers.Main) {
            cameraSource.stopPreview()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        GlobalScope.launch(Dispatchers.Main) {
            cameraSource.close()
        }
    }
}

@SuppressLint("MissingPermission")
class CameraSource(private val context: Fragment, private val textureView: TextureView) {

    private val cameraManager = context.requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val bgThreadPoolContext = newFixedThreadPoolContext(2, "bg")

    private val backgroundThread: HandlerThread by lazy {
        HandlerThread("CameraSource").also {
            it.start()
            i { "Created backgroundThread" }
        }
    }
    private val backgroundHandler: Handler by lazy {
        Handler(backgroundThread.looper).also {
            i { "Created backgroundHandler" }
        }
    }

    // TextureView
    private val previewSurface: Surface by lazy {
        runBlocking(bgThreadPoolContext) {
            suspendCoroutine<Surface> { cont ->
                if (textureView.isAvailable) {
                    cont.resume(Surface(textureView.surfaceTexture))
                } else {
                    textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            Log.i("", "cameraPreviewTextureView available with res ($width x $height)")
                            cont.resume(Surface(surfaceTexture))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            }
        }
    }

    private val cameraId: String by lazy {
        val cameraId = cameraManager.cameraIdList.filterNotNull().maxBy { cameraId ->
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> 3
                CameraCharacteristics.LENS_FACING_FRONT -> 2
                CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
                else -> 0
            }
        } ?: throw NoSuchElementException("Unable to find camera")
        i { "bestCameraId:created $cameraId" }
        cameraId
    }

    private val cameraCharacteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId).also {
            i { "cameraCharacteristics:created for camera $cameraId" }
        }
    }

    private val cameraDevice = LazySuspend<CameraDevice> {
        suspendCoroutine { cont ->
            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try {
                        cont.resumeWithException(Exception("Problem with cameraManager.openCamera onDisconnected"))
                    } catch (e: IllegalStateException) {
                        w { "Swallowing onDisconnected:resumeWithException because not the first resume." }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    when (error) {
                        ERROR_CAMERA_DEVICE -> w { "CameraDevice.StateCallback: Camera device has encountered a fatal error." }
                        ERROR_CAMERA_DISABLED -> w { "CameraDevice.StateCallback: Camera device could not be opened due to a device policy." }
                        ERROR_CAMERA_IN_USE -> w { "CameraDevice.StateCallback: Camera device is in use already." }
                        ERROR_CAMERA_SERVICE -> w { "CameraDevice.StateCallback: Camera service has encountered a fatal error." }
                        ERROR_MAX_CAMERAS_IN_USE -> w { "CameraDevice.StateCallback: Camera device could not be opened because there are too many other open camera devices." }
                    }
                    try {
                        cont.resumeWithException(Exception("openCamera onError $error"))
                    } catch (e: IllegalStateException) {
                        w { "Swallowing onError:resumeWithException because not the first resume." }
                    }
                }
            }
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)

        }
    }

    private val imageSizeForImageReader: Size by lazy {
        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG).maxBy {
            it.width * it.height
        }!!.also {
            i { "Found max size for the camera JPEG: $it" }
        }
    }

    private val imageReaderJPEG: ImageReader by lazy {
        ImageReader.newInstance(imageSizeForImageReader.width, imageSizeForImageReader.height, ImageFormat.JPEG, 3)
            .also {
                it.setOnImageAvailableListener(onImageAvailableForImageReader, backgroundHandler)
                i { "imageReaderJPEG:created maxImages ${it.maxImages}, registered onImageAvailableForImageReader" }
            }
    }

    private val onImageAvailableForImageReader by lazy {
        ImageReader.OnImageAvailableListener {
            // TODO: ファイル保存
        }

    }
    // CameraDeviceからイメージをキャプチャ or Streamするために、CameraCaptureSessionを作成する必要がある
    // 出力するSurface
    private val cameraCaptureSession = LazySuspend<CameraCaptureSession> {
        val cameraDevice = cameraDevice()
        suspendCoroutine { cont ->

            cameraDevice.createCaptureSession(
                listOf(previewSurface, imageReaderJPEG.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val ex = Exception("createCaptureSession.onConfigureFailed")
                        e(ex) { "onConfigureFailed: Could not configure capture session." }
                        cont.resumeWithException(ex)
                    }

                    override fun onConfigured(session: CameraCaptureSession) = cont.resume(session).also {
                        i { "Created cameraCaptureSession through createCaptureSession.onConfigured" }
                    }

                },
                backgroundHandler
            )
        }
    }

    private val captureRequestPreview = LazySuspend {
        cameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.SENSOR_FRAME_DURATION, (1_000_000_000.0 / 4).toLong())
        }.also { i { "preview at 4 frames/second" } }
    }

    suspend fun startPreview() = withContext(Dispatchers.Default) {
        d { "CameraSource.startPreview:start" }
        cameraCaptureSession().setRepeatingRequest(captureRequestPreview().build(), null, backgroundHandler)
        d { "CameraSource.startPreview:end" }
    }

    suspend fun stopPreview() = withContext(Dispatchers.Default) {
        d { "CameraSource.stopPreview (stops repeating capture session)" }
        cameraCaptureSession().stopRepeating()
    }

    suspend fun close() = withContext(Dispatchers.Default) {
        cameraDevice().close()
        previewSurface.release()
        stopBackgroundThread()
    }

    /**
     * take just one picture
     */
    suspend fun takePicture() = withContext(Dispatchers.Default) {
        captureRequestPreview().set(
            CaptureRequest.JPEG_ORIENTATION,
            cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        )
        cameraCaptureSession().capture(captureRequestPreview().build(), null, backgroundHandler)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (ex: InterruptedException) {
            e(ex) { "stopBackgroundThread error waiting for background thread" }
        }
    }

    companion object {
        private val SDF = SimpleDateFormat("yyyyMMddhhmmssSSS", Locale.US)

        /**
         * Save image to storage
         * @param image Image object got from onPicture() callback of EZCamCallback
         * @param file File where image is going to be written
         * @return File object pointing to the file uri, null if file already exist
         */
        private fun saveImage(image: Image, file: File) {
            require(!file.exists()) { "Image target file $file must not exist." }
            val buffer = image.planes[0].buffer!!
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            file.writeBytes(bytes)
            i { "Finished writing image to $file: ${file.length()}" }
        }
    }
}
