# AI 금융 상담사 설계

**날짜:** 2026-05-04  
**상태:** 작성 중

---

## 요약

사용자가 자신의 OpenAI-compatible LLM API 키를 등록하면, 백엔드가 포트폴리오 데이터를 시스템 프롬프트로 주입해 금융 상담 채팅을 제공한다. 대화 이력은 React 상태에만 유지되며 매 세션은 독립적이다.

---

## 아키텍처

```
[설정 페이지] → POST /api/ai/config → DB(ua_ai_configs)

[채팅 페이지]
  사용자 메시지
  → POST /api/ai/chat { messages }
  → 백엔드: ua_ai_configs에서 LLM 설정 조회
  → 백엔드: ua_assets + ua_stock_trades로 시스템 프롬프트 생성 (매 요청)
  → WebClient로 사용자 LLM API 스트리밍 호출
  → SseEmitter로 토큰 스트리밍
  → 프론트: React state에 누적 (페이지 이탈 시 사라짐)
```

시스템 프롬프트는 매 요청마다 새로 생성한다. DB 조회 비용이 낮고 항상 최신 데이터를 반영할 수 있다.

---

## 데이터베이스

### `ua_ai_configs` 테이블

```sql
CREATE TABLE IF NOT EXISTS ua_ai_configs (
    user_id     UUID          NOT NULL,
    base_url    VARCHAR(500)  NOT NULL,   -- "https://api.openai.com/v1"
    api_key     VARCHAR(1000) NOT NULL,   -- 사용자 API 키 (평문, ua_accounts.api_key 와 동일 패턴)
    model       VARCHAR(200)  NOT NULL,   -- "gpt-4o", "claude-3-5-sonnet", "llama-3.3-70b"
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_ai_configs PRIMARY KEY (user_id)
);
```

`infra/postgres/init.sql`에 추가한다.

---

## 백엔드

### 신규 파일

**`unified-asset/.../infrastructure/entity/UserAiConfigEntity.kt`**

```kotlin
@Entity
@Table(name = "ua_ai_configs")
class UserAiConfigEntity(
    @Id @Column(columnDefinition = "uuid")
    val userId: UUID,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val updatedAt: LocalDateTime,
)
```

**`unified-asset/.../infrastructure/jpa/UserAiConfigJpaRepository.kt`**

```kotlin
interface UserAiConfigJpaRepository : JpaRepository<UserAiConfigEntity, UUID>
```

**`unified-asset/.../application/usecase/AiConsultantService.kt`**

```kotlin
@Service
class AiConsultantService(
    private val configRepo: UserAiConfigJpaRepository,
    private val jdbc: JdbcTemplate,
    private val webClientBuilder: WebClient.Builder,
)
```

제공 메서드:
- `getConfig(userId): AiConfigResponse?` — API 키 제외하고 반환
- `saveConfig(userId, req: SaveAiConfigRequest)` — upsert
- `deleteConfig(userId)`
- `chat(userId, messages: List<ChatMessage>): SseEmitter` — 스트리밍

데이터 클래스:
```kotlin
data class AiConfigResponse(val baseUrl: String, val model: String, val hasKey: Boolean)
data class SaveAiConfigRequest(val baseUrl: String, val apiKey: String, val model: String)
data class ChatMessage(val role: String, val content: String)  // role: "user" | "assistant"
```

**시스템 프롬프트 빌더** (`buildSystemPrompt(userId)`)

`ua_assets`와 `ua_stock_trades`를 JdbcTemplate으로 직접 조회해 아래 구조로 포맷:

```
당신은 사용자의 개인 금융 자문 AI입니다. 오늘 날짜: {date}
사용자의 실제 포트폴리오 데이터를 기반으로 구체적이고 실용적인 조언을 제공하세요.

## 포트폴리오 요약
- 총 자산(NAV): {nav}원
- 미실현 손익: {pnl}원 ({pnlPct}%)
- 보유 계좌: {n}개 | 보유 자산: {n}개

## 주요 보유 종목 (상위 10개)
| 종목명 | 유형 | 현재가치 | 비중 |
...

## 자산 배분
유형별: 주식 {x}%, 암호화폐 {x}%, 현금 {x}% ...
통화별: KRW {x}%, USD {x}% ...

## 올해 배당 수령액
총 {n}원 ({n}회)

데이터 기준 시각: {generatedAt}
```

쿼리:
1. `SELECT SUM(current_value), COUNT(*) FROM ua_assets WHERE user_id = ?` — NAV + 자산 수
2. `SELECT SUM(current_value - purchase_price * quantity) FROM ua_assets WHERE user_id = ?` — 미실현 손익
3. `SELECT name, type, current_value, currency FROM ua_assets WHERE user_id = ? ORDER BY current_value DESC LIMIT 10` — 상위 종목
4. `SELECT type, SUM(current_value) FROM ua_assets WHERE user_id = ? GROUP BY type` — 유형별
5. `SELECT currency, SUM(current_value) FROM ua_assets WHERE user_id = ? GROUP BY currency` — 통화별
6. `SELECT SUM(total_amount), COUNT(*) FROM ua_stock_trades WHERE user_id = ? AND trade_type = 'DIVIDEND' AND EXTRACT(YEAR FROM traded_at) = ?` — 올해 배당
7. `SELECT COUNT(DISTINCT id) FROM ua_accounts WHERE user_id = ?` — 계좌 수

**채팅 프록시** (`chat`)

1. `configRepo.findById(userId)` — 설정 없으면 `IllegalStateException("LLM 설정이 없습니다")`
2. `buildSystemPrompt(userId)` 호출
3. 시스템 메시지를 messages 앞에 prepend
4. WebClient로 `{baseUrl}/chat/completions` POST (stream: true)
5. 응답 토큰을 `SseEmitter`로 전달
6. `[DONE]` 수신 시 `emitter.complete()`

OpenAI-compatible 요청 형식:
```json
{
  "model": "{model}",
  "stream": true,
  "messages": [
    {"role": "system", "content": "{systemPrompt}"},
    {"role": "user", "content": "..."},
    ...
  ]
}
```

스트리밍 응답 파싱: `data: {...}` 형식에서 `choices[0].delta.content` 추출. `data: [DONE]` 수신 시 종료.

**`unified-asset/.../api/AiConsultantController.kt`**

```kotlin
@RestController
@RequestMapping("/api/ai")
class AiConsultantController(private val svc: AiConsultantService) {

    @GetMapping("/config")
    fun getConfig(@RequestHeader("X-User-Id") userId: UUID): AiConfigResponse? =
        svc.getConfig(userId)

    @PostMapping("/config")
    fun saveConfig(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: SaveAiConfigRequest,
    ) = svc.saveConfig(userId, req)

    @DeleteMapping("/config")
    fun deleteConfig(@RequestHeader("X-User-Id") userId: UUID) =
        svc.deleteConfig(userId)

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: ChatRequest,
    ): SseEmitter = svc.chat(userId, req.messages)
}

data class ChatRequest(val messages: List<ChatMessage>)
```

---

## 프론트엔드

### 신규 파일

**`frontend/.../types/ai.ts`**

```typescript
export interface AiConfig {
  baseUrl: string
  model: string
  hasKey: boolean
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}
```

**`frontend/.../lib/ai-api.ts`**

```typescript
export function createAiApi(accessToken: string) {
  // getConfig(): Promise<AiConfig | null>
  // saveConfig(req): Promise<void>
  // deleteConfig(): Promise<void>
  // chat(messages, onToken, onDone): AbortController
  //   → fetch SSE, onToken(token) 콜백으로 스트리밍, onDone() 완료 시
}
```

`chat`은 `fetch`로 SSE 수신. `EventSource`는 POST를 지원하지 않으므로 `fetch + ReadableStream` 사용.

**`frontend/.../app/unified/settings/ai/page.tsx`**

```
← 설정   AI 상담사 설정

┌─ LLM 연결 설정 ──────────────────────────────────────────┐
│  Base URL   [https://api.openai.com/v1              ]    │
│  API Key    [sk-••••••••••••••••••••••••••••••••    ]    │
│  모델       [gpt-4o                                 ]    │
│                                        [저장] [삭제]    │
└───────────────────────────────────────────────────────────┘

예시:
  OpenAI:     https://api.openai.com/v1  /  gpt-4o
  OpenRouter: https://openrouter.ai/api/v1  /  anthropic/claude-3.5-sonnet
  Groq:       https://api.groq.com/openai/v1  /  llama-3.3-70b-versatile
  Ollama:     http://localhost:11434/v1  /  llama3.2
```

**`frontend/.../app/unified/advisor/page.tsx`**

```
← 보고서   AI 금융 상담사

(설정 없을 때)
┌─ 안내 ───────────────────────────────────────────────────┐
│  LLM API 키를 등록하면 포트폴리오 기반 상담을 받을 수 있습니다.  │
│                                    [설정하러 가기 →]        │
└───────────────────────────────────────────────────────────┘

(설정 있을 때)
┌─ 채팅창 ──────────────────────────────────────────────────┐
│  assistant: 안녕하세요! 포트폴리오를 확인했습니다.           │
│             현재 총 자산은 ... 무엇이든 물어보세요.          │
│                                                          │
│  user: 내 포트폴리오 리스크가 어떻게 되나요?                 │
│                                                          │
│  assistant: ████ (스트리밍 중...)                          │
└───────────────────────────────────────────────────────────┘
│  [메시지를 입력하세요...                    ] [전송]        │
```

초기 메시지(assistant): 페이지 로드 시 자동으로 "포트폴리오를 확인했습니다. 무엇이든 물어보세요."와 같은 안내 메시지를 보내지 않는다. 빈 채팅창으로 시작하고 사용자가 먼저 질문.

스트리밍 중 전송 버튼 비활성화. 스트리밍 완료 후 활성화.

### 수정 파일

**`frontend/.../lib/useApi.ts`**
- `useAiApi()` 훅 추가

**`frontend/.../app/unified/reports/page.tsx`**
- 상담사 카드 추가:
```typescript
{
  href: '/unified/advisor',
  title: 'AI 금융 상담사',
  desc: '포트폴리오 데이터 기반 LLM 금융 상담',
  color: 'border-green-700 hover:border-green-500',
  badge: '🤖',
}
```

---

## 변경 파일 목록

| 파일 | 작업 |
|------|------|
| `infra/postgres/init.sql` | `ua_ai_configs` 테이블 추가 |
| `unified-asset/.../entity/UserAiConfigEntity.kt` | 신규 |
| `unified-asset/.../jpa/UserAiConfigJpaRepository.kt` | 신규 |
| `unified-asset/.../usecase/AiConsultantService.kt` | 신규 |
| `unified-asset/.../api/AiConsultantController.kt` | 신규 |
| `frontend/.../types/ai.ts` | 신규 |
| `frontend/.../lib/ai-api.ts` | 신규 |
| `frontend/.../app/unified/advisor/page.tsx` | 신규 |
| `frontend/.../app/unified/settings/ai/page.tsx` | 신규 |
| `frontend/.../lib/useApi.ts` | `useAiApi()` 추가 |
| `frontend/.../app/unified/reports/page.tsx` | 상담사 카드 추가 |

---

## 범위 외

- 대화 이력 DB 저장 (세션 간 이어받기)
- API 키 AES-256 암호화 (현재 ua_accounts.api_key와 동일한 평문 저장 패턴)
- 여러 LLM 설정 저장 (현재 사용자당 1개)
- 시스템 프롬프트 커스터마이징 UI
- 사용량/비용 추적
