# SingMOS 단독 파일 평가

WBS 1.1의 첫 구현이다. Android·서버와 분리하여 짧은 한국어 노래 녹음에서 SingMOS 원점수를 확인한다. 설치·실행은 사용자가 직접 한다. 현재 이 스크립트의 설치·실행·결과는 미검증이다.

## 목표와 범위

3~30초 WAV를 읽고 mono·16kHz로 변환하여 공식 `South-Twilight/SingMOS:v1.1.2`의 `singmos_pro`를 CPU에서 실행한다. 원점수, 모델 로딩 시간, 추론 시간, 환경 버전을 JSON으로 저장한다. 최초 모델 다운로드에는 인터넷이 필요하다. 이 스크립트는 녹음 파일을 외부 서버에 전송하지 않는다.

점수는 노랫소리의 자연스러움·품질 예측값이다. 한국곡의 원곡 대비 음정·박자 정확도, 검증된 실력 점수 또는 TJ·금영 채점으로 표현하지 않는다. `display_score_100`은 아직 null이다. 원점수 확인 후 표시 규칙을 정한다.

## 작업 위치

Android Studio Terminal 또는 macOS Terminal에서 실행한다. Gradle Sync·Android 빌드는 필요 없다.

```bash
cd /Users/saseoghyeon/AndroidStudioProjects/PleosSdkPlayground/experiments/singmos
```

## 1 설치 환경 준비

프로젝트 전용 가상환경을 쓴다. Python 3.10은 초기 호환성 확인을 위한 권장 시작점이며, 실제 설치 성공은 확인해야 한다. 설치되어 있지 않으면 임의의 최신 Python으로 진행하지 말고 설치 여부와 오류를 공유한다. 이 작업에서는 Python을 설치하거나 기존 환경을 변경하지 않았다.

```bash
python3.10 --version
python3.10 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

requirements.txt는 검증된 고정 버전 목록이 아니다. upstream이 제공하는 일반 의존성을 바탕으로 만들었다. 특히 s3prl·fairseq 등 간접 의존성과 최신 PyTorch 조합은 실제 설치 결과를 보고 맞춰야 한다. 설치 실패 시 이어서 실행하지 않는다.

## 2 짧은 녹음 준비

반주 없이 부른 10~15초 한국어 노래 WAV 한 개를 먼저 준비한다. `samples/vocal.wav`로 저장한다. 음성 메모 M4A 파일은 확장자만 바꾸지 말고 오디오 도구에서 실제 WAV로 내보낸다. samples 디렉터리의 파일은 Git에 포함되지 않는다.

## 3 파일 평가

```bash
python evaluate.py samples/vocal.wav --output results/vocal-01.json
```

최초 실행은 upstream Python 코드와 기반 모델·가중치를 다운로드하고 로드한다. 공식 태그를 지정하며 `trust_repo=True`는 해당 코드 실행에 동의하는 upstream 로딩 방식이다. 모델 캐시는 일반적으로 PyTorch 캐시 위치를 사용한다. 모델 다운로드나 체크포인트 오류를 해결하려고 전역 보안 옵션을 임의로 해제하지 않는다.

CPU 실행에는 시간이 걸릴 수 있다. 로딩과 추론 로그를 구분하여 확인한다. 실행 중단은 Ctrl+C로 한다. 기존 결과 파일은 덮어쓰지 않으므로 재시도 시 다른 출력 이름을 쓴다.

## 확인 기준

터미널 태그는 Logcat 규칙에 맞춘 `SHSH_SCORE`다. Android Logcat이 아니라 이 명령을 실행한 터미널에서 확인한다.

- `Input accepted`: 실제 WAV 길이·샘플레이트 확인.
- `Model loaded`: 다운로드와 로딩 성공, 걸린 시간 출력.
- `Inference finished`: 유한한 `raw_mos` 숫자와 추론 시간 출력.
- `Result JSON saved`: 결과 파일 생성.

results/vocal-01.json에서 `status=succeeded`, `raw_mos`, `repository`, `model`, `timing`, `environment`를 확인한다. 점수 예시값이나 허위 성공 결과는 포함하지 않았다. 짧은 입력에 대한 결과이므로 아직 한 곡 전체 평가 성능으로 해석하지 않는다.

성공 후 환경 기록:

```bash
python -m pip freeze > results/environment.txt
```

먼저 보컬 파일 하나의 성공 결과를 확인한 뒤 다음 단계로 진행한다. WBS 1.2에서는 동일 길이의 보컬·반주 혼입·다른 녹음을 비교한다. 완전 무음은 이 스크립트가 오류로 거절하며 성공 점수를 생성하지 않는다. 서로 다른 모델의 점수를 같은 척도로 섞지 않는다.

## 실패 시 공유할 것

- `python --version`, `python -m pip show torch torchaudio s3prl librosa`
- 설치 실패 시 처음 오류부터 마지막 오류까지.
- 실행 실패 시 SHSH_SCORE 로그와 전체 traceback. 공유 전에 사용자 경로·토큰 등은 가린다.
- 오디오 길이·포맷, 마지막으로 출력된 처리 단계.

M4A/MP3 디코더 추가, 파일 긴 구간 자동 절단, MPS/GPU, HTTP 서버는 이 단계에서 구현하지 않는다. 녹음 원본을 공유할 필요 없이 우선 로그·결과 JSON으로 확인한다.

## 출처와 이용 조건

- 공식 예제: https://github.com/South-Twilight/SingMOS/tree/v1.1.2
- 로딩 코드: https://github.com/South-Twilight/SingMOS/blob/v1.1.2/hubconf.py
- 모델 입력 코드: https://github.com/South-Twilight/SingMOS/blob/v1.1.2/singmos/ssl_mos/singmos_pro.py
- 코드 라이선스: https://github.com/South-Twilight/SingMOS/blob/v1.1.2/LICENSE

공식 입력·모델 로딩 패턴을 바탕으로 독립 실행 래퍼를 작성했다. SingMOS 코드의 MIT 표시만으로 모델 가중치·기반 모델·모든 의존성의 조건이 동일하다고 가정하지 않는다. 배포 전 각 이용 조건을 확인한다. 현재 연구 시제품 적합성과 한국어·차량 녹음 성능은 검증 대상이다.

## 2026-10-26 호환성 오류 기록

사용자 실행에서 WAV 입력 수신은 성공했지만 s3prl 0.4.18의 BYOL 모듈 import 중 `torchaudio.set_audio_backend` 부재로 모델 로딩이 실패했다. 로컬 버전 파일에서 torch 2.14.1 / torchaudio 2.11.0을 확인했다. FutureWarning은 이번 종료 원인이 아니다.

requirements의 torch·torchaudio를 공식 macOS 설치 조합인 2.5.1로, s3prl을 현재 설치 버전 0.4.18로 고정했다. 이는 레거시 API 호환성을 위한 후보이며 이 프로젝트의 설치·추론 성공을 아직 확인하지 않았다. 검증된 전체 lockfile은 아니다.

활성 가상환경에서 사용자가 실행:

```bash
python -m pip install -r requirements.txt
python -m pip check
python -c "import torch, torchaudio; print('torch:', torch.__version__); print('torchaudio:', torchaudio.__version__); print('set_audio_backend:', hasattr(torchaudio, 'set_audio_backend'))"
```

두 버전이 2.5.1, 속성 확인이 True이며 의존성 충돌이 없는지 먼저 확인한다. 실패하면 전체 출력을 공유하고 모델 재실행은 보류한다. 설치 성공 확인 후 동일 evaluate 명령을 재실행한다. site-packages나 torch hub 캐시의 소스 코드를 직접 바꾸지 않는다.

근거: https://docs.pytorch.org/get-started/previous-versions/ 및 https://docs.pytorch.org/audio/2.8/torchaudio.html
