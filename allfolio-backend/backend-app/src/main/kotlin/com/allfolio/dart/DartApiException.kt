package com.allfolio.dart

/**
 * OpenDART(전자공시시스템 오픈API) 호출 실패.
 *
 * **수집 클라이언트 전용이 아니라 `dart` 패키지 공용이다.** `list.json`(`DartListClient`)에서
 * 태어났지만, Task 9(`dart/corp/`) · Task 10(`dart/insider/`) · Task 12(어드민)가 전부 이 타입을
 * `import com.allfolio.dart.DartApiException`으로 받아 쓴다. 클라이언트마다 예외 타입을 새로 만들면
 * 그 순간부터 수집 서비스가 타입을 그만큼 다 알아야 하고, 하나를 빠뜨리면 그 클라이언트의 실패만
 * 격리를 빠져나가 배치 전체를 죽인다 — 그런데 코드도 메시지도 비슷해서 로그만 봐선 왜 한쪽만
 * 다른지 알 수 없다. 근거는 `FscApiException`이 `market.fsc`에 공용으로 있는 것과 같다.
 *
 * **`message`에 응답 본문·요청 URL을 싣지 말 것.** OpenDART 인증키가 쿼리 파라미터(`crtfc_key=`)에
 * 실리고, 예외에 `cause`를 붙이면(Reactor checkpoint 프레임에 요청 URI가 통째로 들어 있다) 그대로
 * 샌다. 이 메시지는 어드민 응답과 GitHub Actions 주석까지 나가는 값이다 — 방어의 근거는 각
 * 클라이언트(`DartListClient` 등)의 KDoc에 있다.
 */
class DartApiException(message: String) : RuntimeException(message)
