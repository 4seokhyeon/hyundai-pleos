# Pleos SDK 검증 기록

검증일: 2026-10-25 (Asia/Seoul)

## 환경과 범위

- 프로젝트: PleosSdkPlayground / com.shsh.pleos.playground
- Vehicle SDK: ai.pleos.playground:Vehicle:2.3.2
- Pleos Connect 2.1.0 전용 AVD, API 34, arm64
- 검증은 에뮬레이터 Vehicle Service 대상이며 실제 차량 지원을 보장하지 않는다.
- 이번 단계는 읽기만 구현. Mock, 차량 제어, 변경 알림 구독은 구현하지 않았다.

## 확인 결과

- :app:assembleDebug: BUILD SUCCESSFUL
- Pleos AVD에 APK 설치 및 실행 성공
- checkDoorPositionCapabilities: access=READ_WRITE, ROW_1_LEFT 영역 존재
- getCurrentDoorPosition: value=0, 지원 범위=0..1
- initialize 반환과 실제 API 응답을 별도 로그로 기록
- 연결 실패/권한 부족/미지원/시간 초과 처리 코드는 추가했으나 각 실패 상황의 런타임 재현은 아직 하지 않음

## 코드 흐름

Compose → VehicleProbeViewModel(StateFlow, viewModelScope) → VehicleRepository → SDK
SDK callback은 suspendCancellableCoroutine으로 연결하고 조회 전체에 10초 제한 적용.
화면 상태는 collectAsStateWithLifecycle로 관찰. ViewModel 종료 시 Vehicle.release 호출.
앱 실행 시 자동 조회하며 버튼으로 다시 조회할 수 있다.
권한은 pleos.car.permission.CAR_DOORS만 추가했다.
Logcat 필터: tag:SHSH

## SDK 문서와 배포물 차이

공식 오류 문서는 VehicleException과 ErrorCode를 설명하지만 현재 내려받은 2.3.2 AAR에서는 해당 공개 이름을 import할 수 없어 컴파일에 실패했다.
앱은 공개 callback 계약인 Exception을 받아 메시지를 기록한다. SecurityException과 자체 capability 미지원 판단 외에는 오류 원인을 추측해 분류하지 않는다.
향후 SDK 배포 수정 여부 확인 필요.

## 공식 근거

- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/get-property
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code
