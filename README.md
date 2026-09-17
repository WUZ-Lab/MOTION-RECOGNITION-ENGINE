# Motion Recognition Engine

MediaPipe 좌표에서 **운동별 반복 횟수·1회 동작 진행률·완료 이벤트**를 반환하는 Android 우선 KMP SDK입니다. 자동 모드에서는 운동 종류도 분류합니다.

```text
사용 앱의 카메라 → MediaPipe → 33개 영상·world 좌표 + 타임스탬프
                              ├─ 지정 모드: 선택한 운동의 단계 추적 → 횟수·진행률
                              └─ 자동 모드: 운동 분류 + 단계 추적 → 운동 종류·횟수·진행률
```

**현재 제공 범위:** SDK, Android 수집·재생 앱, 브라우저 라벨링 도구, TCN 학습·LiteRT 변환·평가 도구.
실제 수집 데이터로 학습한 운동 모델은 포함되어 있지 않습니다. 진행률 임계값은 초기 기준이며, 합성 데이터 테스트 통과는 실제 인식 정확도를 의미하지 않습니다.

## 사용 순서

1. [사용 모드 선택](#사용-모드-선택)
2. [다른 Android 앱에 SDK 추가](#다른-android-앱에-sdk-추가)
3. [모델 파일 준비](#모델-파일-준비)
4. [엔진 생성과 프레임 처리](#sdk-사용)
5. [결과 표시와 수명 관리](#결과-표시와-수명-관리)
6. [학습한 모델 교체](#학습한-모델-교체)

전체 카메라 구현을 먼저 실행하려면 [샘플 앱 실행](#샘플-앱-실행)을 참고하세요.

## 사용 모드 선택

| 앱의 기능 | 모드 | 운동 분류 모델 |
| --- | --- | --- |
| 사용자가 스쿼트 등 운동을 선택하고 횟수를 셈 | `RecognitionMode.Guided(exerciseId)` | 불필요 |
| 카메라 속 운동을 자동으로 구분하고 횟수를 셈 | `RecognitionMode.Auto` — 분류기를 전달할 때 기본값 | 필요 |

지정 모드는 선택한 운동의 관절 움직임을 추적합니다. 다른 운동을 자동으로 구분하거나 올바른 자세를 평가하는 기능은 아닙니다. 카메라 입력을 MediaPipe 좌표로 바꾸는 **Pose 모델은 두 모드 모두 필요**합니다. 앱이 이미 호환 좌표를 공급한다면 SDK에 `PoseFrame`을 직접 전달할 수도 있습니다.

지원 운동은 `squat`, `burpee`, `jump_squat`, `push_up`이며 `other`는 자동 분류의 대기·기타 클래스입니다. `Guided(other)`는 사용할 수 없습니다. 버피는 **서기 → 몸 낮추기 → 플랭크 → 다리 당기기 → 점프·착지**로 정의하며 푸쉬업은 필수 단계가 아닙니다.

현재 실행 런타임은 Android용입니다. 촬영 기준은 **한 사람·전신 노출·고정된 사선 카메라**입니다.

## 다른 Android 앱에 SDK 추가

### 1. GitHub Packages 저장소와 인증 설정

SDK는 `WUZ-Lab/MOTION-RECOGNITION-ENGINE`의 GitHub Packages로 배포하도록 구성되어 있습니다. **아래 `0.1.1` 예제는 `v0.1.1` 태그의 첫 배포가 성공한 뒤 사용할 수 있습니다.** 실제 버전은 [저장소의 Packages](https://github.com/WUZ-Lab/MOTION-RECOGNITION-ENGINE/packages)에서 확인하세요. 배포 방법은 [GitHub Packages에 배포](#github-packages에-배포)에 있습니다.

GitHub의 Gradle 패키지는 public이어도 인증이 필요합니다. 설치할 계정에 패키지 읽기 권한을 부여하고, `read:packages` 권한의 **Personal access token (classic)**을 준비합니다. 조직에서 SSO를 요구한다면 해당 조직에 토큰을 승인해야 합니다. [GitHub 인증 문서](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry)

개인 PC의 **`~/.gradle/gradle.properties`**에 저장합니다. 토큰을 저장소의 `gradle.properties`에 넣거나 커밋하지 마세요.

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_PERSONAL_ACCESS_TOKEN_CLASSIC
```

아래 설정을 **사용할 앱 프로젝트**의 기존 `settings.gradle.kts` 블록에 병합합니다.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/wuz-lab/MOTION-RECOGNITION-ENGINE")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("io.motionengine") }
        }
    }
}
```

다른 프로젝트의 CI에서는 읽기 권한이 있는 토큰을 CI secret에 저장하고 `GITHUB_TOKEN`으로 전달합니다. `GITHUB_ACTOR`에는 그 토큰 소유자의 GitHub 사용자명을 전달하세요. 다른 저장소의 기본 `GITHUB_TOKEN`에 이 패키지 접근 권한이 자동으로 주어지는 것은 아닙니다.

### 2. 사용할 앱의 의존성 설정

필요 환경: **JDK 21, Android SDK 36**. 이 저장소는 Gradle Wrapper 8.13, Kotlin 2.2.20, AGP 8.13.2를 사용하며 Java 17 바이트코드를 생성합니다. `local.properties`의 `sdk.dir` 또는 `ANDROID_HOME`에 Android SDK 경로를 지정하세요.

앱 모듈의 기존 `build.gradle.kts` 블록에 병합합니다.

```kotlin
android {
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        noCompress += listOf("tflite", "task")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation("io.motionengine:android-runtime:0.1.1")
    implementation("io.motionengine:mediapipe-adapter:0.1.1")
}
```

`core`, LiteRT, MediaPipe 의존성은 위 모듈을 통해 함께 해결됩니다. 지정 모드와 MediaPipe만 사용한다면 `android-runtime`은 생략할 수 있습니다. 직접 만든 `PoseFrame`만 사용하는 지정 모드 앱은 `io.motionengine:core:0.1.1`만 추가할 수 있습니다. SDK 모듈은 모두 같은 버전을 사용하세요.

### 로컬에서 SDK를 수정하며 사용하는 경우

원격 배포 없이 같은 PC에서 사용하려면 SDK 프로젝트 루트에서 실행합니다. 이 명령에는 GitHub 토큰이 필요하지 않습니다.

```sh
./gradlew :core:publishToMavenLocal \
  :android-runtime:publishToMavenLocal \
  :mediapipe-adapter:publishToMavenLocal
```

사용할 앱의 저장소 설정에서 위 GitHub Packages 블록을 `mavenLocal { content { includeGroup("io.motionengine") } }`로 바꾸고 SDK 의존성 버전은 `0.1.0-SNAPSHOT`으로 지정합니다. 로컬 아티팩트는 해당 PC에만 있습니다. 같은 저장소에 포함된 앱에서는 Maven 좌표 대신 `implementation(project(":android-runtime"))`, `implementation(project(":mediapipe-adapter"))`를 사용할 수 있습니다.

### 3. 카메라와 포즈 추출 연결

카메라 프리뷰, CameraX 의존성, 이미지 회전 처리와 권한 요청은 사용하는 앱에서 구현합니다. 카메라를 사용한다면 앱 manifest에 권한을 선언하고 실행 중 권한도 요청하세요.

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

MediaPipe는 **한 명만 추적하도록 `numPoses = 1`**로 설정합니다. 샘플은 `RunningMode.VIDEO`의 `detectForVideo()`를 CameraX 분석용 단일 작업 스레드에서 호출합니다. 영상 좌표와 크기는 **회전 보정 후, 프리뷰 좌우 반전 전** 기준이어야 합니다.

CameraX 설정·MediaPipe 생성·회전 보정의 전체 예제는 [MainActivity.kt](sample-android/src/main/kotlin/io/motionengine/sample/MainActivity.kt), 카메라 의존성은 [샘플 Gradle 설정](sample-android/build.gradle.kts)을 참고하세요. MediaPipe 비동기 모드를 사용하는 앱도 엔진에는 결과를 타임스탬프 순서대로 전달해야 합니다.

## 모델 파일 준비

| 파일 | 역할 | 필요한 모드 |
| --- | --- | --- |
| `pose_landmarker_lite.task` 등 MediaPipe Pose 모델 | 카메라 영상 → 관절 좌표 | MediaPipe로 좌표를 추출하는 두 모드 |
| `motion.tflite` + `manifest.json` | 관절 시계열 → 운동 종류 | 자동 모드 |

Pose 모델은 아래 스크립트로 받을 수 있습니다.

```sh
python3 scripts/download_pose_model.py --output artifacts/pose_landmarker_lite.task
```

사용 앱의 MediaPipe 로더에서 읽을 위치에 `.task` 파일을 넣으세요. 예를 들어 `app/src/main/assets/pose_landmarker_lite.task`에 넣고 MediaPipe의 `BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build()`로 지정합니다. 샘플 앱에서는 **Pose 가져오기**로 선택합니다.

자동 모드의 운동 모델은 [실제 데이터 학습 절차](#training)로 생성하거나, 그 규격에 맞게 검증한 번들을 준비합니다. `artifacts/revision-*`, `artifacts/smoke-model` 등의 합성 데이터 모델은 연결 검증용이며 실사용 정확도가 검증된 모델이 아닙니다.

앱에 묶어 배포하는 경우:

```text
app/src/main/assets/
├─ pose_landmarker_lite.task
└─ motion-model/
   ├─ manifest.json
   └─ motion.tflite
```

앱 내부 저장소에서 로딩하는 경우에는 ZIP을 미리 풀어 두 파일이 같은 폴더에 있도록 준비합니다. `fromDirectory()`는 ZIP을 직접 읽거나 다운로드하지 않습니다.

## SDK 사용

### 1. 엔진 생성: 지정 모드 또는 자동 모드

엔진은 프레임마다 만들지 않고 **운동 세션 동안 유지**합니다. 생성·추론은 단일 백그라운드 작업 스레드에서 수행하고, UI만 메인 스레드에서 갱신하세요.

**스쿼트 횟수만 세는 앱:**

```kotlin
import io.motionengine.core.Exercises
import io.motionengine.core.MotionEngine
import io.motionengine.core.RecognitionMode

val engine = MotionEngine(
    mode = RecognitionMode.Guided(Exercises.SQUAT),
)
```

다른 운동을 선택하려면 `Exercises.PUSH_UP`, `Exercises.JUMP_SQUAT`, `Exercises.BURPEE`를 사용합니다. 운동 변경 시 새 엔진을 생성하고 기존 엔진은 종료합니다.

**운동 종류도 자동으로 구분하는 앱:**

```kotlin
import io.motionengine.core.MotionEngine
import io.motionengine.runtime.LiteRtClassifier

// 앱에서 정의하는 헬퍼입니다. 엔진 생성에 실패하면 분류기 자원을 해제합니다.
fun createAutoEngine(classifier: LiteRtClassifier): MotionEngine = try {
    MotionEngine(classifier)
} catch (error: Throwable) {
    classifier.close()
    throw error
}

// context는 사용하는 앱의 Android Context입니다.
var engine = createAutoEngine(LiteRtClassifier.fromAssets(context))
```

위 예제는 `assets/motion-model/manifest.json`과 `motion.tflite`를 읽습니다. 다른 assets 폴더는 `fromAssets(context, assetDirectory = "models/squat")`로 지정합니다. 파일로 저장한 번들은 생성 부분을 다음과 같이 바꿉니다.

```kotlin
import java.io.File

val modelDirectory = File(context.filesDir, "models/v1")
var engine = createAutoEngine(LiteRtClassifier.fromDirectory(modelDirectory))
```

분류기 생성 시 manifest, SHA-256, 입력·출력 텐서 규격을 검사합니다. 엔진이 생성된 뒤에는 엔진이 분류기를 소유하므로 별도로 `classifier.close()`를 호출하지 않습니다.

### 2. MediaPipe 결과를 프레임마다 전달

아래 함수는 두 모드에서 공통으로 사용하는 **앱 측 연결 예제**입니다. CameraX 분석기에서 MediaPipe 결과를 얻은 뒤 호출합니다.

```kotlin
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import io.motionengine.core.MotionEngine
import io.motionengine.core.RecognitionResult
import io.motionengine.mediapipe.MediaPipePoseAdapter

fun processPoseResult(
    engine: MotionEngine,
    mediaPipeResult: PoseLandmarkerResult,
    uprightWidth: Int,
    uprightHeight: Int,
): RecognitionResult {
    val pose = MediaPipePoseAdapter.fromResult(
        mediaPipeResult, uprightWidth, uprightHeight,
    )
    return if (pose != null) {
        engine.process(pose)
    } else {
        engine.processMissing(mediaPipeResult.timestampMs())
    }
}
```

- `uprightWidth`, `uprightHeight`는 MediaPipe에 전달한 **회전 보정된 영상의 픽셀 크기**입니다. 프리뷰 뷰의 크기가 아닙니다.
- 타임스탬프는 같은 캡처 시계를 사용하는 음수 아닌 밀리초이며 매 호출마다 엄격히 증가해야 합니다. CameraX의 `image.imageInfo.timestamp / 1_000_000L`을 MediaPipe에도 전달하는 방식은 샘플에 구현되어 있습니다. 중복·역순 프레임은 전달하지 마세요.
- 사람이 검출되지 않은 결과도 같은 시각으로 `processMissing()`을 호출합니다. 정상 좌표를 복제해 빈 구간을 채우지 않습니다.
- 엔진 한 개는 한 사람을 처리합니다. 직접 `PoseFrame`을 만드는 앱은 MediaPipe 순서의 영상 좌표 33개와 world 좌표 33개를 모두 제공해야 합니다. 상세 규격은 [데이터 형식](docs/data-format.md)을 참고하세요.

## 결과 표시와 수명 관리

### 횟수·진행률·완료 이벤트

`result`는 위 `processPoseResult()`의 반환값입니다.

```kotlin
import io.motionengine.core.Exercises

val squatCount = result.repsByExercise[Exercises.SQUAT] ?: 0
val totalCount = result.completedReps
val currentExercise = result.current.exerciseId
val progressPercent = result.current.progress?.times(100)?.toInt()

result.events.forEach { event ->
    // event.exerciseId: 확정된 운동 종류
    // event.repId: 세션 내 반복 ID
    // event.startedAtMs / endedAtMs: 실제 관측한 반복의 시작 / 완료 시각
    // event.emittedAtMs: 완료 확인을 마치고 이벤트를 반환한 시각
    // 이곳에서 반복 기록·완료 효과를 처리합니다.
}
```

| 값 | 앱에서 사용하는 방법 |
| --- | --- |
| `current` | 현재 프레임의 운동·진행률·단계·상태. 실시간 UI에 사용 |
| `current.progress` | 한 반복의 진행률 `0..0.99`. 준비 중이거나 판단 불가이면 `null`이므로 대기 표시 |
| `events` | 이번 호출에서 확정된 반복. 모든 항목을 처리하고 완료 효과는 여기서 실행 |
| `repsByExercise` / `completedReps` | 운동별 / 전체 누적 횟수. 카운터 화면은 이 값으로 갱신 |
| `quality` | `usableForTracking`, `usableForClassification`, `missingJoints`, `issue`로 입력 품질 확인 |

현재 동작과 지연된 완료 이벤트는 다를 수 있습니다. **진행률이 100%가 되는지 검사해 횟수를 세지 마세요.** 프레임당 여러 이벤트가 나올 수도 있으므로 `repCompleted == true`일 때 무조건 1을 더하는 방법도 사용하지 않습니다. 저장 시 이벤트 키는 앱의 세션 ID와 `repId`를 함께 사용합니다.

지정 모드는 `current.source = GUIDED`, `current.confidence = 0`, 이벤트의 `confidence = null`입니다. 이는 인식 실패를 뜻하지 않습니다. 자동 모드의 confidence는 모델 점수이며 보정된 정확도나 자세 점수가 아닙니다. 지정 모드에서 `quality.usableForClassification = false`도 정상입니다.

| `current.status` | 표시 예시 |
| --- | --- |
| `WARMING_UP` | 초기 좌표 수집 중 |
| `UNCERTAIN` | 운동을 확인하는 중 |
| `TRACKING` | 현재 운동과 진행률 표시 |
| `OTHER` | 운동 대기 중 |
| `POOR_POSE` | 전신이 보이도록 위치 조정 안내 |

기존 최상위 `exerciseId`, `confidence`, `progress`, `phase`, `status`, `repCompleted`는 호환용입니다. 이벤트가 있는 프레임에서는 첫 이벤트를 표시하므로 새 UI는 `current`와 `events`를 사용하세요.

### 초기화와 종료

`process()`, `processMissing()`, `reset()`, `close()`는 **동시에 호출하지 않습니다.** CameraX 분석과 엔진 제어를 같은 단일 실행 큐에서 순서대로 처리하세요. `Executors.newSingleThreadExecutor()`를 사용하는 전체 구현은 샘플 앱을 참고하세요.

```kotlin
// 새 운동 세션을 시작할 때, 프레임 처리와 같은 실행 큐에서 호출
engine.reset()

// 화면·세션을 종료할 때: 입력 중단 → 진행 중 작업 완료 → 자원 해제
engine.close()
```

두 호출은 서로 다른 수명 주기 시점에 사용하는 예입니다. `reset()`은 모델을 유지하면서 누적 횟수, 진행 상태, 타임스탬프 이력, 이벤트 ID를 초기화합니다. `close()`는 분류기까지 해제하며 반복 호출할 수 있습니다. 종료한 엔진을 다시 처리하거나 초기화할 수는 없습니다.

카메라 분석기·MediaPipe·작업 스레드는 앱이 소유합니다. 화면 종료나 모드 변경 시 이전 콜백이 새 엔진으로 들어오지 않도록 입력과 대기 작업을 정리하세요. UI로 넘긴 이전 세션 결과도 세션 ID로 구분합니다.

### 카운팅 설정과 입력 유실

지정 모드의 기본값은 `ProgressConfig()`, 자동 모드의 기본값은 `classifier.manifest.progress`입니다. `MotionEngine`의 `config`에 `TrackingConfig`를 전달하면 변경할 수 있고, 운동별 값은 `exerciseProgress` 맵에 지정합니다.

```kotlin
import io.motionengine.core.Exercises
import io.motionengine.core.MotionEngine
import io.motionengine.core.ProgressConfig
import io.motionengine.core.RecognitionMode
import io.motionengine.core.TrackingConfig

// 지정 모드용 설정 예시. 아래 값은 기본값이며 실제 데이터 검증 후 조정하세요.
val config = TrackingConfig(
    exerciseProgress = mapOf(
        Exercises.SQUAT to ProgressConfig(
            bentKneeAngle = 105f,
            minimumRepMs = 600,
            completionDelayMs = 500,
        ),
    ),
)
val engine = MotionEngine(mode = RecognitionMode.Guided(Exercises.SQUAT), config = config)
```

자동 모드에서 일부 값만 바꾸려면 `classifier.manifest.progress.copy(...)`로 운동별 설정을 만들고 `TrackingConfig(progress = classifier.manifest.progress, exerciseProgress = ...)`로 나머지 기본값을 유지합니다. 앱에서 명시한 설정이 manifest보다 우선합니다. 카운팅 설정은 학습에서 자동으로 산출되지 않습니다.

완료 이벤트는 기본 500ms의 확인 시간이 지난 뒤 후속 정상 프레임에서 반환됩니다. 스쿼트와 점프스쿼트를 구분하기 위한 지연이며, 같은 반복의 도약을 관측하면 스쿼트 완료 후보를 취소합니다. 마지막 동작 직후 프레임 전달을 끊으면 완료 이벤트를 받지 못할 수 있습니다.

반복 중 확인한 분류 근거는 보관하므로 현재 운동이 `other`로 바뀌어도 관측된 반복을 집계할 수 있습니다. 짧은 좌표 불량에는 완료 후보를 유지하되 확인 시간을 다시 시작하고, 불량 프레임 자체에서는 이벤트를 발행하지 않습니다. 마지막 정상 관측 이후 기본 500ms를 초과하면 해당 추적과 후보를 초기화하지만 누적 횟수는 유지합니다. 완료 후보 보관 한도는 기본 3초입니다. 관측하지 못한 바닥·착지 단계를 추정해 집계하지 않습니다.

스쿼트 지정 모드는 팔이 가려져도 필요한 어깨·골반·무릎·발목이 보이면 추적할 수 있습니다. 자동 분류는 더 많은 관절을 필요로 하므로 카운팅과 분류의 입력 품질이 다를 수 있습니다. 카운팅 임계값을 바꿔도 모델 특징 v1과 특징 시계열의 500ms 초기화 규칙은 변경되지 않습니다.

## 학습한 모델 교체

**입출력 규격과 지원 운동이 같다면 SDK 소스 수정 없이 모델 번들을 교체할 수 있습니다.** 새 학습 결과의 `motion.tflite`와 `manifest.json`을 항상 함께 배포하세요. manifest에는 클래스 순서, 표준화 값과 모델 해시 등이 들어 있습니다.

| 배포 방식 | 교체 절차 |
| --- | --- |
| `fromAssets()` | 앱의 모델 파일 두 개 교체 → 앱 빌드·배포 → 새 엔진 생성 |
| `fromDirectory()` | 앱에서 새 번들 다운로드·가져오기 → 새 디렉터리에 압축 해제 → 새 엔진 생성. SDK 재빌드는 불필요 |

파일을 바꿔도 이미 생성된 엔진에 자동 반영되지 않습니다. 새 폴더를 완전히 준비한 뒤 입력을 잠시 중단하고, **기존 처리 작업이 끝난 같은 실행 큐에서** 교체합니다. 앞서 정의한 `createAutoEngine()`을 사용하면 다음과 같습니다.

```kotlin
val nextDirectory = File(context.filesDir, "models/v2")
// 생성·검증에 실패하면 예외가 발생하며 기존 engine은 그대로 유지됩니다.
val replacement = createAutoEngine(LiteRtClassifier.fromDirectory(nextDirectory))
val previous = engine
engine = replacement
previous.close()
// 앱의 새 세션 ID를 발급하고 이후 프레임부터 입력을 재개합니다.
```

앱은 로딩 실패를 처리하고 기존 모델을 계속 사용할지 결정합니다. 새 엔진은 횟수·진행 상태·이벤트 ID가 초기화된 새 세션입니다. 세션 간 누적 기록은 앱에서 따로 보관하세요. 다운로드·ZIP 압축 해제·업데이트 시점 제어는 사용하는 앱에서 구현해야 합니다.

교체 가능한 현재 계약은 다음과 같습니다.

- `schemaVersion = 1`, `featureVersion = 1`, 30Hz, 180 프레임, 32개 특징 + 마스크.
- 입력 float32 `[1, 180, 33]`, 출력 float32 `[1, N]` softmax. 입력·출력 텐서는 각각 하나이며 실행 런타임이 모델 연산을 지원해야 합니다.
- `classes`에 `other`와 하나 이상의 운동을 포함하고, 운동별 `ExerciseDefinition`이 엔진에 등록되어 있어야 합니다. 기본 네 운동 안에서는 클래스 수·순서를 바꿀 수 있습니다.
- 학습과 SDK의 특징 계산·표준화 규칙이 같아야 합니다. 정확한 계약은 [데이터와 모델 규격](docs/data-format.md)을 참고하세요.

새 운동, 다른 특징 구성, 다른 텐서 규격이나 단계별 다중 출력 모델을 도입하면 트래커·특징 처리·런타임 등 해당 코드의 변경도 필요합니다. `Guided` 모드는 운동 분류기를 사용하지 않으므로 운동 모델 교체의 영향을 받지 않습니다.

## 샘플 앱 실행

SDK 프로젝트 루트에서 실행합니다.

```sh
python3 scripts/download_pose_model.py --output artifacts/pose_landmarker_lite.task
./gradlew :sample-android:assembleDebug

# Android 기기/에뮬레이터 연결 후 설치
adb install -r sample-android/build/outputs/apk/debug/sample-android-debug.apk
```

1. `pose_landmarker_lite.task`를 Android 기기에서 파일 선택기로 찾을 수 있는 위치에 복사합니다.
2. **Motion Engine Lab** 앱을 열고 **Pose 가져오기**로 `.task` 파일을 선택합니다.
3. **스쿼트 모드 → 카메라 시작**을 누르고 카메라 권한을 허용합니다. 전신이 보이도록 서서 준비한 뒤 스쿼트를 수행합니다.
4. 자동 분류는 검증한 `motion-model.zip`을 **운동 모델 ZIP**으로 가져오고 **자동 인식 모드 → 카메라 시작**을 사용합니다. ZIP 루트에는 `manifest.json`, `motion.tflite`가 있어야 합니다.
5. 기록 검증은 **좌표 재생 → 예측 내보내기**로 할 수 있습니다. 좌표 재생에는 카메라나 Pose 모델이 필요하지 않습니다.

운동 선택 드롭다운은 **수집할 데이터의 라벨**이며 인식 모드를 바꾸지 않습니다. 모드 버튼을 사용하세요. 샘플에 실제 데이터로 학습한 운동 모델은 기본 제공되지 않습니다.

## 자주 발생하는 문제

| 증상 | 확인할 내용 |
| --- | --- |
| Gradle에서 `io.motionengine`을 찾지 못함 | GitHub Packages 배포 성공 여부·버전·저장소 URL 확인. 로컬 사용이면 세 모듈의 `publishToMavenLocal`과 `mavenLocal()` 확인 |
| GitHub Packages `401` / `403` | 토큰 종류·유효기간·`read:packages`/`write:packages` 권한과 계정의 저장소 접근 권한 확인 |
| 모델 파일 없음 / 체크섬 오류 | 폴더 경로와 두 파일의 존재 확인. 같은 학습 결과의 manifest·모델을 함께 사용 |
| `AUTO requires a classifier` | 지정 모드는 `RecognitionMode.Guided(...)` 명시, 자동 모드는 분류기 전달 |
| 진행률이 `null`이거나 카운트가 증가하지 않음 | 시작 자세·필수 동작 단계·좌표 품질·후속 프레임 확인. 자동 모드는 실제 학습 모델의 분류 결과도 확인 |
| 지정 모드 confidence가 0 | 정상 동작. 횟수·진행률·품질 값으로 표시 |
| `Timestamps must be strictly increasing` | 중복·역순 결과 제거, 같은 캡처 시계 사용. 새 녹화 재생 전에 `reset()` 호출 |
| `Engine is closed` | 종료한 엔진으로 들어오는 콜백을 정리하고 새 엔진으로 세션 시작 |

입력 형식 오류·NaN·역순 타임스탬프는 `IllegalArgumentException`으로 거부하며 엔진 상태를 바꾸지 않습니다. 모델 로딩·추론 오류도 호출자에게 전달되므로 앱에서 오류 상태를 표시하세요.

<a id="training"></a>

## 수집 → 라벨링 → 학습 → Android 확인

1. [MediaPipe 공식 Pose Landmarker 문서](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)의 lite 모델을 준비합니다. `python3 scripts/download_pose_model.py --output artifacts/pose_landmarker_lite.task`로도 받을 수 있습니다.
2. 샘플 앱의 **Pose 가져오기**에서 `.task`를 선택하고 카메라를 시작합니다. 한 명의 전신을 고정 사선 카메라로 촬영합니다.
3. 같은 사람에게는 같은 익명 참가자 ID를 사용합니다. 운동 라벨을 선택하고 수집한 뒤 JSONL을 내보냅니다. 정답 라벨은 분류기에 전달하지 않습니다. 원본 영상은 저장하지 않습니다.
4. [라벨링 도구](training/labeler.html)를 브라우저에서 열어 JSONL을 불러옵니다. 반복과 단계 경계를 기록하고 같은 이름의 `.annotations.json`을 받습니다. 두 파일을 `data/real/`에 둡니다.
5. 아래 명령으로 학습합니다. 최소 세 참가자가 필요하고, 각 분할에 학습 대상으로 선택한 모든 운동과 other가 포함되어야 합니다. 실제 성능 평가에는 더 다양한 참가자·체형·속도의 데이터가 필요합니다.

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install -e ./training
.venv/bin/python -m motion_training.train --data data/real --output artifacts/model --epochs 30

# 스쿼트/기타 2종, 잔차 TCN 및 클래스별 가중치 실험
.venv/bin/python -m motion_training.train --data data/real --output artifacts/squat-model \
  --epochs 30 --classes squat other --architecture residual --balance-classes
```

`--classes` 순서가 모델 출력 순서입니다. 선택하지 않은 운동 데이터는 `other`로 학습합니다. `baseline`이 기본 구조이며 `residual`은 비교 실험용입니다. 같은 참가자 분할로 분류 성능과 실제 반복 카운팅을 비교해 선택하세요. 중단·불완전 반복의 운동 종류는 분류 정답에 유지하고, 정상 반복 횟수 정답에서는 제외합니다.

6. `artifacts/model/motion-model.zip`을 앱의 **운동 모델 ZIP**에서 가져옵니다. 모델은 온디바이스에서 실행되며 앱에는 네트워크 권한이 없습니다.
7. **좌표 재생**으로 녹화 파일을 확인하고 **예측 내보내기**로 결과를 저장합니다.

```sh
.venv/bin/python -m motion_training.evaluate \
  --session data/real/session.jsonl \
  --predictions artifacts/session-predictions.jsonl \
  --output artifacts/sequence-report.json
```

학습 보고서에는 운동별 precision/recall/F1·혼동 행렬·변환 오차·호스트 추론 시간이 들어갑니다. 시퀀스 평가에는 운동별 반복 precision/recall·누락/오검출·인식 지연·완료 지연 p50/p95·단계 경계 누락률·진행률 오차와 평가 가능 비율이 들어갑니다. 현재 자세와 완료 이벤트를 따로 평가하며 구버전 예측 파일도 지원합니다. 진행률 정답은 사람이 지정한 단계 경계 사이를 보간한 근사 기준입니다.

## 합성 데이터로 전체 연결 검증

```sh
PYTHONPATH=training python3 training/tools/generate_fixtures.py --smoke-data data/synthetic
.venv/bin/python -m motion_training.train --data data/synthetic --output artifacts/smoke-model --epochs 2 --synthetic

# 연결된 Android 기기 또는 에뮬레이터 필요
./gradlew :android-runtime:connectedDebugAndroidTest -PmodelBundle=artifacts/smoke-model
```

합성 모델에는 `trainingData="synthetic"`가 기록됩니다. Android 테스트는 실제 LiteRT 추론과 TensorFlow 출력을 비교하고, 체크섬 변조·close 이후 호출을 검사합니다. 모델이 없는 기본 계측 테스트 실행에서는 해당 테스트가 건너뛰어집니다.

## 모듈

| 모듈 | 역할 |
| --- | --- |
| `core` | KMP 공통 타입, 특징 추출, 시계열 처리, 자동 분류 조정, 네 운동의 단계·반복 추적. Android/JVM 타깃 |
| `android-runtime` | 오프라인 LiteRT CPU 추론, manifest·SHA-256·텐서 규격 검사 |
| `mediapipe-adapter` | MediaPipe 결과를 SDK 입력으로 변환 |
| `sample-android` | CameraX 수집, 모델 가져오기, 좌표 재생·예측 내보내기 |
| `training` | 라벨링, 참가자별 데이터 분리, 학습·변환·평가 |

## SDK 빌드와 테스트

학습 도구의 Python 지원 버전은 3.11–3.13입니다. 저장소의 모델 다운로드·데이터 도구도 Python을 사용하지만, SDK를 사용하는 Android 앱에는 Python이 필요하지 않습니다.

```sh
./gradlew :core:jvmTest :sample-android:assembleDebug :sample-android:lintDebug

# TensorFlow 설치 없이 데이터·특징·평가 테스트 실행
PYTHONPATH=training python3 -m unittest discover -s training/tests -v
```

## GitHub Packages에 배포

[배포 워크플로](.github/workflows/publish.yml)는 `v*` 태그를 push하면 실행됩니다. `v0.1.1`은 패키지 버전 `0.1.1`, `v0.2.0-rc.1`은 `0.2.0-rc.1`로 배포합니다. 일반 브랜치 push나 PR은 배포하지 않으며, 형식에 맞지 않는 태그와 `-SNAPSHOT` 태그는 거부합니다.

### 태그로 자동 배포

1. 배포할 SDK 변경과 워크플로 파일을 커밋하고 GitHub에 push합니다. 태그는 해당 커밋을 가리켜야 합니다.
2. 저장소에서 GitHub Actions와 패키지 생성을 허용합니다. 워크플로는 `contents: read`, `packages: write` 권한과 자동 제공되는 `GITHUB_TOKEN`을 사용하므로 별도의 배포용 PAT secret은 필요하지 않습니다. 조직 정책이 이를 제한하면 관리자가 허용해야 합니다.
3. 아직 사용하지 않은 버전 태그를 생성하고 push합니다.

```sh
git tag v0.1.1
git push origin v0.1.1
```

4. **Actions → Publish GitHub Packages**에서 성공 여부를 확인합니다. 코어 JVM 테스트, 샘플 앱 빌드·Lint가 통과한 뒤 업로드합니다.
5. [Packages](https://github.com/WUZ-Lab/MOTION-RECOGNITION-ENGINE/packages)에서 버전을 확인한 뒤 소비 앱의 SDK 의존성 버전을 갱신합니다.

Actions의 **Run workflow**에서도 기존 태그를 입력해 배포할 수 있습니다. 수동 실행은 워크플로가 기본 브랜치에 올라간 뒤 사용하며, 입력한 태그의 코드를 checkout합니다. 이미 배포한 버전을 덮어쓰는 용도로 사용하지 마세요. 여러 패키지는 순서대로 업로드되므로 중간 실패 시 일부만 올라갈 수 있습니다. 원인을 수정한 뒤 새 버전 태그로 전체를 배포하세요.

배포되는 Maven 좌표는 모두 `io.motionengine` 그룹이며 다음과 같습니다.

| 아티팩트 | 내용 |
| --- | --- |
| `core` | KMP 루트 메타데이터와 타깃 연결 |
| `core-android` / `core-jvm` | Android release / JVM 코어와 소스 |
| `android-runtime` | LiteRT 런타임 AAR와 소스 |
| `mediapipe-adapter` | MediaPipe 어댑터 AAR와 소스 |

샘플 앱, 수집 데이터와 운동 모델 파일은 Maven 패키지에 포함하지 않습니다. 패키지 접근 권한은 GitHub 저장소와 조직 설정을 따르며, 다른 앱의 설치에도 앞서 설명한 읽기 인증이 필요합니다. [GitHub의 Gradle 배포 안내](https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle)

### 로컬에서 수동 배포

저장소 쓰기 권한이 있는 계정의 **PAT classic (`write:packages`)**을 개인 `~/.gradle/gradle.properties`의 `gpr.user`, `gpr.key`로 설정하거나 `GITHUB_ACTOR`, `GITHUB_TOKEN` 환경변수로 전달합니다. GitHub 토큰을 명령행 인자에 직접 넣지 않습니다.

```sh
./gradlew :core:jvmTest :sample-android:assembleDebug :sample-android:lintDebug -PsdkVersion=0.1.1
./gradlew publishGitHubPackages -PsdkVersion=0.1.1
```

`sdkVersion`을 생략하면 개발용 `0.1.0-SNAPSHOT`이 됩니다. 정식 배포에서는 반드시 새 버전을 지정하세요. 대상 저장소는 `-PgithubRepository=OWNER/REPOSITORY`, `GITHUB_REPOSITORY`, 기본값 `WUZ-Lab/MOTION-RECOGNITION-ENGINE` 순서로 선택합니다. fork의 Actions는 해당 fork로 배포하며, 소비 앱의 저장소 URL도 맞춰 바꿔야 합니다.

## 확장과 문서

새 운동은 학습 클래스·데이터를 추가하고 `ExerciseDefinition(id, createTracker)`를 등록합니다. 모델 클래스에 대응하는 단계 정의가 없으면 초기화에 실패합니다. 사용자 정의 운동을 학습할 때는 Python의 클래스 목록·라벨 단계·모델 출력 수 역시 함께 변경해야 합니다.

- [입력·데이터·특징 규격](docs/data-format.md)
- [동작 판정과 개발 범위](docs/design.md)
- [검증 결과](docs/validation.md)

iOS 런타임, 자유 각도, 다인 추적, 자세 교정 점수와 실제 데이터 기반 성능 보장은 첫 버전 범위에 포함하지 않습니다.
