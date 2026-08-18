-- A1 실물자산 평가 (G2) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-18-real-asset-tables.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.
--
-- 설계 문서: 노션 "ALLFOLIO 실물자산 평가 설계 — 2026-08-16" 4절.
--
-- **공시(D1) 트랙과 순서 의존이 없다.** 설계 문서 초안은 `V1__baseline → V2__dart_tables →
-- V3__real_asset_tables` 번호 순서를 박고 "공시가 먼저"라고 했지만, 그 전제였던 Flyway
-- 도입(AF-118)이 2026-08-18 보류로 D1 범위에서 빠졌다. 이 저장소는 번호가 아니라 날짜
-- 접두사를 쓰므로 충돌할 번호 자체가 없다. 두 트랙은 서로를 기다리지 않는다.
--
--
-- ## 문서와 다르게 쓴 것 셋 — 전부 저장소 실물에 맞춘 것이다
--
-- 1) **id·user_id가 BIGSERIAL/BIGINT가 아니라 UUID다.** 설계 문서는 BIGINT로 적혀 있지만
--    이 저장소의 사용자 소유 테이블은 전부 UUID다(`users.id` · `ua_assets.user_id` ·
--    `cash_flow.user_id` · `ua_accounts.user_id`). BIGINT로 만들면 조인이 아예 성립하지
--    않고, 그 사실이 G6 등록 API를 쓸 때까지 드러나지 않는다. 문서 쪽을 고칠 것.
--    id에 DB DEFAULT를 두지 않는 것도 관례다 — 앱이 `UUID.randomUUID()`로 만든다
--    (`AccountEntity` · `AssetEntity` · `ExclusionListEntity`가 전부 그렇다).
--
-- 2) **`price_unit`을 추가했다** (문서에 없는 컬럼). AF-108은 단위를 코드 상수가 아니라
--    `market_commodity_quote.unit` 행에 저장한다 — "소스가 단위를 바꾼 날 저장은 멀쩡한데
--    화면만 조용히 틀린다"를 막으려는 설계다. 평가 스냅샷이 그 단위를 안 남기면 방어가
--    스냅샷 경계에서 끊긴다: 나중에 `unit_price`만 보고는 그게 KRW/g였는지 KRW/돈이었는지
--    알 수 없어 과거 화면을 재현하지 못한다. 문서가 `price_basis`를 미리 넣은 근거
--    ("나중에 추가하는 것보다 지금 넣는 게 싸다")와 같은 논리다.
--
-- 3) **`valued_on`에 인덱스를 하나 더 두지 않았다.** 문서의
--    `idx_valuation_asset (real_asset_id, valued_on DESC)`이 "자산별 최신 스냅샷"과
--    "자산별 구간 조회"를 둘 다 받는다. 전 사용자 배치(G5)가 `valued_on` 단독으로 훑는
--    쿼리를 쓰게 되면 그때 추가할 것 — 지금 만들면 쓰지도 않는 인덱스를 매 INSERT마다
--    갱신하게 된다. (`uq_valuation`이 그 조합의 유일성은 이미 진다.)
--
--
-- ## 금 시세 표는 여기서 만들지 않는다
--
-- `market_commodity_quote`(code='GOLD_KRX')는 AF-108에서 이미 만들어져 운영에 적용돼 있다.
-- 설계 문서 초안이 말하는 `krx_gold_price`는 **존재하지 않는 표다** — 전용 표를 만들려던
-- 계획이 AF-108에서 원자재 17종을 한 표로 묶으면서 흡수됐다. 폴백 조회(G4)도 기존
-- `uk_market_commodity_quote (code, trade_date)` 인덱스로 충분해 인덱스 추가가 필요 없다.

-- ── 1) 사용자 보유 실물자산 ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS real_asset (
    id                UUID          NOT NULL,
    user_id           UUID          NOT NULL,
    asset_type        VARCHAR(20)   NOT NULL,   -- GOLD | WATCH | REAL_ESTATE
    sub_type          VARCHAR(30),              -- KRX_ACCOUNT | BAR | JEWELRY
    name              VARCHAR(200)  NOT NULL,   -- 사용자 지정 명칭
    -- 시세 조인 키. 자산 유형이 늘어도 스키마를 안 건드리려고 문자열 하나로 둔다.
    -- 금=시세 코드('GOLD_KRX') · 시계=ref · 부동산=단지코드+전용면적.
    -- nullable인 이유는 시세 소스가 없는 자산(감정가 수동 입력 등)을 나중에 받을 수 있기
    -- 때문이다 — 그때 어댑터가 null을 보고 "평가 불가"로 판정한다(설계 1절 원칙 3).
    source_ref        VARCHAR(100),
    -- **NUMERIC이어야 한다. INT면 안 된다** — 금은 g 단위 소수가 필수다(3.75g = 1돈).
    quantity          NUMERIC(18,4) NOT NULL,
    -- 순도. v1은 24K 고정이라 항상 1.0이지만, 18K(0.75)를 받는 날 코드를 안 고치려고 넣는다.
    purity            NUMERIC(5,4)  NOT NULL DEFAULT 1.0,
    acquired_at       DATE          NOT NULL,
    -- 취득가는 원 단위 정수다. 과거 대시보드가 KRW를 소수로 들고 있다가 자릿수를 흘린
    -- 사고가 있었다(AF-104). 원화에 소수점을 두지 않는다.
    acquired_cost_krw BIGINT        NOT NULL,
    -- **기본값이 FALSE인 것은 의도다.** 설계 문서는 "금 true / 시계·부동산 false"라고
    -- 하지만, 그 판단은 등록 API(G6)가 자산 유형을 보고 명시적으로 넣어야 한다.
    -- 기본값을 TRUE로 두면 유형이 하나 늘 때마다 그 자산이 조용히 TWR에 섞인다 —
    -- 틀린 방향이 다르다: 빠뜨린 자산은 수익률을 과소 표시할 뿐이지만, 잘못 섞인
    -- 계단식 자산은 TWR 자체를 오염시킨다(설계 1절 원칙 5).
    include_in_twr    BOOLEAN       NOT NULL DEFAULT FALSE,
    -- 삭제 대신 비활성. 평가 스냅샷이 이 행을 참조하므로 물리 삭제하면 과거가 끊긴다.
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_real_asset PRIMARY KEY (id)
);

-- 부분 인덱스다. 조회는 항상 "이 사용자의 살아 있는 자산"이라 비활성 행을 인덱스에
-- 담을 이유가 없다. WHERE 절을 쓰는 쿼리만 이 인덱스를 타므로, 조회 코드에서
-- `is_active` 조건을 빼면 인덱스가 조용히 안 쓰인다 — G7 작성 시 주의할 것.
CREATE INDEX IF NOT EXISTS idx_real_asset_user
    ON real_asset (user_id) WHERE is_active;

COMMENT ON TABLE  real_asset                IS '사용자 보유 실물자산 (금·시계·부동산) — A1';
COMMENT ON COLUMN real_asset.source_ref     IS '시세 조인 키. 금=GOLD_KRX · 시계=ref · 부동산=단지코드+면적';
COMMENT ON COLUMN real_asset.quantity       IS '금은 g. 3.75g = 1돈이라 소수 필수';
COMMENT ON COLUMN real_asset.include_in_twr IS '금 true / 시계·부동산 false. 기본 FALSE는 의도 — 등록 API가 명시적으로 넣는다';

-- ── 2) 평가 스냅샷 ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS real_asset_valuation (
    id             UUID          NOT NULL,
    real_asset_id  UUID          NOT NULL,
    -- 평가 기준일 = 배치 실행일(KST). **러너의 UTC 시계를 싣지 않는다** — 서버가 KST로
    -- 정한다. 이 저장소는 UTC 9시간 밀림으로 스냅샷 날짜가 하루 어긋난 적이 있다.
    valued_on      DATE          NOT NULL,
    -- 적용 시세. market_commodity_quote.price와 같은 정밀도로 맞춘다.
    unit_price     NUMERIC(18,4) NOT NULL,
    -- 적용 시세의 단위. 문서에 없는 컬럼 — 파일 상단 "문서와 다르게 쓴 것" 2번 참조.
    price_unit     VARCHAR(20)   NOT NULL,   -- KRW/g 등. market_commodity_quote.unit을 그대로 옮긴다
    -- 시세 x 수량 x 순도. 원 단위 정수 — acquired_cost_krw와 같은 이유.
    valuation_krw  BIGINT        NOT NULL,
    -- **실제 시세 기준일. valued_on과 다른 게 정상이다.**
    -- 스냅샷에 박아두어야 과거 화면을 재현할 수 있다.
    price_as_of    DATE          NOT NULL,
    -- valued_on - price_as_of. 2026-08-18 실측 분포(평가일 78일 기준):
    --   1일 68% · 2일 15% · 3일 14% · 4일 3%, 최대 4.
    -- **0이 정상이 아니다** — 공공데이터포털 금 시세는 D+1 공표라 평일에도 최소 1이다.
    -- 임계치를 1~4로 잡으면 매일/자주 경보가 울린다. 5 이상부터가 "이상한 상황"이다.
    staleness_days SMALLINT      NOT NULL DEFAULT 0,
    -- 체결가(TRADE)와 호가(ASK)는 다른 숫자다. 지금은 전부 TRADE지만 시계(ASK)가 오면
    -- 섞인다 — 실물자산은 스프레드가 커서 이 둘을 섞으면 손익이 왜곡된다.
    price_basis    VARCHAR(10)   NOT NULL,   -- TRADE | ASK
    confidence     VARCHAR(10),              -- HIGH | MEDIUM | LOW
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_real_asset_valuation PRIMARY KEY (id),
    -- 배치가 같은 날 두 번 돌아도 행이 둘 생기지 않는다. G5의 재시도는 이 제약에
    -- 기대어 멱등해진다 — 원자재 수집이 겹쳐 돌다 유니크 제약에 걸려 배치 전체를
    -- 날린 전례가 있으니, G5는 INSERT가 아니라 upsert(ON CONFLICT)로 쓸 것.
    CONSTRAINT uq_valuation UNIQUE (real_asset_id, valued_on),
    -- 자산이 지워지면 그 자산의 스냅샷도 의미가 없다. 다만 real_asset은 is_active로
    -- 비활성만 하고 물리 삭제를 안 하는 것이 원칙이라, 이 CASCADE는 실수로 지운
    -- 경우의 정리용이지 평상시 경로가 아니다.
    CONSTRAINT fk_valuation_asset FOREIGN KEY (real_asset_id)
        REFERENCES real_asset(id) ON DELETE CASCADE
);

-- 자산별 최신 스냅샷(ORDER BY valued_on DESC LIMIT 1)과 구간 조회를 둘 다 받는다.
CREATE INDEX IF NOT EXISTS idx_valuation_asset
    ON real_asset_valuation (real_asset_id, valued_on DESC);

COMMENT ON TABLE  real_asset_valuation                IS '실물자산 일별 평가 스냅샷 (휴장일 포함 매일) — A1';
COMMENT ON COLUMN real_asset_valuation.price_as_of    IS '실제 시세 기준일. valued_on과 다른 게 정상 (금은 D+1 공표)';
COMMENT ON COLUMN real_asset_valuation.staleness_days IS 'valued_on - price_as_of. 평일에도 최소 1. 임계치는 5 이상으로 잡을 것';
COMMENT ON COLUMN real_asset_valuation.price_unit     IS '적용 시세의 단위(market_commodity_quote.unit 사본). 단위 변경을 스냅샷 경계 너머로 추적하기 위한 것';

-- ── 검증 ────────────────────────────────────────────────────────────────────
-- 두 표가 만들어졌고 비어 있는지 확인한다. 재실행하면 그대로 0/0이 나온다(멱등).
SELECT
    (SELECT count(*) FROM real_asset)           AS real_asset_rows,
    (SELECT count(*) FROM real_asset_valuation) AS valuation_rows;
