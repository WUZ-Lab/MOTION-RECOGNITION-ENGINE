package io.motionengine.sample

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import io.motionengine.core.*
import io.motionengine.mediapipe.MediaPipePoseAdapter
import io.motionengine.runtime.LiteRtClassifier
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Collection labels are only written to disk; they are never passed to MotionEngine. */
class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var cameraRunning = false
    private var landmarker: PoseLandmarker? = null // worker confined
    private var lastCameraTimestamp: Long? = null // worker confined
    private var engine: MotionEngine? = null // worker confined
    private var recorder: BufferedWriter? = null // worker confined
    private var lastSession: File? = null
    private var lastPredictions: File? = null
    private var modelDescription = "모델 미설치 · 좌표 수집 가능"
    private lateinit var preview: PreviewView
    private lateinit var overlay: PoseView
    private lateinit var status: TextView
    private lateinit var modelStatus: TextView
    private lateinit var participant: EditText
    private lateinit var label: Spinner
    private lateinit var recordButton: Button
    private var recording = false // UI confined
    private val poseFile get() = File(filesDir, "pose_landmarker_lite.task")

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showStatus("카메라 권한이 필요합니다. 좌표 파일 재생은 사용할 수 있습니다.")
    }
    private val importPose = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            stopStreams()
            work {
                val temp = File(filesDir, "pose-import.task")
                contentResolver.openInputStream(uri)!!.use { copyLimited(it, temp, 32L * 1024 * 1024) }
                val candidate = createLandmarker(temp)
                candidate.close()
                temp.copyTo(poseFile, overwrite = true); temp.delete()
                showStatus("Pose 모델 설치 완료. 카메라 시작을 누르세요.")
            }
        }
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            stopStreams()
            work {
                val directory = File(filesDir, "models/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val names = mutableSetOf<String>()
                    ZipInputStream(contentResolver.openInputStream(uri)!!).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            require(!entry.isDirectory && entry.name in setOf("manifest.json", "motion.tflite") && names.add(entry.name)) {
                                "ZIP에는 manifest.json과 motion.tflite만 한 개씩 있어야 합니다."
                            }
                            copyLimited(zip, File(directory, entry.name), if (entry.name.endsWith("json")) 1024L * 1024 else 32L * 1024 * 1024)
                            zip.closeEntry(); entry = zip.nextEntry
                        }
                    }
                    require(names == setOf("manifest.json", "motion.tflite"))
                    val classifier = LiteRtClassifier.fromDirectory(directory)
                    val replacement = try { MotionEngine(classifier) } catch (error: Throwable) { classifier.close(); throw error }
                    engine?.close(); engine = replacement
                    modelDescription = if (classifier.manifest.trainingData == "synthetic")
                        "합성 데이터 검증 모델 · 실제 운동 인식용 아님" else "운동 모델 연결됨 · 확신도는 모델 점수"
                    getPreferences(MODE_PRIVATE).edit().putString("modelDirectory", directory.name).remove("guidedExercise").apply()
                    runOnUiThread { modelStatus.text = modelDescription }
                    showStatus("운동 모델 설치 완료")
                } catch (error: Throwable) { directory.deleteRecursively(); throw error }
            }
        }
    }
    private val replayFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replay(uri)
    }
    private val exportSession = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) work {
            recorder?.flush()
            val file = requireNotNull(lastSession) { "먼저 좌표를 수집하세요." }
            contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            showStatus("좌표 파일 내보내기 완료")
        }
    }
    private val exportPredictions = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) work {
            val file = requireNotNull(lastPredictions) { "모델을 연결하고 좌표 파일을 재생하세요." }
            contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            showStatus("예측 결과 내보내기 완료")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 32, 16, 24)
        }
        root.addView(TextView(this).apply { text = "Motion Engine Lab"; textSize = 23f })
        modelStatus = TextView(this).apply { text = modelDescription }
        root.addView(modelStatus)
        root.addView(TextView(this).apply { text = "한 명 · 전신 · 고정 카메라 · 사선 촬영\n진행률과 분류 확신도는 서로 다른 값입니다." })
        val frame = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = PoseView(this)
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "Pose 모델을 가져온 뒤 카메라를 시작하세요."; textSize = 16f; minLines = 3 }
        root.addView(status)
        participant = EditText(this).apply {
            hint = "익명 참가자 ID (같은 사람은 같은 ID)"
            setSingleLine(true)
            setText(getPreferences(MODE_PRIVATE).getString("participant", ""))
        }
        root.addView(participant)
        label = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Exercises.builtIn) }
        root.addView(label)
        fun row(vararg buttons: Button) {
            root.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } })
        }
        fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action() } }
        row(button("Pose 가져오기") { importPose.launch(arrayOf("*/*")) }, button("운동 모델 ZIP") { importModel.launch(arrayOf("*/*")) })
        row(button("스쿼트 모드") {
            stopStreams()
            work { useGuidedSquat() }
        }, button("자동 인식 모드") {
            stopStreams()
            work { loadSavedModel(required = true) }
        })
        recordButton = button("수집 시작") { toggleRecording() }
        row(button("카메라 시작") { startCamera() }, recordButton, button("중지") { stopStreams() })
        row(button("좌표 재생") { replayFile.launch(arrayOf("*/*")) }, button("좌표 내보내기") { exportSession.launch("session.jsonl") },
            button("예측 내보내기") { exportPredictions.launch("predictions.jsonl") })
        setContentView(root)
        work {
            if (getPreferences(MODE_PRIVATE).getString("guidedExercise", null) == Exercises.SQUAT) useGuidedSquat()
            else loadSavedModel()
        }
    }

    private fun useGuidedSquat() {
        engine?.close()
        engine = MotionEngine(mode = RecognitionMode.Guided(Exercises.SQUAT))
        modelDescription = "스쿼트 지정 모드 · 자세 단계로 횟수 측정"
        getPreferences(MODE_PRIVATE).edit().putString("guidedExercise", Exercises.SQUAT).apply()
        runOnUiThread { modelStatus.text = modelDescription }
        showStatus("스쿼트 모드 준비 완료 · 전신이 보이게 서서 시작하세요.")
    }

    private fun loadSavedModel(required: Boolean = false) {
        val name = getPreferences(MODE_PRIVATE).getString("modelDirectory", null)
        if (name == null) { require(!required) { "자동 인식에는 운동 모델 ZIP이 필요합니다." }; return }
        val classifier = LiteRtClassifier.fromDirectory(File(filesDir, "models/$name"))
        val replacement = try { MotionEngine(classifier) } catch (error: Throwable) { classifier.close(); throw error }
        engine?.close(); engine = replacement
        modelDescription = if (classifier.manifest.trainingData == "synthetic") "합성 데이터 검증 모델 · 실제 운동 인식용 아님" else "운동 모델 연결됨"
        getPreferences(MODE_PRIVATE).edit().remove("guidedExercise").apply()
        runOnUiThread { modelStatus.text = modelDescription }
    }

    private fun createLandmarker(file: File): PoseLandmarker = PoseLandmarker.createFromOptions(this,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(file.absolutePath).build())
            .setRunningMode(RunningMode.VIDEO).setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f).setMinPosePresenceConfidence(0.5f).setMinTrackingConfidence(0.5f).build())

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        stopStreams()
        val token = generation.get()
        work {
            require(poseFile.exists()) { "먼저 MediaPipe pose_landmarker_lite.task 파일을 가져오세요." }
            landmarker = createLandmarker(poseFile)
            lastCameraTimestamp = null
            engine?.reset()
            runOnUiThread {
                if (generation.get() != token || isDestroyed) return@runOnUiThread
                val future = ProcessCameraProvider.getInstance(this)
                future.addListener({
                    try {
                        if (generation.get() != token) return@addListener
                        provider = future.get()
                        val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(worker) { image ->
                            try {
                                if (generation.get() == token) {
                                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                                    if (lastCameraTimestamp?.let { timestamp <= it } == true) return@setAnalyzer
                                    lastCameraTimestamp = timestamp
                                    val raw = image.toBitmap()
                                    val rotation = image.imageInfo.rotationDegrees
                                    val bitmap = if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                                        Matrix().apply { postRotate(rotation.toFloat()) }, true)
                                    val mp = BitmapImageBuilder(bitmap).build()
                                    try {
                                        val result = landmarker!!.detectForVideo(mp, timestamp)
                                        val pose = MediaPipePoseAdapter.fromResult(result, bitmap.width, bitmap.height)
                                        recorder?.apply { write(MotionJson.format.encodeToString(FrameRecord(timestamp, pose))); newLine() }
                                        display(pose, engine?.let { if (pose == null) it.processMissing(timestamp) else it.process(pose) })
                                    } finally { mp.close(); if (bitmap !== raw) bitmap.recycle(); raw.recycle() }
                                }
                            } catch (error: Exception) { showStatus("처리 오류: ${error.message}") }
                            finally { image.close() }
                        }
                        provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
                        cameraRunning = true
                        showStatus("카메라 준비 완료 · 수집할 운동 라벨을 선택하세요.")
                    } catch (error: Exception) { showStatus("카메라 오류: ${error.message}") }
                }, ContextCompat.getMainExecutor(this))
            }
        }
    }

    private fun toggleRecording() {
        if (recording) {
            recording = false; recordButton.text = "수집 시작"
            work { recorder?.close(); recorder = null; showStatus("수집 완료 · 좌표 내보내기를 누르세요.") }
            return
        }
        val id = participant.text.toString().trim()
        if (id.isBlank()) { showStatus("익명 참가자 ID를 입력하세요."); return }
        val exercise = label.selectedItem.toString()
        getPreferences(MODE_PRIVATE).edit().putString("participant", id).apply()
        recording = true; recordButton.text = "수집 종료"
        work {
            require(landmarker != null && cameraRunning) { "카메라를 먼저 시작하세요." }
            val sessionId = UUID.randomUUID().toString()
            val file = File(filesDir, "sessions/$sessionId.jsonl").apply { parentFile!!.mkdirs() }
            val poseHash = MessageDigest.getInstance("SHA-256").digest(poseFile.readBytes()).joinToString("") { "%02x".format(it) }
            val header = SessionHeader(sessionId = sessionId, participantId = id, exerciseLabel = exercise,
                poseModel = "imported.task", poseSettings = mapOf("numPoses" to "1", "minConfidence" to "0.5",
                    "sha256" to poseHash, "timestampClock" to "camera_image_nanoseconds_to_milliseconds"))
            recorder = file.bufferedWriter().also { it.write(MotionJson.format.encodeToString(header)); it.newLine() }
            lastSession = file
            showStatus("수집 중: $exercise · 라벨은 학습 파일에만 저장됩니다.")
        }
    }

    private fun replay(uri: Uri) {
        stopStreams()
        val token = generation.get()
        work {
            engine?.reset()
            val predictions = if (engine != null) File(filesDir, "predictions/${UUID.randomUUID()}.jsonl").apply { parentFile!!.mkdirs() } else null
            predictions?.bufferedWriter().use { writer ->
                contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
                    val header = MotionJson.format.decodeFromString<SessionHeader>(requireNotNull(reader.readLine()))
                    require(header.schemaVersion == 1 && header.type == "session")
                    var previous: Long? = null
                    for (line in reader.lineSequence()) {
                        if (generation.get() != token) break
                        val frame = MotionJson.format.decodeFromString<FrameRecord>(line)
                        require(frame.type == "frame" && (frame.pose == null || frame.pose?.timestampMs == frame.timestampMs))
                        require(previous == null || frame.timestampMs > previous!!)
                        if (previous != null) Thread.sleep((frame.timestampMs - previous!!).coerceAtMost(100))
                        previous = frame.timestampMs
                        if (generation.get() != token) break
                        val result = engine?.let { e -> frame.pose?.let(e::process) ?: e.processMissing(frame.timestampMs) }
                        if (result != null) { writer?.write(MotionJson.format.encodeToString(result)); writer?.newLine() }
                        display(frame.pose, result)
                    }
                }
            }
            lastPredictions = predictions
            showStatus(if (generation.get() == token) "재생 완료 · ${if (predictions == null) "모델 미설치" else "예측 결과를 내보낼 수 있습니다."}" else "재생 중지")
        }
    }

    private fun display(pose: PoseFrame?, result: RecognitionResult?) = runOnUiThread {
        overlay.frame = pose
        status.text = if (result == null) "${if (recording) "수집 중 · " else ""}$modelDescription" else {
            val current = result.current
            val classification = if (current.source == RecognitionSource.GUIDED) "지정 운동" else "모델 점수 ${(current.confidence * 100).toInt()}%"
            "${current.exerciseId ?: current.status.name} · $classification\n" +
                "진행률 ${current.progress?.let { "${(it * 100).toInt()}%" } ?: "—"} · ${current.phase ?: "—"} · ${result.completedReps}회" +
                if (result.events.isNotEmpty()) " ✓" else ""
        }
    }

    private fun stopStreams() {
        generation.incrementAndGet(); cameraRunning = false; provider?.unbindAll()
        recording = false
        if (::recordButton.isInitialized) recordButton.text = "수집 시작"
        work { recorder?.close(); recorder = null; landmarker?.close(); landmarker = null; engine?.reset() }
    }

    private fun showStatus(message: String) = runOnUiThread { if (!isDestroyed) status.text = message }
    private fun work(block: () -> Unit) {
        if (!worker.isShutdown) worker.execute {
            try { block() } catch (error: Exception) {
                showStatus(error.message ?: error.javaClass.simpleName)
                runOnUiThread { if (recorder == null) { recording = false; recordButton.text = "수집 시작" } }
            }
        }
    }
    private fun copyLimited(input: InputStream, file: File, limit: Long) {
        file.outputStream().use { output ->
            val buffer = ByteArray(8192); var total = 0L
            while (true) {
                val read = input.read(buffer); if (read < 0) break
                total += read; require(total <= limit) { "파일 크기 제한을 초과했습니다." }; output.write(buffer, 0, read)
            }
        }
    }
    override fun onStop() { stopStreams(); super.onStop() }
    override fun onDestroy() {
        work { recorder?.close(); landmarker?.close(); engine?.close() }
        worker.shutdown(); super.onDestroy()
    }
}
