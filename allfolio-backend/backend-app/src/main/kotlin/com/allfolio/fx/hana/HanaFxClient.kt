package com.allfolio.fx.hana

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface HanaFxClient {
    /** 지정일 고시 화면 HTML. 실패하면 예외를 던진다 — 호출자가 기존 값을 지키도록. */
    fun fetch(date: LocalDate): String
}

/**
 * 하나은행 고시환율 조회.
 *
 * 원본 `hana_fx_scraper.py`의 폼 파라미터를 그대로 옮겼다. 공식 API가 아니므로
 * `User-Agent`와 `Referer`가 없으면 응답이 달라질 수 있다.
 *
 * `pbldDvCd`: 오늘이면 3(현재고시), 과거면 0(최종고시).
 * 오늘 조회는 장중에 회차가 계속 올라가고, 과거 조회는 그날의 마지막 회차가 온다.
 */
@Component
class HanaFxWebClient : HanaFxClient {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("User-Agent", USER_AGENT)
            .defaultHeader("Referer", REFERER)
            .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }
            .build()
    }

    companion object {
        private const val BASE_URL = "https://www.kebhana.com"
        private const val PATH = "/cms/rate/wpfxd651_01i_01.do"
        private const val REFERER = "https://www.kebhana.com/cms/rate/wpfxd651_01i.do"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        private val TIMEOUT = Duration.ofSeconds(20)
        private val KST = ZoneId.of("Asia/Seoul")
        private val COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val DASHED = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    override fun fetch(date: LocalDate): String {
        val isToday = date == LocalDate.now(KST)
        val form = LinkedMultiValueMap<String, String>().apply {
            add("ajax", "true")
            add("curCd", "")
            add("tmpInqStrDt", date.format(DASHED))
            add("pbldDvCd", if (isToday) "3" else "0")
            add("pbldSqn", "")
            add("hid_key_data", "")
            add("inqStrDt", date.format(COMPACT))
            add("inqKindCd", "1")
            add("hid_enc_data", "")
            add("requestTarget", "searchContentDiv")
        }

        log.info("[하나은행] 고시 조회 date={} 구분={}", date, if (isToday) "현재고시" else "최종고시")

        return try {
            webClient.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw HanaFxParseException("응답 본문이 비어 있습니다")
        } catch (e: HanaFxParseException) {
            // 위 "본문 비어 있음". 우리가 만든 예외라 아래 갈아끼우기 대상이 아니다.
            // 아래 Throwable 절이 있으므로 이 절이 없으면 메시지가 "호출에 실패했습니다"로 덮인다.
            throw e
        } catch (e: WebClientException) {
            // WebClientRequestException(연결·DNS·TLS·reset)과 WebClientResponseException(4xx·5xx)의 공통 부모.
            // 인증이 없는 엔드포인트라 ECOS처럼 URI를 가릴 이유는 없지만, 원인은 클래스 이름만 남긴다 —
            // 응답 본문이 HTML 한 뭉치라 로그에 실으면 통째로 흘러든다.
            log.warn("[하나은행] 호출 실패 date={} reason={}", date, e.javaClass.simpleName)
            throw HanaFxParseException("하나은행 호출에 실패했습니다")
        } catch (e: Throwable) {
            // WebClientException만으로는 새는 경로가 있다. 대표적으로 block(TIMEOUT)의 타임아웃은
            // Reactor가 BlockingSingleSubscriber에서 IllegalStateException으로 새로 던지며,
            // 이건 WebClientException이 아니다 — 잡지 않으면 Task 7·11이 "하나은행 쪽 문제"로 분류하지 못한다.
            // 예외 종류를 열거하는 건 블랙리스트라 새 경로가 생길 때마다 샌다. 그래서 남은 전부를 갈아끼운다.
            if (e is Error) {
                // OutOfMemoryError를 "하나은행 호출 실패"로 둔갑시키면 운영자를 은행 쪽으로 보낸다.
                throw e
            }
            if (e is InterruptedException || e.cause is InterruptedException) {
                // 인터럽트 플래그가 지워지면 종료 중 끊긴 수집이 하나은행 장애로 읽힌다.
                Thread.currentThread().interrupt()
            }
            log.warn("[하나은행] 호출 실패 date={} reason={}", date, e.javaClass.simpleName)
            throw HanaFxParseException("하나은행 호출에 실패했습니다")
        }
    }
}
