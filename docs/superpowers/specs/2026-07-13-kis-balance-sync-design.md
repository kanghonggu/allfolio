# KIS(한국투자증권) 잔고조회 연동 설계

- 작성일: 2026-07-13
- 상태: 설계 승인 대기
- 관련 모듈: `unified-asset`, `frontend/allfolio_app`

## 배경 / 문제

`rkdghd123@naver.com` 실계정으로 실제 브로커 보유종목 sync를 테스트하려 한다.
사용자는 KIS 실전 API 키를 발급받았고, 오늘 계좌를 만들어 종목 1개를 매수한 상태다.

현재 코드 확인 결과:

- 프론트 `/unified/accounts`가 쓰는 실제 시스템은 `unified-asset` 모듈의
  `SyncAccountUseCase` + provider별 `SyncAdapter`다.
- `AccountProvider.KIS` enum 값은 **있으나 `KisSyncAdapter`가 없다.**
  → UI에서 KIS 계좌를 등록하고 sync하면 `SyncAccountUseCase`가 "No adapter for KIS" 에러를 낸다.
- 프론트 "증권 계좌(STOCK)" 경로는 API 연동이 아니라 **증권사 이름을 고르고 거래내역을 수동
  입력**하는 용도다 (`StockSyncAdapter`는 `ua_stock_trades`를 이동평균으로 계산할 뿐 KIS API를 쓰지 않음).
- 별개로 `com.allfolio.broker.KisAdapter`(체결내역 기반)가 backend-app에 있으나, UI에 연결되지
  않았고 `broker_sync_state` 생성기도 없는 사실상 휴면 서브시스템이다. 이번 작업에서 사용하지 않는다.

따라서 KIS 실 API로 보유종목을 보려면 `unified-asset`에 신규 코드가 필요하다.

## 목표

- KIS 계좌를 UI에서 등록(앱키/앱시크릿/계좌번호, 암호화 저장)하고, sync 시 KIS 잔고조회 API로
  현재 보유종목을 자산으로 수집한다.
- Binance는 이미 작동하므로 코드 변경 없음(참고: `BinanceSyncAdapter`가 `api.binance.com` 호출).

## 비목표 (YAGNI)

- KIS 체결내역/거래내역 sync (잔고 스냅샷만).
- per-account 실전/모의 토글 (앱 레벨 설정으로 처리, 사용자는 실전).
- 연속조회 페이지네이션 (실전 1회 50건 이내면 충분 — 첫 페이지만 처리, 후속 노트).
- backend-app `broker.KisAdapter`(휴면 서브시스템) 수정.

## API 스펙 (KIS 공식문서 2026-07-13 확인 — 주식잔고조회 v1_국내주식-006)

- Endpoint: `GET /uapi/domestic-stock/v1/trading/inquire-balance`
- 실전 Domain: `https://openapi.koreainvestment.com:9443`
- 모의 Domain: `https://openapivts.koreainvestment.com:29443`
- tr_id: 실전 `TTTC8434R` / 모의 `VTTC8434R`
- 토큰 발급: `POST /oauth2/tokenP`, body `{grant_type: client_credentials, appkey, appsecret}`
  → `access_token`(TTL 24h), `expires_in`

### 요청 헤더
`authorization: Bearer {token}`, `appkey`, `appsecret`, `tr_id`, `custtype: P`(개인), `content-type: application/json`

### 요청 쿼리 파라미터
| 파라미터 | 값 | 비고 |
|---|---|---|
| CANO | 계좌번호 앞 8자리 | externalId에서 파싱 |
| ACNT_PRDT_CD | 계좌상품코드 뒤 2자리 | externalId에서 파싱 |
| AFHR_FLPR_YN | `N` | |
| OFL_YN | `` (공란) | |
| INQR_DVSN | `02` | **종목별**(01=대출일별은 종목이 여러 행으로 쪼개짐) |
| UNPR_DVSN | `01` | |
| FUND_STTL_ICLD_YN | `N` | |
| FNCG_AMT_AUTO_RDPT_YN | `N` | |
| PRCS_DVSN | `00` | 전일매매포함 |
| CTX_AREA_FK100 / NK100 | `` (공란) | 첫 페이지 |

### 응답 output1 (보유종목, 확정 필드)
| 필드 | 의미 | 매핑 |
|---|---|---|
| `pdno` | 종목코드(6자리) | Asset.symbol |
| `prdt_name` | 종목명 | Asset.name |
| `hldg_qty` | 보유수량 | Asset.quantity |
| `pchs_avg_pric` | 매입평균가 | Asset.purchasePrice |
| `pchs_amt` | 매입금액 | 집계용 |
| `prpr` | 현재가 | currentValue 폴백용 |
| `evlu_amt` | 평가금액 | Asset.currentValue |
| `evlu_pfls_amt` | 평가손익 | (참고, 미저장) |

### 공식문서에서 확인한 함정 2개
1. **같은 종목 다중 행**: 응답 예시에서 "삼성전기"가 `현금`/`자기융자` 매매구분별로 2행.
   → `INQR_DVSN=02`(종목별)로 요청하되, **코드에서도 `pdno` 기준 집계**(hldg_qty·pchs_amt·evlu_amt
   합산, 평균가=Σpchs_amt/Σhldg_qty 재계산)로 종목당 1자산 보장.
2. **`prpr`·`evlu_amt`가 0으로 올 수 있음**: 응답 예시에서 실제 `prpr:"0", evlu_amt:"0"`
   (장 시간외/시세 미반영). 오늘 산 종목을 장 마감 후 조회하면 0일 수 있음.
   → 폴백: `currentValue = evlu_amt>0 ? evlu_amt : (prpr>0 ? prpr : pchs_avg_pric) × hldg_qty`,
   `valuationMethod = prpr>0 ? MARKET_PRICE : USER_INPUT`.

### 기타 제약
- 실전 1회 최대 50건 / 모의 20건, 초과 시 `ctx_area_fk100` 커서 연속조회 (v1 미구현).
- 이 TR은 초당 120 TPS 제한. 초과 시 `EGW00215` — v1은 에러 표면화(재시도 미구현).
- 토큰 발급은 앱키당 1분 1회 제한 → 인메모리 캐시 필수.

## 설계

### 1. 백엔드 — `KisSyncAdapter` (신규)

위치: `unified-asset/.../infrastructure/adapter/KisSyncAdapter.kt`
패턴: `BinanceSyncAdapter`와 동일 구조 (`SyncAdapter` 구현, 자체 WebClient).

```
class KisSyncAdapter(props, objectMapper) : SyncAdapter {
  override val supportedProvider = AccountProvider.KIS

  override fun sync(account): List<Asset> {
    require apiKey/apiSecret/externalId present  → 없으면 로그+emptyList
    (cano, prdt) = parseExternalId(account.externalId)   // "50123456_01" → ("50123456","01")
    token = resolveToken(account.apiKey, account.apiSecret)   // 캐시 우선
    resp  = fetchBalance(token, account.apiKey, account.apiSecret, cano, prdt)
    if resp.rtCd != "0" → throw(resp.msg1)                // UseCase가 ERROR 처리
    resp.output1
      .groupBy { it.pdno }
      .map { (pdno, rows) -> aggregate → Asset.create(...) }
  }

  override fun testConnection(account): ConnectionTestResult {
    token 발급 성공 + fetchBalance rtCd=0 → (true, "연결 성공! N개 종목")
    실패 → (false, 메시지)
  }
}
```

- **토큰 캐시**: `ConcurrentHashMap<appkey, Pair<token, expiryEpochMs>>`, TTL = `expires_in - 120s`.
  동시성은 단순 read-then-issue (경합 시 중복 발급 허용 — 무해). 앱 재시작 시 재발급.
- **Asset 매핑**: type=STOCK, sourceType=STOCK_API, currency="KRW", category=FINANCIAL.
- **설정** `KisSyncProperties`(`@ConfigurationProperties("kis-sync")`):
  `mock: Boolean=false`, `realBaseUrl`, `mockBaseUrl` (실전 기본값). env: `KIS_SYNC_MOCK`.
  base-url·tr_id를 mock 값으로 분기.
- **DTO**(unified-asset 내 신규): `KisTokenResponse`(access_token, expires_in),
  `KisBalanceResponse`(rt_cd, msg1, output1), `KisBalanceItem`(위 8개 필드).
  backend-app의 `broker.kis.*` DTO는 모듈 경계상 재사용하지 않고 최소 재정의.

### 2. 프론트 — KIS API 연동 폼

`frontend/allfolio_app/app/unified/accounts/new/page.tsx`:

- 카테고리에 **"증권사 API 연동"**(가칭) 추가, provider=`KIS`.
  (기존 "증권 계좌(STOCK, 수동입력)" 카테고리는 그대로 유지)
- 입력 필드: 별칭, **앱키**(password), **앱시크릿**(password),
  **계좌번호 한 칸**(예: `50123456-01`, 계좌번호 체계 8-2), 연결테스트 버튼.
- 계좌번호 파싱: 하이픈/공백 제거 후 앞 8자리=CANO, 뒤 2자리=상품코드로 분리하여
  `externalId="{CANO}_{상품코드}"`로 저장. (파싱은 제출 시 프론트에서 수행; 형식 미검증 시
  백엔드 `parseExternalId`가 `_` 없으면 상품코드 기본 `01` 폴백)
- 제출 payload: `provider=KIS`, `accountType=STOCK`, `currency=KRW`,
  `apiKey`, `apiSecret`, `externalId="{CANO}_{상품코드}"`.
- 거래소 폼처럼 **연결테스트 성공 후에만 계좌 추가** 가능하도록 게이팅.
- `types/unified.ts`에 `KIS`가 `AccountProvider`에 포함돼 있는지 확인(없으면 추가).

### 3. 데이터 흐름

계좌 등록(apiKey/apiSecret 암호화 저장) → `POST /api/unified/accounts/{id}/sync`
→ `SyncAccountUseCase.execute` → `KisSyncAdapter.sync()` → 토큰 발급/캐시 + 잔고조회
→ pdno 집계 → `Asset` 목록 → 기존 자산 full-refresh 교체 → NAV 스냅샷 기록
→ 프론트 자산목록에 오늘 산 종목 표시.

### 4. 에러 처리
- 자격증명 누락 → 로그 + `emptyList()` (Binance와 동일).
- `rt_cd != "0"` → 예외 → `SyncAccountUseCase`가 계좌 상태 ERROR + msg1 반환.
- 토큰 발급 실패(잘못된 키) → 예외 → ERROR. testConnection에서 사전 검출.
- 네트워크/타임아웃 → 예외 → ERROR.

## 테스트 계획

- **단위 테스트**(unified-asset): KIS 잔고 응답 JSON(공식 예시 기반, prpr=0 케이스 및 종목
  다중 행 케이스 포함)을 목킹 → `KisSyncAdapter.sync()`가 pdno 집계·폴백을 정확히 수행해
  올바른 `Asset`을 만드는지 검증. WebClient는 MockWebServer 또는 클라이언트 추상화로 목킹.
- **실측 검증**: Render 배포 후 `rkdghd123@naver.com`으로 KIS 계좌 등록(실 앱키/시크릿/계좌번호)
  → 연결테스트 → sync → 오늘 산 종목이 자산으로 뜨는지 확인.
  (앱키/시크릿 실제 값은 사용자가 직접 입력, 대화에 노출 금지.)

## 배포 노트
- 앱 레벨 env는 기본 실전이라 별도 설정 불필요(`KIS_SYNC_MOCK` 미설정 = 실전).
- `Build backend JAR` GitHub Actions 체크 pass 확인 후 머지.

## 미해결/후속
- 연속조회 페이지네이션(50건 초과 계좌).
- `EGW00215` rate-limit 재시도.
- per-account 실전/모의 토글.
- KIWOOM 동일 패턴 확장(별도 스펙).
