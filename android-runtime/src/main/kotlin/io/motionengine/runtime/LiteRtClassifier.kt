package io.motionengine.runtime

import io.motionengine.core.*
import kotlinx.serialization.decodeFromString
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Offline CPU inference. A model bundle is motion.tflite + manifest.json in the same directory. */
class LiteRtClassifier private constructor(
    override val manifest: ModelManifest,
    private val interpreter: Interpreter,
    // Keep direct model storage alive for the entire interpreter lifetime.
    @Suppress("unused") private val modelBuffer: ByteBuffer,
) : MotionClassifier {
    private val input = ByteBuffer.allocateDirect(manifest.windowSize * (manifest.featureCount + 1) * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(manifest.classes.size * 4).order(ByteOrder.nativeOrder())
    private var closed = false

    override fun classify(window: FeatureWindow): FloatArray {
        check(!closed) { "Classifier is closed" }
        require(window.steps == manifest.windowSize && window.channels == manifest.featureCount + 1 &&
            window.data.size == window.steps * window.channels)
        input.clear(); input.asFloatBuffer().put(window.data)
        output.clear()
        interpreter.run(input, output)
        output.rewind()
        return FloatArray(manifest.classes.size) { output.float }
    }

    override fun close() {
        if (!closed) { closed = true; interpreter.close() }
    }

    companion object {
        fun fromDirectory(directory: File, threads: Int = 2): LiteRtClassifier {
            require(threads in 1..8)
            val manifest = MotionJson.format.decodeFromString<ModelManifest>(File(directory, "manifest.json").readText())
            manifest.validate()
            val model = File(directory, manifest.modelFile).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
            require(digest == manifest.sha256) { "Model checksum does not match manifest" }
            val buffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).apply { put(model); rewind() }
            val interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(threads))
            try {
                require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1)
                val input = interpreter.getInputTensor(0); val output = interpreter.getOutputTensor(0)
                require(input.dataType() == DataType.FLOAT32 && output.dataType() == DataType.FLOAT32)
                require(input.shape().contentEquals(intArrayOf(1, manifest.windowSize, manifest.featureCount + 1))) { "Unexpected input tensor shape" }
                require(output.shape().contentEquals(intArrayOf(1, manifest.classes.size))) { "Unexpected output tensor shape" }
                return LiteRtClassifier(manifest, interpreter, buffer)
            } catch (error: Throwable) {
                interpreter.close()
                throw error
            }
        }
    }
}
