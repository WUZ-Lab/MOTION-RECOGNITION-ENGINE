# 구현 검증 기록

## 2026-09-17 GitHub Packages 배포 설정 검증

태그 기반 GitHub Actions 배포, `sdkVersion` 지정, GitHub 인증과 Maven 메타데이터 설정을 추가했습니다. 아래는 원격 업로드 전 로컬 검증 결과입니다.

| 검사 | 결과 |
| --- | --- |
| 배포 작업 dry-run | SDK 3개 모듈의 5개 publication 포함. KMP 루트·Android·JVM 포함 |
| 로컬 Maven 배포 | `0.1.0` 버전의 `core`, `core-android`, `core-jvm`, `android-runtime`, `mediapipe-adapter` 생성 |
| 아티팩트·의존성 검사 | 바이너리·소스·POM·Gradle metadata 존재, SDK 의존성 버전 일치, KMP 타깃 참조 파일 존재 확인 |
| SDK 검증 작업 | JVM 26개 테스트 통과 상태 유지, 샘플 debug 빌드·Lint 통과. Gradle의 최신 결과 재사용 포함 |
| 독립 Android 앱 | 소스 project 의존성 없이 Maven `0.1.0` 패키지만 사용해 release/R8 빌드 통과 |
| 워크플로 검증 | YAML·셸 문법 확인. 정상 태그 4개에서 버전 추출, 잘못된 태그 9개 거부 |
| 배포 입력 검증 | 빈 인증정보와 잘못된 `sdkVersion` 거부. 원격 요청 없이 검사 |
| README | 코드 블록·셸 문법·내부 링크 확인 |

검증용 Maven 저장소는 `/tmp/motion-github-packages-maven`, 소비 앱은 `/tmp/motion-github-packages-consumer`에 생성했습니다. 임시 파일은 삭제될 수 있습니다. 실제 GitHub 업로드, GitHub Actions 실행 및 원격 저장소에서의 인증·다운로드는 수행하지 않았습니다. 배포 워크플로를 포함한 커밋을 push하고 새 버전 태그를 push하면 원격 배포가 시작됩니다.

## 2026-09-17 SDK 개선 검증

카운팅 후보·이벤트·지정 모드·관절 품질 처리와 학습/평가 변경을 검증했습니다.

| 검사 | 결과 |
| --- | --- |
| 코어 JVM 테스트 | 26개 통과. 기존 15개 + 회귀 11개 |
| Python 데이터·특징·평가 테스트 | 15개 통과 |
| 샘플 앱 debug APK / Android Lint | 빌드 통과, Lint 오류 없음 |
| Android 런타임 계측 테스트 APK | 빌드 통과. asset 로딩 후 추론 비교 검사 추가 |
| 독립 Android 앱의 SDK 사용 | 임시 Maven 저장소에 배포한 세 모듈만 의존해 release/R8 빌드 통과. 소스 project 의존성 없음 |
| 기존 5종 baseline TCN | 합성 데이터 1 epoch 학습·변환·ZIP 생성 통과. TensorFlow/LiteRT 최대 차이 `1.1920928955078125e-7` |
| 2종 squat/other residual TCN | 클래스 가중치 사용, 합성 1 epoch 학습·변환·ZIP 생성 통과. 최대 차이 `5.066394805908203e-7` |
| 지정 모드 JSONL → SDK → JSONL → Python 평가 | 합성 스쿼트 기대 1회/예측 1회, 누락·오카운트 0. 진행률과 단계 오차가 없는 것은 아님 |

회귀 테스트에는 완료 후 other 전환, 짧은 유실 후 확인 재시작, 장시간 유실 취소, 관측하지 않은 바닥 단계 거부, 분류 근거 없는 자동 카운팅 거부, 연속 반복, 손목·한쪽 다리 가림, 점프·부분 동작 거부, 다음 점프가 이전 스쿼트를 취소하지 않는지, 여러 완료 이벤트 동시 발행, 결과 직렬화를 포함했습니다.

지정 모드의 운동 선택값을 자동 분류 성공으로 평가하지 않도록 Python 평가에서 해당 분류 지표는 `null`로 표시합니다. 새 이벤트는 기하학적 완료 시각과 발행 시각을 분리해 비교하고, 단계 누락도 보고합니다.

**범위:** 실제 수집 데이터는 제공되지 않았습니다. 생성한 두 모델은 `trainingData="synthetic"`인 연결 검증용이며 앱 기본 모델로 포함하지 않았습니다. 기기 목록이 비어 있어 이번 변경의 Android 설치·실행·계측 테스트 및 실촬영 정확도/발열 검증은 수행하지 않았습니다. 아래의 기존 에뮬레이터 기록은 이전 코드에 대한 결과입니다.

산출물: `artifacts/revision-baseline/`, `artifacts/revision-squat/`, `sample-android/build/outputs/apk/debug/`. 외부 앱 검증은 `/tmp/motion-sdk-consumer`, 최종 Maven 아티팩트는 `/tmp/motion-sdk-maven-final`에 생성했으며 임시 파일은 삭제될 수 있습니다.

## 2026-09-16 초기 구현 검증

검증일: 2026-09-16 (KST). macOS arm64, JDK 21, Android SDK 36, Python 3.12 / TensorFlow 2.20.0.

| 검사 | 결과 |
| --- | --- |
| KMP 코어 JVM 테스트 | 15개 통과 |
| Python 데이터·특징·평가 테스트 | 7개 통과 |
| Android 런타임·MediaPipe 어댑터 빌드 | 통과 |
| Android 샘플 debug APK | 빌드·에뮬레이터 설치·실행 통과. 시작 시 크래시 없음 |
| Android Lint | 오류 없이 통과. 샘플의 고정 방향·하드코딩 한국어·아이콘 및 의존성 업데이트 경고 등은 남아 있음 |
| 학습 → LiteRT → 모델 ZIP | 합성 3참가자·5클래스, 2 epoch 연결 검증 통과 |
| TensorFlow / LiteRT 출력 비교 | test 190개 창의 최대 절대 오차 `1.7881393e-7` |
| Android 실제 네이티브 런타임 | 1개 계측 테스트 통과. 출력 오차 1e-4 이내, SHA-256 변조 거부, close 동작 검사 |
| Android에서 전체 JSONL 재생 | 240프레임 처리·예측 파일 생성·Python 시퀀스 평가 실행 통과 |
| 라벨링 HTML | JavaScript 구문 검사 통과. 연결된 브라우저가 없어 브라우저 상호작용 검증은 미실시 |

## 핵심 회귀 시나리오

네 운동의 단계 순서, 준비 자세 없는 입력, 중간 반전, 동일 자세 유지, 완료 이벤트 중복, 늦은 운동 분류, 점프에 따른 스쿼트 완료 후보 취소, 좌표 유실 후 재시작, 세션 초기화, 잘못된 입력의 상태 보존을 검사했습니다.

Kotlin/Python 양쪽은 동일한 좌표 fixture의 특징 32채널과 180×33 시계열 창을 비교합니다. 별도 테스트에서 world 좌표 이동·배율에 대한 기하학적 특징의 불변성과 각도 기준값도 확인합니다.

## 성능과 해석 범위

Pixel_10a AVD, Android 17/API 37, arm64 16KB 페이지 에뮬레이터에서 LiteRT CPU 추론을 실행했습니다. 첫 계측 실행의 30회 측정 중앙값은 약 0.32ms, p95는 약 0.83ms였습니다. **에뮬레이터의 작은 합성 모델 수치이며 실제 Android 기기 성능을 대표하지 않습니다.**

2 epoch 모델은 연결 검증만을 위한 모델입니다. 합성 test 창에서도 운동별 recall이 0이고 주로 other로 분류했습니다. 한 스쿼트 시퀀스 평가에서도 기대 1회에 예측 0회였습니다. 이를 운동 인식 성공이나 정확도 검증으로 해석하면 안 됩니다. 실제 데이터 수집·충분한 학습·참가자 분리 평가가 후속 작업입니다.

실제 카메라에서 사람을 촬영한 동작 인식, 사선 촬영 조건에 따른 오차, 개별 기기 프레임 처리량, 브라우저 라벨러의 수동 사용성, iOS 실행은 검증하지 않았습니다.

## 산출물과 재현

- `core/build/reports/tests/jvmTest/index.html`
- `sample-android/build/outputs/apk/debug/sample-android-debug.apk`
- `sample-android/build/reports/lint-results-debug.html`
- `android-runtime/build/reports/androidTests/connected/debug/index.html`
- `artifacts/smoke-model/report.json`, `android-benchmark.json`, `sequence-report.json`

빌드·검증 산출물과 데이터는 Git에서 제외합니다. 재현 명령은 루트 README에 있습니다. CI 설정은 추가했으며 원격 GitHub Actions 실행은 수행하지 않았습니다.
