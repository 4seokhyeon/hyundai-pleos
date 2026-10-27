# 로컬 한국곡 검색 서버

> 이전 중계 방식의 참고 코드다. 현재 Android 앱은 YouTube API를 직접 호출하므로 서버를 켤 필요가 없다. 최신 절차는 ../docs/YOUTUBE_DIRECT_SEARCH.md를 따른다.

YouTube Data API v3의 search.list를 중계하는 개발용 서버다. Python 표준 라이브러리만 사용한다. API 키는 프로젝트 루트 local.properties에서 읽고, 없으면 환경변수에서 읽으며 앱·Git·로그에는 저장하지 않는다. 현재 설치·실행·검색 호출은 미검증이다.

## 키 준비

Google Cloud Console에서 프로젝트를 선택하고 YouTube Data API v3를 활성화한 뒤 API 키를 만든다. API 제한은 YouTube Data API v3로 지정한다. 이는 Android 키가 아니라 서버가 호출하는 키다. 서버 IP 제한이 필요하면 개발 Mac의 외부 IP에 맞춰 설정한다. 키를 채팅·스크린샷·Git에 공유하지 않는다. 실제 할당량·사용 가능 여부는 콘솔에서 확인한다.

- https://console.cloud.google.com/apis/library/youtube.googleapis.com
- https://developers.google.com/youtube/v3/getting-started

## 직접 실행

Android Studio에서 프로젝트 루트 `local.properties`를 열고 기존 `sdk.dir` 줄을 유지한 채 다음 줄을 추가한다. 값은 따옴표 없이 실제 키를 입력한다.

```properties
YOUTUBE_API_KEY=발급받은_실제_키
```

파일: `/Users/saseoghyeon/AndroidStudioProjects/PleosSdkPlayground/local.properties`

이 파일은 기존 .gitignore에 포함되어 있다. 키는 Python 서버만 읽고 APK의 BuildConfig·리소스에 포함하지 않는다. 파일의 키를 우선 사용하고 없으면 YOUTUBE_API_KEY 환경변수를 사용한다. 키 내용을 로그에 출력하지 않는다.

저장 후 서버를 시작한다. 이미 실행 중이면 Ctrl+C로 종료한 뒤 다시 시작한다.

```bash
cd /Users/saseoghyeon/AndroidStudioProjects/PleosSdkPlayground
/opt/homebrew/bin/python3.10 server/search_server.py
```

127.0.0.1:8090에서만 수신하는 로컬 서버다. 외부에 공개하지 않는다. 키를 파일로 설정할 수 있지만 앱이 검색 서버를 거치는 구조이므로 서버 프로세스 실행은 필요하다. 앱 재빌드는 필요 없다.

다른 터미널 또는 Mac 브라우저에서 `http://127.0.0.1:8090/health`를 연다. status=ok와 youtube_key_configured=true를 확인한다. true는 키가 입력됐다는 뜻이며 유효성·API 활성화 성공은 실제 검색으로 확인한다.

## 앱 연결

Android Studio에서 debug app 빌드·실행 후 영상 검증 화면을 연다. 정차 상태가 확인되면 곡 제목·가수로 검색하거나 음성으로 곡 검색 버튼을 누른다. `좋은 날 아이유 찾아줘`처럼 말하면 STT 최종 결과로 검색한다. 말 끝 명령어를 간단히 제거하며 완전한 자연어 이해 기능은 아니다.

앱은 에뮬레이터의 호스트 주소 `10.0.2.2:8090`을 사용한다. debug 빌드만 해당 호스트의 HTTP를 허용하며 release 배포 전 인증된 HTTPS 서버로 교체해야 한다. 실차·실기기에서 이 주소를 그대로 사용할 수 있는 것으로 가정하지 않는다.

검색 결과 최대 5개에 제목과 채널이 표시된다. 선택하면 영상 ID가 내부에서 전달되고 공식 플레이어를 연다. 사용자에게 ID를 입력하거나 말하도록 요구하지 않는다. 후보를 자동 재생하지 않고 공식 재생 버튼을 누른다. 지역·삽입 필터는 실제 재생 성공과 콘텐츠 이용 승인을 보장하지 않는다.

## 확인과 오류

- 서버: SHSH_SEARCH Search requested / Search finished, 결과 개수 확인.
- 앱: SHSH_SEARCH와 SHSH_STT, SHSH_PLAYER, SHSH_UX 확인.
- youtube_key_missing: local.properties 키 입력·저장 후 서버 다시 시작.
- accessNotConfigured: API 활성화 확인.
- quotaExceeded / dailyLimitExceeded: 콘솔 할당량 확인. 무한 자동 재시도하지 않는다.
- keyInvalid / forbidden / ipRefererBlocked: 키와 서버 호출 제한 확인.
- 앱 연결 실패: 서버 실행·8090 포트·debug 설치 여부 확인.

추가 인증·DB·페이징·공개 서버 배포·노래 예약 보관·노래 채점은 이번 변경 범위가 아니다. 소수 검색을 수동 요청하고 타이핑 중 자동 호출하지 않는다. 사용자 실행 결과 확인 후 확장한다.

## 근거

https://developers.google.com/youtube/data/reference/rest/v3/search/list
https://developers.google.com/youtube/terms/developer-policies
