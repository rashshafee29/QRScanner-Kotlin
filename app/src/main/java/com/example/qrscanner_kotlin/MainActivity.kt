package com.example.qrscanner_kotlin

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.example.qrscanner_kotlin.viewmodel.CameraXViewModel
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector: CameraSelector? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private lateinit var qrCodeTextView: TextView


    private val screenAspectRatio: Int
        get() {
            val metrics = DisplayMetrics().also { previewView?.display?.getRealMetrics(it) }
            return aspectRatio(metrics.widthPixels, metrics.heightPixels)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupCamera()

        qrCodeTextView = findViewById(R.id.id_qr_code_text_view)
    }

    private fun setupCamera() {
        previewView = findViewById(R.id.id_camera_preview_view)
        cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        ViewModelProvider(
                this, ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        ).get(CameraXViewModel::class.java)
                .processCameraProvider
                .observe(
                        this,
                        Observer { provider: ProcessCameraProvider? ->
                            cameraProvider = provider
                            if (isCameraPermissionGranted()) {
                                bindCameraUseCases()
                            } else {
                                ActivityCompat.requestPermissions(
                                        this,
                                        arrayOf(Manifest.permission.CAMERA),
                                        PERMISSION_CAMERA_REQUEST
                                )
                            }
                        }
                )
    }

    private fun bindCameraUseCases() {
        bindPreviewUseCase()
        bindAnalyseUseCase()
    }

    private fun bindPreviewUseCase() {
        if (cameraProvider == null) {
            return
        }
        if (previewUseCase != null) {
            cameraProvider!!.unbind(previewUseCase)
        }

        previewUseCase = Preview.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(previewView!!.display.rotation)
                .build()
        previewUseCase!!.setSurfaceProvider(previewView!!.surfaceProvider)

        try {
            cameraProvider!!.bindToLifecycle(
                    /* lifecycleOwner= */this,
                    cameraSelector!!,
                    previewUseCase
            )
        } catch (illegalStateException: IllegalStateException) {
            illegalStateException.message?.let { Log.e(TAG, it) }
        } catch (illegalArgumentException: IllegalArgumentException) {
            illegalArgumentException.message?.let { Log.e(TAG, it) }
        }
    }

    private fun bindAnalyseUseCase() {
        val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()

        if (cameraProvider == null) {
            return
        }
        if (analysisUseCase != null) {
            cameraProvider!!.unbind(analysisUseCase)
        }

        analysisUseCase = ImageAnalysis.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(previewView!!.display.rotation)
                .build()

        val cameraExecutor = Executors.newSingleThreadExecutor()

        analysisUseCase?.setAnalyzer(
                cameraExecutor,
                ImageAnalysis.Analyzer { imageProxy ->
                    processImageProxy(barcodeScanner, imageProxy)
                }
        )

        try {
            cameraProvider!!.bindToLifecycle(
                    this,
                    cameraSelector!!,
                    analysisUseCase
            )
        } catch (illegalStateException: IllegalStateException) {
            illegalStateException.message?.let { Log.e(TAG, it) }
        } catch (illegalArgumentException: IllegalArgumentException) {
            illegalArgumentException.message?.let { Log.e(TAG, it) }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(
            barcodeScanner: BarcodeScanner,
            imageProxy: ImageProxy
    ) {
        val inputImage =
                InputImage.fromMediaImage(imageProxy.image!!, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    readerBarcodeData(barcodes)
                }
                .addOnFailureListener {
                    it.message?.let { it1 -> Log.e(TAG, it1) }
                }.addOnCompleteListener {
                    imageProxy.close()
                }
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_CAMERA_REQUEST) {
            if (isCameraPermissionGranted()) {
                bindCameraUseCases()
            } else {
                Log.e(TAG, "no camera permission")
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
                baseContext,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun readerBarcodeData(barcodes: List<Barcode>) {
        for (barcode in barcodes) {
            val bounds = barcode.boundingBox
            val corners = barcode.cornerPoints

            val rawValue = barcode.rawValue

            when (barcode.valueType) {
                Barcode.TYPE_URL -> {
                    val title = barcode.url!!.title
                    val url = barcode.url!!.url
                    qrCodeTextView.text = ""
                    showText(title)
                    showText(url)
                }
                Barcode.TYPE_EMAIL -> {
                    val address = barcode.email!!.address
                    val subject = barcode.email!!.subject
                    val body = barcode.email!!.body
                    qrCodeTextView.text = ""
                    showText(address)
                    showText(subject)
                    showText(body)
                }
                Barcode.TYPE_WIFI -> {
                    val wifiName = barcode.wifi!!.ssid
                    val pass = barcode.wifi!!.password
                    qrCodeTextView.text = ""
                    showText(wifiName)
                    showText(pass)
                }
            }
        }
    }

    private fun showText(text: String?) {
        qrCodeTextView.append(text + "\n")
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val PERMISSION_CAMERA_REQUEST = 1

        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}