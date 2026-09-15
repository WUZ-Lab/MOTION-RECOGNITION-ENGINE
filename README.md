# Motion Recognition Engine

MediaPipe 좌표에서 운동 종류와 **1회 동작 진행률**을 반환하는 Android 우선 KMP SDK입니다.

```text
33개 영상·world 좌표 + 타임스탬프
  → 품질 검사 → 특징 추출 → 6초 시계열 → AI 분류 → 단계 추적 → 결과
```

**현재 제공 범위:** SDK, Android 수집·재생 앱, 브라우저 라벨링 도구, TCN 학습·LiteRT 변환·평가 도구.
실제 수집 데이터로 학습한 운동 모델은 포함되어 있지 않습니다. 진행률 임계값은 초기 기준이며, 합성 데이터 테스트 통과는 실제 인식 정확도를 의미하지 않습니다.

## 모듈

| 모듈 | 역할 |
| --- | --- |
| `core` | KMP 공통 타입, 특징 추출, 시계열 처리, 자동 분류 조정, 네 운동의 단계·반복 추적. Android/JVM 타깃 |
| `android-runtime` | 오프라인 LiteRT CPU 추론, manifest·SHA-256·텐서 규격 검사 |
| `mediapipe-adapter` | MediaPipe 결과를 SDK 입력으로 변환 |
| `sample-android` | CameraX 수집, 모델 가져오기, 좌표 재생·예측 내보내기 |
| `training` | 라벨링, 참가자별 데이터 분리, 학습·변환·평가 |

지원 운동은 `squat`, `burpee`, `jump_squat`, `push_up`이며 `other`는 대기·기타입니다.
버피는 **서기 → 몸 낮추기 → 플랭크 → 다리 당기기 → 점프·착지**로 정의합니다. 푸쉬업은 필수 단계가 아닙니다.

## 빌드와 테스트

필요 환경: JDK 21, Android SDK 36, Python 3.11–3.13(학습), Android 8/API 26 이상(실행).
Gradle Wrapper 8.13, Kotlin 2.2.20, AGP 8.13.2를 사용하며 JVM 바이트코드는 Java 17로 생성합니다.

```sh
# local.properties의 sdk.dir 또는 ANDROID_HOME으로 Android SDK 경로 지정
./gradlew :core:jvmTest :sample-android:assembleDebug :sample-android:lintDebug

# TensorFlow 설치 없이도 데이터/특징 테스트 실행 가능
PYTHONPATH=training python3 -m unittest discover -s training/tests -v
```

APK: `sample-android/build/outputs/apk/debug/sample-android-debug.apk`
로컬 Maven 배포: `./gradlew :core:publishToMavenLocal :android-runtime:publishToMavenLocal :mediapipe-adapter:publishToMavenLocal`
프로젝트 내부 사용 시 `implementation(project(":android-runtime"))`와 `implementation(project(":mediapipe-adapter"))`를 추가합니다.

## SDK 사용

학습으로 생성한 ZIP의 `manifest.json`, `motion.tflite`를 같은 디렉터리에 풉니다.

```kotlin
val classifier = LiteRtClassifier.fromDirectory(modelDirectory)
val engine = MotionEngine(classifier)

// 단일 백그라운드 실행 문맥에서 시간순으로 호출합니다.
val pose = MediaPipePoseAdapter.fromResult(mediaPipeResult, uprightWidth, uprightHeight)
val result = if (pose != null) {
    engine.process(pose)
} else {
    engine.processMissing(mediaPipeResult.timestampMs())
}

// 예: exerciseId="squat", confidence=0.95, progress=0.80
// confidence는 모델 점수이며 보정된 확률이 아닙니다.
// progress는 한 반복의 단계·관절 움직임으로 추정한 진행률입니다.
val percent = result.progress?.times(100)
if (result.repCompleted) { /* 이번 호출에서 1회 완료 확정 */ }

engine.reset() // 새 세션: 반복 수와 시계열 모두 초기화
engine.close() // 분류기 자원도 해제. close는 반복 호출 가능
```

MediaPipe 호출·카메라 처리 자체는 SDK 코어의 책임이 아닙니다. 입력은 **회전 보정된 영상 기준**이며 프리뷰 좌우 반전을 적용하기 전의 좌표와 영상 크기를 사용합니다. 한 인스턴스는 한 사람만 처리합니다.

### 결과와 실패 처리

- `exerciseId`: 확정한 운동 ID. 판단 중·기타·좌표 불량에는 `null`.
- `confidence`: 0~1 모델 점수. 아직 추론하지 않은 경우 0.
- `progress`: 0~1 진행률. 단계 시작을 관측하지 못했거나 판단 불가이면 `null`.
- `phase`: 운동 단계. `ready`, `lowering`, `bottom`, `rising`, `airborne`, `landing`, `complete` 등.
- `status`: `WARMING_UP`, `UNCERTAIN`, `TRACKING`, `OTHER`, `POOR_POSE`.
- `completedReps`: 세션의 전체 운동 누적 반복 수. `repCompleted`는 완료당 한 번만 참.

스쿼트/점프스쿼트의 초기 구간은 비슷하므로 분류가 늦어질 수 있습니다. 단계 추적은 분류 확정 전부터 잠정적으로 수행합니다. 완료 확정은 기본 500ms 지연하며, 그 전에 도약을 관측하면 스쿼트 완료 후보를 취소합니다. 미확정 진행률은 99%를 넘지 않습니다.

좌표 불량 시 진행률을 숨기고 완료 후보를 폐기합니다. 마지막 정상 좌표 이후 500ms 초과 공백은 현재 반복·시계열을 초기화하지만 누적 반복 수는 유지합니다. 형식 오류·NaN·역순 타임스탬프는 `IllegalArgumentException`을 발생시키며 엔진 상태를 바꾸지 않습니다. 모델 규격·추론 오류는 호출자에게 전달합니다.

## 수집 → 라벨링 → 학습 → Android 확인

1. [MediaPipe 공식 Pose Landmarker 문서](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)의 lite 모델을 준비합니다. `python3 scripts/download_pose_model.py --output artifacts/pose_landmarker_lite.task`로도 받을 수 있습니다.
2. 샘플 앱의 **Pose 가져오기**에서 `.task`를 선택하고 카메라를 시작합니다. 한 명의 전신을 고정 사선 카메라로 촬영합니다.
3. 같은 사람에게는 같은 익명 참가자 ID를 사용합니다. 운동 라벨을 선택하고 수집한 뒤 JSONL을 내보냅니다. 정답 라벨은 분류기에 전달하지 않습니다. 원본 영상은 저장하지 않습니다.
4. [라벨링 도구](training/labeler.html)를 브라우저에서 열어 JSONL을 불러옵니다. 반복과 단계 경계를 기록하고 같은 이름의 `.annotations.json`을 받습니다. 두 파일을 `data/real/`에 둡니다.
5. 아래 명령으로 학습합니다. 최소 세 참가자가 필요하고, 각 분할에 모든 운동과 other가 포함되어야 합니다. 실제 성능 평가에는 더 다양한 참가자·체형·속도의 데이터가 필요합니다.

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install -e ./training
.venv/bin/python -m motion_training.train --data data/real --output artifacts/model --epochs 30
```

6. `artifacts/model/motion-model.zip`을 앱의 **운동 모델 ZIP**에서 가져옵니다. 모델은 온디바이스에서 실행되며 앱에는 네트워크 권한이 없습니다.
7. **좌표 재생**으로 녹화 파일을 확인하고 **예측 내보내기**로 결과를 저장합니다.

```sh
.venv/bin/python -m motion_training.evaluate \
  --session data/real/session.jsonl \
  --predictions artifacts/session-predictions.jsonl \
  --output artifacts/sequence-report.json
```

학습 보고서에는 운동별 precision/recall/F1·혼동 행렬·변환 오차·호스트 추론 시간이 들어갑니다. 시퀀스 평가에는 반복 수 오차·누락/오검출·인식 지연·단계 경계 오차·진행률 오차와 평가 가능 비율이 들어갑니다. 진행률 정답은 사람이 지정한 단계 경계 사이를 보간한 근사 기준입니다.

## 합성 데이터로 전체 연결 검증

```sh
PYTHONPATH=training python3 training/tools/generate_fixtures.py --smoke-data data/synthetic
.venv/bin/python -m motion_training.train --data data/synthetic --output artifacts/smoke-model --epochs 2 --synthetic

# 연결된 Android 기기 또는 에뮬레이터 필요
./gradlew :android-runtime:connectedDebugAndroidTest -PmodelBundle=artifacts/smoke-model
```

합성 모델에는 `trainingData="synthetic"`가 기록됩니다. Android 테스트는 실제 LiteRT 추론과 TensorFlow 출력을 비교하고, 체크섬 변조·close 이후 호출을 검사합니다. 모델이 없는 기본 계측 테스트 실행에서는 해당 테스트가 건너뛰어집니다.

## 확장과 문서

새 운동은 학습 클래스·데이터를 추가하고 `ExerciseDefinition(id, createTracker)`를 등록합니다. 모델 클래스에 대응하는 단계 정의가 없으면 초기화에 실패합니다. 사용자 정의 운동을 학습할 때는 Python의 클래스 목록·라벨 단계·모델 출력 수 역시 함께 변경해야 합니다.

- [입력·데이터·특징 규격](docs/data-format.md)
- [동작 판정과 개발 범위](docs/design.md)
- [검증 결과](docs/validation.md)

iOS 런타임, 자유 각도, 다인 추적, 자세 교정 점수와 실제 데이터 기반 성능 보장은 첫 버전 범위에 포함하지 않습니다.
