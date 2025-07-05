package android.vendor.drwsiness_tf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque

class EyeAnalyzerCamera2(
    private val context: Context,
    private val onResult: (DetectionResult) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "EyeAnalyzer"
        private const val IMAGE_SIZE = 150
        private const val PROB_THRESHOLD = 0.5f
    }

    private lateinit var eyeCascade: CascadeClassifier
    private lateinit var tfliteInterpreter: Interpreter
    private val probabilityHistory = ArrayDeque<Float>()
    private var lastSmoothed = 0f

    init {
        if (!initializeComponents()) {
            throw IllegalStateException("Failed to initialize required components")
        }
    }

    private fun initializeComponents(): Boolean {
        return initOpenCv() && initTensorFlow()
    }

    private fun initOpenCv(): Boolean {
        return try {
            if (!OpenCVLoader.initDebug()) {
                onError("OpenCV initialization failed")
                false
            } else {
                initCascadeClassifier()
                true
            }
        } catch (e: Exception) {
            onError("OpenCV init error: ${e.message}")
            false
        }
    }

    private fun initCascadeClassifier() {
        val cascadeFile = File(context.cacheDir, "haarcascade_eye.xml").apply {
            if (!exists()) {
                context.assets.open("haarcascade_eye.xml").use { input ->
                    FileOutputStream(this).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        eyeCascade = CascadeClassifier(cascadeFile.absolutePath)
        if (eyeCascade.empty()) {
            throw IllegalStateException("Failed to load eye cascade")
        }
    }

    private fun initTensorFlow(): Boolean {
        return try {
            tfliteInterpreter = Interpreter(
                FileUtil.loadMappedFile(context, "model.tflite"),
                Interpreter.Options().apply {
                    setUseNNAPI(false)
                    setNumThreads(1)
                }
            )
            true
        } catch (e: Exception) {
            onError("TF Lite init failed: ${e.message}")
            false
        }
    }

    fun analyze(image: Image) {
        try {
            val bitmap = YuvToRgbConverter.yuvToRgb(context, image)
            val grayMat = Mat().apply {
                Utils.bitmapToMat(bitmap, this)
                Imgproc.cvtColor(this, this, Imgproc.COLOR_RGB2GRAY)
                Imgproc.equalizeHist(this, this)
            }

            val eyes = MatOfRect()
            eyeCascade.detectMultiScale(grayMat, eyes, 1.1, 3, 0, Size(30.0, 30.0), Size())

            if (eyes.empty()) {
                val fallbackStatus = if (lastSmoothed > PROB_THRESHOLD) "OPEN" else "CLOSED"
                val fallbackColor = if (lastSmoothed > PROB_THRESHOLD) Color.GREEN else Color.RED
                onResult(DetectionResult(fallbackStatus, lastSmoothed, fallbackColor))
                return
            }

            val predictions = eyes.toArray().mapNotNull { eye ->
                val x = eye.x.coerceAtLeast(0)
                val y = eye.y.coerceAtLeast(0)
                val width = eye.width.coerceAtMost(bitmap.width - x)
                val height = eye.height.coerceAtMost(bitmap.height - y)
                val eyeBitmap = Bitmap.createBitmap(bitmap, x, y, width, height)
                detectEyeState(eyeBitmap)
            }

            if (predictions.isNotEmpty()) {
                val avgProb = predictions.map { it.confidence }.average().toFloat()
                val smoothed = smoothProbability(avgProb)
                lastSmoothed = smoothed
                val status = if (smoothed > PROB_THRESHOLD) "OPEN" else "CLOSED"
                val color = if (smoothed > PROB_THRESHOLD) Color.GREEN else Color.RED
                onResult(DetectionResult(status, smoothed, color))
            }
        } catch (e: Exception) {
            onError("Analysis error: ${e.message}")
        }
    }

    private fun detectEyeState(bitmap: Bitmap): DetectionResult? {
        return try {
            val inputImage = TensorImage(DataType.FLOAT32).apply {
                load(bitmap)
                val processor = ImageProcessor.Builder()
                    .add(ResizeOp(IMAGE_SIZE, IMAGE_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                    .add(NormalizeOp(0f, 255f))
                    .add(NormalizeOp(0.5f, 0.5f))
                    .build()
                processor.process(this)
            }

            val outputTensor = tfliteInterpreter.getOutputTensor(0)
            val outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), outputTensor.dataType())

            tfliteInterpreter.run(inputImage.buffer, outputBuffer.buffer.rewind())

            val probability: Float = when (outputTensor.dataType()) {
                DataType.FLOAT32 -> outputBuffer.floatArray[0]
                DataType.UINT8 -> (outputBuffer.buffer.get().toInt() and 0xFF).toFloat() / 255f
                else -> throw IllegalStateException("Unsupported output type")
            }.coerceIn(0f, 1f)

            DetectionResult("", probability, Color.WHITE)
        } catch (e: Exception) {
            onError("Inference error: ${e.message}")
            null
        }
    }

    private fun smoothProbability(current: Float): Float {
        probabilityHistory.addLast(current)
        if (probabilityHistory.size > 5) {
            probabilityHistory.removeFirst()
        }
        return probabilityHistory.average().toFloat()
    }

    fun close() {
        tfliteInterpreter.close()
    }

    data class DetectionResult(
        val status: String,
        val confidence: Float,
        val color: Int
    )
}