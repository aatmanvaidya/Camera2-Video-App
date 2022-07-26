@file:Suppress("DEPRECATION")

package com.example.camera2videotry7

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.hardware.camera2.CameraDevice.TEMPLATE_RECORD
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.math.max

class Camera2VideoFragment : Fragment(), View.OnClickListener,
    //this method is called to check permissions such as access to camera etc
    ActivityCompat.OnRequestPermissionsResultCallback {
    // defining some constant variables
    private val FRAGMENT_DIALOG = "dialog"
    private val TAG = "Camera2VideoFragment"
    private val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
    private val SENSOR_ORIENTATION_INVERSE_DEGREES = 270

    //map integers to integers
    //this container keeps its mappings in an array data structure, using a binary search to find keys
    private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }
    private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 270)
        append(Surface.ROTATION_90, 180)
        append(Surface.ROTATION_180, 90)
        append(Surface.ROTATION_270, 0)
    }

    // handles several lifecycle events on a texture view
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    }
    // autoFitTexture - can be adjusted accordingly
    // calling it here
    private lateinit var textureView: AutoFitTextureView
    //calling the button that records video
    private lateinit var videoButton: Button
    //A reference to the opened [android.hardware.camera2.CameraDevice].
    private var cameraDevice: CameraDevice? = null
    //a reference to the current camera capture session
    private var captureSession: CameraCaptureSession? = null
    //size of camera preview
    private lateinit var previewSize: Size
    //the size of video recording
    private lateinit var videoSize: Size
    //check whether the video is being recording currently or not
    private var isRecordingVideo = false
    //An additional thread for running tasks that shouldn't block the UI.
    private var backgroundThread: HandlerThread? = null
    //A [Handler] for running tasks in the background.
    private var backgroundHandler: Handler? = null
    //A [Semaphore] to prevent the app from exiting before closing the camera.
    private val cameraOpenCloseLock = Semaphore(1)
    //capture request builder for camera preview
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    //Orientation of the camera sensor
    private var sensorOrientation = 0

    //[CameraDevice.StateCallback] is called when [CameraDevice] changes its status.
    private val stateCallback = object : CameraDevice.StateCallback() {
        //The CameraDevice class is a representation of a single camera connected to an Android device
        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@Camera2VideoFragment.cameraDevice = cameraDevice
            startPreview()
            configureTransform(textureView.width, textureView.height)
        }
        //when camera gets disconnected.
        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@Camera2VideoFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@Camera2VideoFragment.cameraDevice = null
            activity?.finish()
        }

    }
    //output file for video
    private var nextVideoAbsolutePath: String? = null
    //init the mediaRecorder
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreateView( //inflate the layout, passing the layout
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera2_video, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        //setting up the buttons and on-click listeners
        textureView = view.findViewById(R.id.texture)
        videoButton = view.findViewById<Button>(R.id.video).also {
            it.setOnClickListener(this)
        }
        view.findViewById<View>(R.id.info).setOnClickListener(this)
    }
    //makes the fragment begin interacting with the user (based on its containing activity being resumed).
    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }
    //fragment is no longer interacting with the user
    // either because its activity is being paused or a fragment operation is modifying it in the activity.
    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }
    //This class represents the basic building block for user interface components.
    // A View occupies a rectangular area on the screen and is responsible for drawing and event handling.
    override fun onClick(view: View) {
        when (view.id) {
            R.id.video -> if (isRecordingVideo) stopRecordingVideo() else startRecordingVideo()
            R.id.info -> {
                if (activity != null) {
                    AlertDialog.Builder(activity)
                        .setMessage(R.string.intro_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }
    //Starts a background thread and its [Handler].
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }
    //Stops the background thread and its [Handler].
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }
    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private fun shouldShowRequestPermissionRationale(permissions: Array<String>) =
        permissions.any { shouldShowRequestPermissionRationale(it) }
    //Requests permissions needed for recording video.
    private fun requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.size == VIDEO_PERMISSIONS.size) {
                for (result in grantResults) {
                    if (result != PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                            .show(childFragmentManager, FRAGMENT_DIALOG)
                        break
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
    private fun hasPermissionsGranted(permissions: Array<String>) =
        permissions.none {
            checkSelfPermission((activity as FragmentActivity), it) != PERMISSION_GRANTED
        }

    /**
     * Tries to open a [CameraDevice]. The result is listened by [stateCallback].
     *
     * Lint suppression - permission is checked in [hasPermissionsGranted]
     */
    @SuppressLint("MissingPermission")
    //on opening camera check the video permissions
    private fun openCamera(width: Int, height: Int) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions()
            return
        }
        val cameraActivity = activity //get fragment activity
        if (cameraActivity == null || cameraActivity.isFinishing) return //null check
        //define a manager that returns the handle to a system-level service by name (string)
        //CameraManager - A system service manager for detecting, characterizing, and connecting to CameraDevices.
        val manager = cameraActivity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            //Acquires a permit from this semaphore, only if one is available at the time of invocation.
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            //Return the list of currently connected camera devices by identifier,
            // including cameras that may be in use by other camera API clients.
            val cameraId = manager.cameraIdList[0]
            // Choose the sizes for camera preview and video recording
            //Query the capabilities of a camera device. These capabilities are immutable for a given camera.
            val characteristics = manager.getCameraCharacteristics(cameraId)
            //The available stream configurations that this camera device supports;
            // also includes the minimum frame durations and the stall durations for each format/size combination.
            val map = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")
            //get the sensor orientation
            sensorOrientation = characteristics.get(SENSOR_ORIENTATION)!!
            //function to set the size
            //getOutputSizes - Get a list of sizes compatible with the requested image format.
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
            //set the size of the preview
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width, height, videoSize
            )
            //not able to understand this part
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }
            configureTransform(width, height)
            //init the mediaRecorder
            mediaRecorder = MediaRecorder()
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            showToast("Cannot access the camera.")
            cameraActivity.finish()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }
    //Close the Camera Device
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Start the camera preview.
     */
    private fun startPreview() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            val texture = textureView.surfaceTexture
            texture!!.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Update the camera preview. [startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            HandlerThread("CameraPreview").start()
            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = (activity as FragmentActivity).windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val cameraActivity = activity ?: return

        if (nextVideoAbsolutePath.isNullOrEmpty()) {
            nextVideoAbsolutePath = getVideoFilePath(cameraActivity)
        }

        val rotation = cameraActivity.windowManager.defaultDisplay.rotation
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder?.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder?.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(nextVideoAbsolutePath)
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun getVideoFilePath(context: Context?): String {
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = context?.getExternalFilesDir(null)

        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }

    private fun startRecordingVideo() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture = textureView.surfaceTexture.apply {
                this!!.setDefaultBufferSize(previewSize.width, previewSize.height)
            }

            // Set up Surface for camera preview and MediaRecorder
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
            }
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        captureSession = cameraCaptureSession
                        updatePreview()
                        activity?.runOnUiThread {
                            videoButton.setText(R.string.stop)
                            isRecordingVideo = true
                            mediaRecorder?.start()
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun stopRecordingVideo() {
        isRecordingVideo = false
        videoButton.setText(R.string.record)
        mediaRecorder?.apply {
            stop()
            reset()
        }

        if (activity != null) showToast("Video saved: $nextVideoAbsolutePath")
        nextVideoAbsolutePath = null
        startPreview()
    }

    private fun showToast(message: String) = Toast.makeText(activity, message, LENGTH_SHORT).show()

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
        it.width == it.height * 4 / 3 && it.width <= 1080
    } ?: choices[choices.size - 1]

    /**
     * Given [choices] of [Size]s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal [Size], or an arbitrary one if none were big enough
     */
    private fun chooseOptimalSize(
        choices: Array<Size>,
        width: Int,
        height: Int,
        aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height
        }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    companion object {
        fun newInstance(): Camera2VideoFragment = Camera2VideoFragment()
    }

}