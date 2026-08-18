package com.allfolio.dart

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * OpenDART(전자공시시스템 오픈API) 접속 설정 — D1 공시 연동.
 *
 * @param apiKey 빈 값이면 클라이언트가 예외를 던진다 — 조용히 빈 목록을 주면
 *   "키를 안 넣었다"가 "그날 공시가 없었다"로 굳는다. `status 013`(공휴일)과 구분이 안 된다.
 * @param pageCount 최대 100. 실측 최다일(2026-08-14 반기보고서 마감)이 4,555건 = 46페이지다.
 *
 * yml 키 오타(예: `page-count` → `page-cnt`) 검사는 여기가 아니라
 * `DartPropertiesYamlTest`에서 한다 — `ignoreUnknownFields = false`를 여기 걸면
 * relaxed binding 때문에 `DART_`로 시작하는 환경변수 하나만 잘못 들어와도(오타든
 * 코드보다 먼저 얹힌 `DART_DEBUG` 같은 것이든) `dart.*` 아래 미지의 키가 되어
 * 운영 기동이 통째로 실패한다. 형제 설정 클래스(`FredProperties`·`EcosProperties`·
 * `CommodityProperties`)도 전부 스프링 기본값(`true`)을 쓴다 — 이 클래스만 다르게
 * 둘 이유가 없다.
 */
@ConfigurationProperties(prefix = "dart")
data class DartProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://opendart.fss.or.kr/api",
    val pageCount: Int = 100,
    val timeoutSeconds: Long = 30,
)
