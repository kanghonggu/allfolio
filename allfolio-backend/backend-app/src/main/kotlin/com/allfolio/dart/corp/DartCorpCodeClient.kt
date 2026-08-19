package com.allfolio.dart.corp

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartHttpConnector
import com.allfolio.dart.DartProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * `corpCode.xml` 응답 중 **상장사** 한 행. 비상장(`stock_code` 없음)은 파싱 단계에서 이미
 * 걸러지므로 이 타입에 도달한 행은 전부 `stockCode`가 있다 — 근거는 [DartCorpCodeClient] KDoc.
 */
data class DartCorpRow(
    val corpCode: String,
    val corpName: String,
    val stockCode: String,
    val modifyDate: LocalDate?,
)

/**
 * [totalRows] `corp_code`가 있는 행 전체 수(상장 여부 무관, 실측 118,712).
 * [listedRows] 그중 `stock_code`가 있어 실제로 [DartCorpRow]로 만들어진(=상장) 행. 실측 3,983건(3.4%).
 */
data class DartCorpParseResult(val totalRows: Int, val listedRows: List<DartCorpRow>)

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
 * `application/x-msdownload`라 Jackson으로 읽으면 깨진다. `ZipInputStream`으로 풀고 StAX로
 * XML을 스트리밍 파싱한다.
 *
 * **실측(2026-08-18, 실제 호출).** 계획서 Task 9은 이 구조를 "미검증 가정"이라 적어 두었으나
 * 이제 실측으로 확인했다:
 * - ZIP 3,596,918 bytes(3.4MB) → 해제 30,059,956 bytes(28.7MB), ZIP 엔트리명 `CORPCODE.xml`(대문자)
 * - 총 118,712행. `<result><list><corp_code>…` 구조가 계획서 가정과 일치했다
 * - `<list>` 태그는 5종을 담는다: `corp_code`·`corp_eng_name`·`corp_name`·`modify_date`·`stock_code`.
 *   `corp_eng_name`은 쓰지 않는다
 * - `stock_code` 있음(상장) 3,983행(3.4%), 공백(비상장) 114,729행(96.6%) — **빈 문자열이 아니라
 *   공백 한 칸(`" "`)**. 코틀린 `" ".isBlank()`가 true라 정규화는 문제없이 걸린다
 * - `modify_date` 파싱 불가 0건
 *
 * **상장사만 적재한다 — 118,712행 중 3,983행(3.4%)만 [DartCorpParseResult.listedRows]에 남는다.**
 * 이 테이블의 존재 이유 둘 다(수집 시점 스냅샷 보정, `corp_code`→종목 역방향 조회) `stock_code`가
 * 있어야 성립한다 — 비상장 96.6%는 어느 쪽에도 기여하지 않는다. Neon CU-hours가 이 프로젝트의
 * 문서화된 병목(설계 1절 원칙 2)인데 쓰지도 않을 30배를 적재할 이유가 없다.
 *
 * **DOM이 아니라 StAX로 파싱하고, 파싱 중에 상장 필터를 적용한다.** 28.7MB XML을 DOM으로 올리면
 * 트리 노드 오버헤드 때문에 통상 원본의 수 배~수십 배 힙을 쓴다. Render 무료 인스턴스가 512MB이고
 * 스프링 앱이 이미 상당량을 쓰는 상황에서 이 여유가 없다. StAX로 훑으면서 `stock_code`가 공백인
 * 행은 [DartCorpRow]로 만들지 않고 그 자리에서 버리므로, 메모리에 남는 건 118,712행이 아니라
 * 최종 3,983행뿐이다.
 *
 * **StAX에도 XXE 방지를 건다.** [XMLInputFactory.SUPPORT_DTD]를 꺼서 DTD 선언 자체를 거부한다
 * (DOM에 걸었던 `disallow-doctype-decl`과 동등한 방어). 외부(OpenDART)가 만든 XML을 그대로
 * 파싱하는 지점이라 최소한의 방어는 걸어 둔다.
 *
 * **전 종목 매핑이라 응답이 수 MB다.** WebClient의 in-memory 버퍼 상한(기본 256KB)을 올려야
 * 한다 — 주 1회 호출이면 이 정도 메모리는 감당할 만하다는 게 애초에 주 1회로 잡은 이유다.
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
            // **커넥터를 명시한다.** reactor-netty 기본 암호군에는 DHE가 없고 OpenDART는
            // ECDHE를 전부 거절해 교집합이 빈다 — 근거는 [DartHttpConnector] KDoc
            .clientConnector(connector ?: DartHttpConnector.create())
            .build()
    }

    /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** — 근거는 `dedicatedConnector()` 주석 */
    internal var connector: ClientHttpConnector? = null

    /** 전 종목 매핑이라 `list.json`보다 느릴 수 있어 기본 타임아웃의 배수를 쓴다. 테스트에서만 줄인다 */
    internal var timeout: Duration = Duration.ofSeconds(props.timeoutSeconds * TIMEOUT_MULTIPLIER)

    fun fetch(): DartCorpParseResult {
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

        /** [parseZip]가 값을 누적하는 leaf 태그. `corp_eng_name`은 여기 없어 자동으로 무시된다 */
        private val TRACKED_TAGS = setOf("corp_code", "corp_name", "stock_code", "modify_date")

        /**
         * ZIP을 풀어 StAX로 훑으며 `<list>` 행마다 상장 여부를 가른다.
         *
         * - `corp_code`가 없거나 공백뿐이면(태그 자체가 없든, 태그는 있는데 내용이 없든) 그 행
         *   전체를 버린다 — `totalRows`에도 안 잡힌다.
         * - `stock_code`가 없거나 공백뿐이면 비상장이다 — [DartCorpRow]를 만들지 않고 버린다.
         *   `totalRows`에는 잡히지만 `listedRows`에는 안 남는다.
         * - `modify_date`는 없거나 `yyyyMMdd`로 못 읽으면 null로 둔다 — 한 행의 파싱 실패로
         *   전체를 죽이지 않는다.
         */
        fun parseZip(zipBytes: ByteArray): DartCorpParseResult =
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory }
                    ?: throw DartApiException("corpCode ZIP에 항목이 없습니다")
                // **`readBytes()`로 통째로 올리지 않는다.** 압축 해제분이 실측 28.7 MB인데
                // Render 무료는 RAM 512 MB라 JVM 기본 최대 힙이 그 1/4인 128 MB다 —
                // ZIP 3.4 MB + XML 28.7 MB + StAX 버퍼가 겹쳐 OutOfMemoryError로 죽었다
                // (2026-08-19 운영 첫 corp_map 실행). StAX에 스트림을 그대로 물리면
                // 상장사 3,983건만 메모리에 남고 나머지는 흘려보낸다.
                parseXml(zip)
            }

        private fun parseXml(xml: java.io.InputStream): DartCorpParseResult {
            val factory = XMLInputFactory.newInstance().apply {
                // XXE 방지 — DOM에 걸었던 disallow-doctype-decl과 동등한 방어
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }
            val reader = factory.createXMLStreamReader(xml)

            var totalRows = 0
            val listed = mutableListOf<DartCorpRow>()

            try {
                var currentTag: String? = null
                var corpCode: StringBuilder? = null
                var corpName: StringBuilder? = null
                var stockCode: StringBuilder? = null
                var modifyDate: StringBuilder? = null

                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            when (reader.localName) {
                                "list" -> {
                                    corpCode = null; corpName = null; stockCode = null; modifyDate = null
                                    currentTag = null
                                }
                                in TRACKED_TAGS -> currentTag = reader.localName
                                else -> currentTag = null
                            }
                        }
                        XMLStreamConstants.CHARACTERS -> {
                            val target = when (currentTag) {
                                "corp_code" -> corpCode ?: StringBuilder().also { corpCode = it }
                                "corp_name" -> corpName ?: StringBuilder().also { corpName = it }
                                "stock_code" -> stockCode ?: StringBuilder().also { stockCode = it }
                                "modify_date" -> modifyDate ?: StringBuilder().also { modifyDate = it }
                                else -> null
                            }
                            target?.append(reader.text)
                        }
                        XMLStreamConstants.END_ELEMENT -> {
                            if (reader.localName == "list") {
                                val code = corpCode?.toString()?.trim()
                                if (!code.isNullOrBlank()) {
                                    totalRows++
                                    val stock = stockCode?.toString()?.trim()
                                    if (!stock.isNullOrBlank()) {
                                        listed += DartCorpRow(
                                            corpCode = code,
                                            corpName = corpName?.toString()?.trim().orEmpty(),
                                            stockCode = stock,
                                            modifyDate = modifyDate?.toString()?.trim()?.ifBlank { null }
                                                ?.let { runCatching { LocalDate.parse(it, DATE_FORMAT) }.getOrNull() },
                                        )
                                    }
                                }
                            }
                            currentTag = null
                        }
                        else -> {}
                    }
                }
            } finally {
                reader.close()
            }

            return DartCorpParseResult(totalRows, listed)
        }
    }
}
