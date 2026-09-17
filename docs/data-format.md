# 데이터와 모델 규격 v1

## JSONL 수집 파일

첫 줄은 `SessionHeader`, 이후 각 줄은 `FrameRecord`입니다. SDK의 `MotionJson.format`은 기본값도 명시적으로 직렬화합니다. Python과 브라우저 라벨러는 같은 규격을 사용합니다.

```json
{"type":"session","schemaVersion":1,"sessionId":"uuid","participantId":"p001","exerciseLabel":"squat","cameraView":"fixed_oblique","poseModel":"pose_landmarker_lite.task","poseSettings":{"numPoses":"1","minConfidence":"0.5"}}
```

```json
{"type":"frame","timestampMs":1000,"pose":null}
```

포즈가 있으면 `pose`에 `timestampMs`, `imageWidth`, `imageHeight`, `landmarks`, `worldLandmarks`를 넣습니다. 두 landmark 배열은 MediaPipe 순서의 33개 `{x,y,z,visibility}`입니다. 모든 수치는 유한해야 하고 visibility는 0~1입니다. 영상 밖 landmark는 있을 수 있으므로 x/y를 강제로 0~1에 자르지 않습니다.

타임스탬프는 음수가 아닌 밀리초 정수이며 세션 내에서 엄격히 증가해야 합니다. 기록과 pose 타임스탬프는 같습니다. 사람이 안 보이는 프레임도 `pose:null`로 기록하여 유실 구간을 보존합니다. `exerciseLabel`은 수집 당시 힌트이며 학습 정답은 검토된 annotation에서 읽습니다.

## Annotation

`session.jsonl`에 대응하는 `session.annotations.json`:

```json
{
  "schemaVersion": 1,
  "sessionId": "uuid",
  "repetitions": [
    {"startMs":1000,"endMs":4000,"exerciseId":"squat","valid":true,
     "phases":{"start":1000,"bottom":2500,"end":4000}}
  ]
}
```

| 운동 | 정상 반복의 필수 단계 경계 |
| --- | --- |
| squat / push_up | start, bottom, end |
| jump_squat | start, bottom, takeoff, landing, end |
| burpee | start, lowered, plank, tucked, takeoff, landing, end |

모든 경계는 순서대로 증가해야 합니다. 반복 구간은 원본 프레임 시간 범위 안에 있고 서로 겹치지 않아야 합니다. `start`/`end`는 각각 startMs/endMs와 같습니다. start는 준비 자세에서 실제 움직이기 시작한 프레임, end는 착지·최종 자세가 안정된 프레임입니다.

`valid:false`는 중단·불완전·잘못 수행한 구간입니다. 필수 중간 경계는 없으며 **운동 종류 정답은 유지하되 정상 반복 횟수의 정답에서는 제외**합니다. 이는 기존에 불완전 구간 전체를 other로 바꾸던 학습 정책과 다릅니다. 학습 보고서에 정책을 기록합니다. 라벨되지 않은 구간은 other입니다. other만 수집한 세션도 검토 후 빈 `repetitions` annotation 파일을 저장해야 합니다. `--classes`로 선택하지 않은 운동은 해당 모델에서 other로 학습합니다.

## 특징과 시간 규격

분류 모델 v1의 필수 관절은 양쪽 어깨·팔꿈치·손목·골반·무릎·발목(11–16, 23–28)이며 영상/world visibility 모두 0.5 이상이어야 합니다. 몸통 길이나 관절 벡터가 퇴화한 좌표는 품질 불량입니다. 카운팅은 운동별 부분 기하를 별도로 사용하므로 일부 관절 가림에서 분류만 중단될 수 있습니다. 부분 기하는 아래 모델 특징 채널에 채워 넣지 않습니다.

| 인덱스 | 특징 |
| --- | --- |
| 0–1 | 좌·우 무릎 각도 / 180도 |
| 2–3 | 좌·우 고관절 각도 / 180도 |
| 4–5 | 좌·우 팔꿈치 각도 / 180도 |
| 6 | world Y축 기준 몸통 기울기 / 180도 |
| 7–8 | 손목에서 골반 중심까지 거리 / 몸통 길이 |
| 9 | 발목 사이 거리 / 몸통 길이 |
| 10–11 | 영상 골반 중심 Y, 양 발목 평균 Y |
| 12 | 영상 비율을 보정한 어깨 중심–골반 중심 길이 |
| 13–14 | world 손목 평균 Y·발목 평균 Y의 골반 대비 값 / 몸통 길이 |
| 15 | world 골반–발목 중심 거리 / 몸통 길이 |
| 16–31 | 0–15의 초당 변화량. ±20 제한 |
| 32 | 시계열 샘플 유효 마스크: 0 또는 1 |

좌표의 위치·크기 정규화는 world 거리의 골반 상대값과 몸통 길이 비율로 수행합니다. 영상 Y 위치·크기는 점프 이동 감지를 위해 유지합니다. 카메라 각도 회전 불변성은 보장하지 않습니다.

첫 정상 프레임 또는 500ms 초과 유실 뒤의 변화량은 0입니다. 30Hz 샘플 시각은 `origin + floor(tick*1000/30)`이며 미래 프레임을 참조하지 않습니다. 직전 관측을 최대 100ms 유지하고 이후는 마스크 0을 넣습니다. 180개 시간행을 유지하고 앞쪽 부족분은 0으로 채웁니다.

모델 입력은 float32 `[1,180,33]`, 출력은 float32 `[1,classCount]` softmax입니다. 표준화는 train 참가자의 유효 특징만으로 구한 mean/std를 사용합니다. 마스크는 표준화하지 않으며 무효 시간행은 전체 0입니다. 최소 유효 시간행 15개 후 추론합니다.

## 모델 번들

`manifest.json`과 `motion.tflite`가 동일 디렉터리에 있어야 합니다. 앱 가져오기 ZIP에는 두 파일만 루트에 넣습니다. 경로·중복 항목·파일 크기와 모델 SHA-256을 검사합니다.

manifest에는 `schemaVersion`, `featureVersion`, `modelFile`, `sha256`, `classes`, `sampleRateHz`, `windowSize`, `featureCount`, `mean`, `std`, `confidenceThreshold`, `marginThreshold`, `stableDurationMs`, `minValidSteps`, `progress`, `trainingData`가 있습니다. TensorFlow 버전을 고정하고 변수를 상수로 변환한 뒤 LiteRT를 생성합니다. export는 전체 test 창의 예측 차이가 1e-4 이하여야 성공합니다.

특징 순서나 의미 변경 시 featureVersion을 올려야 합니다. 모델·설정과 코어가 불일치하면 로딩을 거부합니다. 진행률 설정은 학습에서 자동 추정하지 않으며 별도 실제 좌표 검증으로 조정합니다.

## 예측 결과

`RecognitionResult.current`는 현재 자세의 분류·단계·진행률·상태를 나타내고, `events`는 이번 프레임에서 발행한 완료 이벤트 배열입니다. `repsByExercise`와 `completedReps`는 세션 누적 값입니다. 지정 모드는 `source: "GUIDED"`, 자동 모드는 `source: "MODEL"`입니다.

```json
{"repId":1,"exerciseId":"squat","startedAtMs":1000,"endedAtMs":4000,"emittedAtMs":4500,"confidence":null,"source":"GUIDED"}
```

위 객체는 `events`의 한 항목입니다. ID는 세션 내 증가하며 `reset()` 후 다시 시작합니다. `startedAtMs < endedAtMs <= emittedAtMs`이고, 발행 시각은 해당 결과 프레임 시각과 같습니다. 현재 프레임에서는 이미 다른 동작이 진행 중일 수 있습니다. 기존 최상위 필드는 첫 이벤트를 표시하므로 현재 자세 평가에는 `current`를 사용합니다.

시퀀스 평가기는 신형 이벤트의 시작·종료 오차가 각각 500ms 이내이고 구간이 겹칠 때 일대일 매칭합니다. 구형 결과는 종료 -300/+1500ms 발행 시각 기준을 유지합니다. 발행 지연과 기하학적 단계 오차를 분리하며, 단계는 같은 자세 유지 중의 가장 가까운 프레임이 아닌 상태 전환 시각으로 평가합니다. 같은 단계 관측을 인접 반복에 재사용하지 않고 누락 경계도 보고합니다.
