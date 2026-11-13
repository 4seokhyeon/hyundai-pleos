# 노래하자

**전기차 충전 대기 시간을 노래 검색·반주 재생·녹음·AI 재미 점수와 주변 화장실 SOS로 활용하는 Pleos 차량용 앱입니다.**

## 제출 APK

- 저장소: [4seokhyeon/hyundai-pleos](https://github.com/4seokhyeon/hyundai-pleos)
- APK 폴더: [apk/](apk/)
- 최종 파일명 규칙: `사석현_노래하자_release.apk`

## 실행 방법

1. Pleos Connect 에뮬레이터를 실행하고 기어를 P로 설정합니다.
2. `apk/`의 제출 APK를 내려받아 에뮬레이터에 설치합니다. Android SDK 도구를 사용하는 경우 `adb install -r "apk/사석현_노래하자_release.apk"`로 설치할 수 있습니다.
3. 런처에서 **노래하자**를 실행합니다.
4. 홈에서 곡 검색 → 결과 선택 → 공식 YouTube 플레이어 재생을 확인합니다.
5. 마이크 권한과 녹음 동의 후 최대 15초 녹음 → 녹음 듣기 → 전송 동의 → AI 재미 점수 평가 순서로 이용합니다.
6. 화장실 SOS는 위치 권한을 허용하고 앱에서 화장실·주차장 자료를 준비한 후 사용합니다. 후보를 선택하고 내비 전달을 확인합니다. 내비 안내 중에는 다음 경유지로 추가하고 기존 목적지를 유지합니다. 전달 성공이 확인되면 앱으로 복귀할 때 홈을 표시합니다.

정차 상태라도 플랫폼의 이용 허용 상태를 확인한 뒤 동작합니다. 주행 제한이 적용되면 영상·녹음과 제한된 화면 동작을 중단합니다. 충전 상태 자동 감지나 주행 중 노래방 이용을 제공한다고 주장하지 않습니다.

## 테스트 환경 및 필요한 설정

| 항목 | 개발·검증 환경 |
| --- | --- |
| Pleos Connect 에뮬레이터 | **v2.1.0** |
| 시스템 이미지 | Android 14 / API 34 / Pleos Car Emulator / arm64-v8a |
| 화면 규격 | **2560 × 1440px, 160dpi**, 가로 화면. 전체 에뮬레이터 기준이며 앱 콘텐츠 영역은 시스템 UI 및 창 배치에 따라 달라집니다. |
| 앱 패키지 | `com.shsh.pleos.playground` |
| 앱 버전 | `1.0` / versionCode `1` |
| Android 지원 설정 | minSdk 28 / targetSdk 36. 주요 앱 실행 흐름은 Android 10 이상을 요구합니다. |
| 네트워크 | YouTube 검색·재생, 공공데이터 수집, 채점 서버 연결 시 필요 |
| 마이크 | 차량/에뮬레이터의 마이크 입력 전달 및 앱 녹음 권한 |
| 위치 | 유효한 차량 위치와 Pleos 위치 권한. SOS 준비 자료는 경기 지역 화장실 및 전국 주차장 공개 자료 기반입니다. |
| Gleo | 설치한 APK의 `assets/schema.json`을 Gleo가 읽은 상태 |
| 채점 | 심사자 노트북에서 SingMOS 서버 실행 후 같은 노트북의 에뮬레이터가 `http://10.0.2.2:8091`로 연결. 아래 설치 가이드 참고. |

화면 규격은 개발 AVD 설정 파일에서 확인했습니다. 개발 중 확인한 기능은 다음과 같습니다.

- 노래 검색, 영상·소리 재생, 번호 선택 재생
- 녹음과 녹음 파일 평가를 위한 명시적 동의 흐름
- 화장실/연결 주차장의 내비 전달 및 경유지 추가
- 내비 전달 성공 후 앱 홈 복귀는 코드 적용 상태이며 최종 APK에서 확인이 필요합니다.

## SingMOS 서버를 심사자 노트북에서 실행

심사자 노트북에서 서버와 Pleos 에뮬레이터를 **함께 실행**합니다. 개발자의 노트북이나 외부 배포 서버에 연결하지 않습니다. 모델은 노트북의 CPU에서 실행하며, 최초 모델·의존성 다운로드에는 인터넷이 필요합니다.

### 1. 준비

- Git, Python **3.10**, Pleos Connect 에뮬레이터를 준비합니다.
- 아래 명령은 저장소 루트(`app/`, `server/`, `experiments/`가 있는 폴더)에서 실행합니다. 소스가 없다면 먼저 내려받습니다.

  ```bash
  git clone https://github.com/4seokhyeon/hyundai-pleos.git
  cd hyundai-pleos
  ```

  심사용으로 지정한 최종 커밋이 있다면 해당 커밋의 소스를 사용합니다.
- 녹음 파일을 평가하는 서버에는 YouTube·공공데이터 API 키가 필요하지 않습니다. 앱의 노래 검색·SOS에 필요한 키는 별도입니다.
- 모델 캐시와 Python 패키지를 저장할 디스크 공간을 확보합니다. GPU/CUDA는 필요하지 않습니다.

### 2. Python 환경 설치

**macOS 터미널**

```bash
python3.10 --version
python3.10 -m venv experiments/singmos/.venv
source experiments/singmos/.venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r experiments/singmos/requirements.txt
python -m pip check
```

**Windows PowerShell**

```powershell
py -3.10 --version
py -3.10 -m venv experiments/singmos/.venv
.\experiments\singmos\.venv\Scripts\python.exe -m pip install --upgrade pip
.\experiments\singmos\.venv\Scripts\python.exe -m pip install -r experiments/singmos/requirements.txt
.\experiments\singmos\.venv\Scripts\python.exe -m pip check
```

Windows에서는 가상환경을 활성화하지 않고 그 안의 Python을 직접 실행하므로 PowerShell 실행 정책을 바꿀 필요가 없습니다. `python3.10` 또는 `py -3.10`을 찾을 수 없다면 Python 3.10을 먼저 설치합니다.

주요 고정 버전은 `torch==2.5.1`, `torchaudio==2.5.1`, `s3prl==0.4.18`입니다. 기존 macOS 개발 환경에서 이 조합을 사용했습니다. requirements는 모든 간접 의존성을 고정한 lockfile이 아니며, Windows 신규 설치·추론은 아직 확인하지 않았습니다. 설치나 `pip check`가 실패하면 서버 실행으로 넘어가지 말고 오류를 확인합니다. 최신 torch/torchaudio로 임의 변경하지 않습니다.

### 3. 서버 시작

macOS는 위 가상환경이 활성화된 터미널에서 실행합니다.

```bash
python server/score_server.py --port 8091
```

Windows PowerShell에서는 다음 명령을 사용합니다.

```powershell
.\experiments\singmos\.venv\Scripts\python.exe server\score_server.py --port 8091
```

첫 실행에는 [SingMOS v1.1.2](https://github.com/South-Twilight/SingMOS/tree/v1.1.2)의 코드, `singmos_pro` 및 기반 모델 가중치를 내려받아 로딩합니다. 다운로드와 CPU 로딩이 끝날 때까지 기다립니다. 터미널에 다음 로그가 나온 뒤 앱 평가를 시작합니다.

```text
Scoring API ready; host=127.0.0.1, port=8091, model=singmos_pro
```

이 터미널은 평가 중 계속 열어 둡니다. 종료하려면 `Ctrl+C`를 누릅니다. 서버는 `127.0.0.1`에만 바인딩하므로 공유기 포트 포워딩이나 공용 인터넷 공개는 필요하지 않습니다.

### 4. 준비 상태 확인

노트북 브라우저에서 **http://127.0.0.1:8091/health** 를 엽니다. 응답에 `status: ready`, `model: singmos_pro`가 있으면 모델 준비가 끝난 상태입니다. 서버가 준비되지 않았는데 가짜 성공 응답을 만들지는 않습니다.

주소는 다음과 같이 구분합니다.

| 실행 위치 | 채점 서버 주소 |
| --- | --- |
| 심사자 노트북의 브라우저 | `http://127.0.0.1:8091/health` |
| 같은 노트북에서 실행하는 Pleos 에뮬레이터 앱 | `http://10.0.2.2:8091` |

에뮬레이터의 `10.0.2.2`는 호스트 노트북의 루프백 주소를 가리키는 별칭입니다. [Android 공식 네트워크 안내](https://developer.android.com/studio/run/emulator-networking-address)를 참고하세요. 실제 차량이나 다른 노트북에서 실행하는 에뮬레이터에는 이 주소를 그대로 사용할 수 없습니다.

### 5. 앱에서 평가

1. 서버 터미널의 준비 로그와 노트북 `/health` 응답을 확인합니다.
2. 같은 노트북에서 Pleos 에뮬레이터를 실행하고 P 기어 및 앱 이용 허용 상태를 확인합니다.
3. 로컬 채점 연결이 가능한 APK를 설치합니다. 앱은 `10.0.2.2:8091`에 접속하며 HTTP 허용 설정도 있어야 합니다.
4. 노래하자에서 무반주 녹음 또는 영상 재생 후 녹음 동의 → **3~15초** 노래 녹음 → 녹음 종료 → 녹음 듣기를 진행합니다.
5. **채점 요청 → 녹음 파일 전송 동의**를 선택합니다. 완료될 때까지 서버를 끄거나 노트북을 절전 모드로 전환하지 않습니다.
6. 서버 터미널의 `Upload accepted`, `Job processing`, `Job succeeded`와 앱의 점수 화면을 확인합니다. 서버는 원점수 `raw_mos`를 반환하고, 앱이 이를 재미 점수로 변환합니다.

녹음 파일은 사용자가 전송에 동의한 뒤 이 노트북 서버로 보내며, 서버는 처리 후 임시 WAV를 삭제합니다. 결과는 서버 메모리에 일시 보관되므로 재시작하면 없어집니다. 모델 다운로드와 녹음 업로드는 서로 다른 작업입니다.

### 6. 문제가 생기면

| 증상 | 확인할 사항 |
| --- | --- |
| `python3.10` / `py -3.10` 없음 | Python 3.10 설치 여부 확인 |
| `No module named ...` | 가상환경 Python으로 requirements를 설치·실행했는지 확인 |
| `torchaudio.set_audio_backend` 관련 오류 | torch·torchaudio가 모두 2.5.1인지와 s3prl 0.4.18인지 확인 |
| 서버가 시작 중 오래 기다림 | 첫 모델 다운로드 및 로딩 로그 확인. 준비 로그가 나올 때까지 대기 |
| `Address already in use` | 8091 포트를 쓰는 기존 서버가 있는지 확인. 앱 주소가 8091로 고정되어 있으므로 서버 포트만 바꾸면 앱이 연결되지 않음 |
| 노트북 `/health` 접속 실패 | 서버 프로세스와 모델 준비 로그, 포트 8091 확인 |
| 노트북 `/health`는 되지만 앱 접속 실패 | 동일 노트북의 에뮬레이터인지, 앱의 `10.0.2.2:8091` 설정·HTTP 허용·로컬 방화벽 확인 |
| 무음·너무 짧은 녹음·평가 실패 | 3초 이상 실제 노랫소리를 녹음하고 서버 `SHSH_SCORE` 오류 확인. 실패 시 임의 점수를 표시하지 않음 |

서버 API와 파일 단독 검증은 [채점 서버 상세 문서](docs/SCORING_SERVER.md), [SingMOS 평가 환경](experiments/singmos/README.md)을 참고하세요. 이 가이드를 작성하며 새 환경 설치나 모델 실행을 수행하지는 않았습니다.

## Gleo 음성 명령

- “노래하자에서 아이유 좋은 날 검색해줘.”
- “노래하자에서 3번 재생해줘.”
- “노래하자 앱 홈으로 가줘.”
- “노래하자에서 가까운 화장실 찾아줘.”

실제 기능 선택은 설치된 스키마와 Gleo의 발화 해석에 따라 달라질 수 있습니다. 화장실 SOS는 정차 중 앱 화면에서 이용합니다. 화면 없는 SOS 오버레이와 주행 중 최적 경로 자동 추천은 개발 검증용이며 완성된 제출 기능으로 안내하지 않습니다.

## 소스 구성 및 개발 실행

- `app/`: Kotlin / Jetpack Compose Android 앱, Pleos SDK 및 Gleo 연동
- `server/`: 개발용 Python 서버
- `experiments/singmos/`: SingMOS 평가 환경과 관련 실험
- `docs/`: 개발·검증 기록. 과거 기록은 최종 APK의 기능 상태와 다를 수 있습니다.
- `apk/`: 제출 APK 배치 위치

Android Studio에서 프로젝트 루트를 열고 Gradle Sync를 수행합니다. Pleos SDK 다운로드에 필요한 개발 환경을 준비합니다. 개발용 설정은 Git에서 제외되는 `local.properties`를 사용합니다. 실제 API 키·서명 파일·암호는 저장소에 올리지 않습니다.

개발 debug 실행에서 기존 SOS 화면을 사용하려면 `-PgleoSosOverlayProbe=false`를 사용합니다. 서명 키는 공개 저장소 밖에서 보관합니다.

## 알려진 제약

- 시설과 주차장 사이 거리는 직선거리이며 실제 도보 경로·출입구·현재 개방·실시간 주차 빈자리는 확인하지 않습니다.
- 후보별 예상 운전 시간 비교는 현재 위치에서 주차장까지의 시간입니다. 활성 경로에서의 최소 우회 시간을 보장하지 않습니다.
- YouTube 영상별 삽입 제한이나 네트워크 상태에 따라 재생이 불가능할 수 있습니다.
- SingMOS 기반 점수는 녹음 품질·자연스러움 예측을 활용한 재미 점수이며 원곡 음정·박자 또는 노래 실력을 객관적으로 평가한 점수가 아닙니다.
- 채점은 외부 배포 서버 대신 심사자 노트북의 로컬 서버로 시연하는 절차를 안내합니다.
