package com.allfolio.dart.corp

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `corpCode.xml` 응답 한 행.
 *
 * **`stock_code`는 비상장이면 공백/빈 문자열로 온다** — `list.json`(`DartListRow`)과 같은 습성이다.
 * 여기서 null로 정규화해 두지 않으면 `dart_corp_map`의 부분 인덱스(`WHERE stock_code IS NOT NULL`)가
 * 무용지물이 된다.
 */
data class DartCorpRow(
    val corpCode: String,
    val corpName: String,
    val stockCode: String?,
    val modifyDate: LocalDate?,
)

/**
 * OpenDART(전자공시시스템 오픈API) 전 종목 고유번호 매핑 `corpCode.xml`.
 *
 * **이 테이블(`dart_corp_map`)은 이 계획 안에서 아무도 읽지 않는다.** `list.json`이 행마다
 * `stock_code`를 이미 주기 때문에 수집·조회 어느 쪽도 이 매핑이 필요 없다. 그럼에도 만드는
 * 이유는 둘이다: (1) `list.json`의 `stock_code`는 **수집 시점 스냅샷**이라 상장폐지·코드변경이
 * 나면 과거 행이 옛 코드를 든 채 굳는다 — 권위 있는 현재 매핑이 따로 있어야 그때 되짚을 수
 * 있다. (2) `corp_code`만 아는 상태에서 종목을 찾는 역방향 조회(향후 종목별 공시 이력 화면)의
 * 유일한 경로다. 다음 사람이 "왜 안 쓰는 걸 만들었지" 하지 않도록 남긴다.
 *
 * **`corpCode.xml`은 ZIP으로 온다 — JSON이 아니다.** 응답 Content-Type이
 * `application/x-msdownload`라 Jackson으로 읽으면 깨진다. `ZipInputStream`으로 풀고 DOM으로
 * XML을 파싱한다.
 *
 * **전 종목 매핑이라 응답이 수 MB다.** WebClient의 in-memory 버퍼 상한(기본 256KB)을 올려야
 * 한다 — 주 1회 호출이면 이 정도 메모리는 감당할 만하다는 게 애초에 주 1회로 잡은 이유다.
 *
 * **응답 구조는 아직 실측하지 않은 가정이다.** `<result><list><corp_code>…` 구조와
 * ZIP 내부 파일명(`CORPCODE.xml`)은 계획서(Task 9)에 적힌 값을 그대로 따른 것으로, 실제
 * 필드명·파일명이 다를 수 있다. 실측 확인은 배포 후 corp-map 워크플로 수동 실행에서 한다.
 *
 * **🔴 인증키가 쿼리 파라미터(`crtfc_key=`)에 실린다.** `DartListClient`와 같은 방어 셋을
 * 지킨다: 전체 URL을 로그에 찍지 않는다 · 예외에 `cause`를 붙이지 않는다(Reactor checkpoint
 * 프레임에 요청 URI가 통째로 들어 있다) · 응답 본문 미리보기를 남기지 않는다.
 *
 * **키가 비면 호출하지 않고 예외를 던진다.** 조용히 빈 목록을 주면 "키를 안 넣었다"가 다른
 * 실패와 구분이 안 된다.
 */
@Component
class DartCorpCodeClient(private val props: DartProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(props.baseUrl)
            // 전 종목 매핑 ZIP이 수 MB다 — 기본 256KB 상한으로는 DataBufferLimitException이 난다
            .codecs { it.defaultCodecs().maxInMemorySize(BUFFER_LIMIT_BYTES) }
            .also { builder -> connector?.let(builder::clientConnector) }
            .build()
    }

    /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** — 근거는 `dedicatedConnector()` 주석 */
    internal var connector: ClientHttpConnector? = null

    /** 전 종목 매핑이라 `list.json`보다 느릴 수 있어 기본 타임아웃의 배수를 쓴다. 테스트에서만 줄인다 */
    internal var timeout: Duration = Duration.ofSeconds(props.timeoutSeconds * TIMEOUT_MULTIPLIER)

    fun fetch(): List<DartCorpRow> {
        // 설정 누락은 상류 장애가 아니라 우리 문제다. 조용히 빈 목록을 주면 다른 실패와 구분이 안 된다
        if (props.apiKey.isBlank()) {
            throw DartApiException("DART_API_KEY가 설정되지 않았습니다")
        }

        log.info("[DART] corpCode.xml 조회")

        val bytes = try {
            webClient.get()
                .uri { b -> b.path(PATH).queryParam("crtfc_key", props.apiKey).build() }
                .retrieve()
                .bodyToMono(ByteArray::class.java)
                .block(timeout)
                ?: throw DartApiException("corpCode 응답 본문이 비어 있습니다")
        } catch (e: DartApiException) {
            throw e
        } catch (e: WebClientResponseException) {
            // 상태만 남긴다. 본문에는 우리 요청 URL이 되울려 올 수 있고 거기 키가 들어 있다.
            // cause도 붙이지 않는다 — Reactor checkpoint 프레임에 URI가 통째로 있다
            log.warn("[DART] corpCode HTTP {}", e.statusCode.value())
            throw DartApiException("OpenDART가 HTTP ${e.statusCode.value()} 를 반환했습니다")
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            log.warn("[DART] corpCode 호출 실패 reason={}", e.javaClass.simpleName)
            throw DartApiException("corpCode.xml 호출에 실패했습니다")
        }

        // ZIP이 아니거나 내부 XML이 깨진 경우도 여기서 갈아낀다 — 원본 바이트를 메시지에 싣지 않는다
        return try {
            parseZip(bytes)
        } catch (e: DartApiException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            log.warn("[DART] corpCode ZIP 해석 실패 reason={}", e.javaClass.simpleName)
            throw DartApiException("corpCode ZIP을 해석할 수 없습니다")
        }
    }

    companion object {
        /** 오퍼레이션 경로. 테스트가 "예외 어디에도 이 조각이 없다"로 유출을 본다 */
        internal const val PATH = "/corpCode.xml"

        private const val BUFFER_LIMIT_BYTES = 32 * 1024 * 1024
        private const val TIMEOUT_MULTIPLIER = 4L
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * ZIP을 풀어 `<list>` 행마다 [DartCorpRow]로 옮긴다.
         *
         * - `corp_code`가 없는 행은 버린다.
         * - `stock_code`는 trim 후 공백/빈 문자열이면 null로 정규화한다(비상장).
         * - `modify_date`는 없거나 `yyyyMMdd`로 못 읽으면 null로 둔다 — 한 행의 파싱 실패로
         *   전체를 죽이지 않는다.
         *
         * XXE 방지를 위해 DTD 선언을 거부한다 — 외부(OpenDART)가 만든 XML을 그대로 파싱하는
         * 지점이라 최소한의 방어는 걸어 둔다.
         */
        fun parseZip(zipBytes: ByteArray): List<DartCorpRow> {
            val xmlBytes = ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory }
                    ?: throw DartApiException("corpCode ZIP에 항목이 없습니다")
                zip.readBytes()
            }

            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))

            val nodes = doc.getElementsByTagName("list")
            return (0 until nodes.length).mapNotNull { i ->
                val el = nodes.item(i) as Element
                val corpCode = el.textOf("corp_code")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DartCorpRow(
                    corpCode = corpCode,
                    corpName = el.textOf("corp_name").orEmpty(),
                    stockCode = el.textOf("stock_code")?.ifBlank { null },
                    modifyDate = el.textOf("modify_date")?.ifBlank { null }
                        ?.let { runCatching { LocalDate.parse(it, DATE_FORMAT) }.getOrNull() },
                )
            }
        }

        private fun Element.textOf(tag: String): String? =
            getElementsByTagName(tag).item(0)?.textContent?.trim()
    }
}
