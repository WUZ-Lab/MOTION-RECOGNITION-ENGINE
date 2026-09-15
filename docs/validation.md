# 구현 검증 기록

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
