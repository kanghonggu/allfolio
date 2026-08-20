-- S12 로컬 검증용 시드 — **운영에 실행하지 말 것**
-- 2026-08-11~08-18 OpenDART 실측 응답에서 뽑은 행이다. 값을 지어내지 않았다.
-- 대상: livetest@allfolio.dev (003a88d4-06c1-4bea-bedd-3c62581c9e0f)
--
-- 종목을 이 넷으로 고른 이유 — 화면이 확인해야 할 것을 전부 덮는다:
--   한국유니온제약 T1·T3·T5 + 정정 그룹   코오롱 T1·T2·T5 + 정정 그룹
--   키스트론 T4가 8건 — **임원별 분리(flrNm 묶기 키)를 직접 검증한다**
--   삼성전자 T4 + elestock 소유변동 데이터
-- 공시 29건: T1 7건 · T2 1건 · T3 1건 · T4 16건 · T5 4건
-- 되돌리기: 파일 끝 정리 블록

BEGIN;

-- 1) 보유종목 — livetest는 원래 카카오 하나뿐이라 Tier 다양성을 못 본다
INSERT INTO ua_assets (id, user_id, account_id, category, type, source_type, name, symbol,
    quantity, purchase_price, current_value, currency, valuation_method, confidence_level,
    last_updated_at, created_at, liquidity_type)
SELECT gen_random_uuid(), '003a88d4-06c1-4bea-bedd-3c62581c9e0f', 'aa000000-0000-0000-0000-0000000000a2', 'FINANCIAL', 'STOCK', 'STOCK_API', '한국유니온제약', '080720',
    120, 7800, 936000, 'KRW', 'MARKET_PRICE', 'HIGH', now(), now(), 'LIQUID'
WHERE NOT EXISTS (SELECT 1 FROM ua_assets WHERE user_id='003a88d4-06c1-4bea-bedd-3c62581c9e0f' AND symbol='080720');
INSERT INTO ua_assets (id, user_id, account_id, category, type, source_type, name, symbol,
    quantity, purchase_price, current_value, currency, valuation_method, confidence_level,
    last_updated_at, created_at, liquidity_type)
SELECT gen_random_uuid(), '003a88d4-06c1-4bea-bedd-3c62581c9e0f', 'aa000000-0000-0000-0000-0000000000a2', 'FINANCIAL', 'STOCK', 'STOCK_API', '코오롱', '002020',
    40, 18500, 740000, 'KRW', 'MARKET_PRICE', 'HIGH', now(), now(), 'LIQUID'
WHERE NOT EXISTS (SELECT 1 FROM ua_assets WHERE user_id='003a88d4-06c1-4bea-bedd-3c62581c9e0f' AND symbol='002020');
INSERT INTO ua_assets (id, user_id, account_id, category, type, source_type, name, symbol,
    quantity, purchase_price, current_value, currency, valuation_method, confidence_level,
    last_updated_at, created_at, liquidity_type)
SELECT gen_random_uuid(), '003a88d4-06c1-4bea-bedd-3c62581c9e0f', 'aa000000-0000-0000-0000-0000000000a2', 'FINANCIAL', 'STOCK', 'STOCK_API', '키스트론', '475430',
    300, 4200, 1260000, 'KRW', 'MARKET_PRICE', 'HIGH', now(), now(), 'LIQUID'
WHERE NOT EXISTS (SELECT 1 FROM ua_assets WHERE user_id='003a88d4-06c1-4bea-bedd-3c62581c9e0f' AND symbol='475430');
INSERT INTO ua_assets (id, user_id, account_id, category, type, source_type, name, symbol,
    quantity, purchase_price, current_value, currency, valuation_method, confidence_level,
    last_updated_at, created_at, liquidity_type)
SELECT gen_random_uuid(), '003a88d4-06c1-4bea-bedd-3c62581c9e0f', 'aa000000-0000-0000-0000-0000000000a2', 'FINANCIAL', 'STOCK', 'STOCK_API', '삼성전자', '005930',
    60, 72000, 4320000, 'KRW', 'MARKET_PRICE', 'HIGH', now(), now(), 'LIQUID'
WHERE NOT EXISTS (SELECT 1 FROM ua_assets WHERE user_id='003a88d4-06c1-4bea-bedd-3c62581c9e0f' AND symbol='005930');

-- 2) 공시 29건
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811800906', '00152862', '코오롱', '002020', 'Y',
    '단일판매ㆍ공급계약체결(자회사의 주요경영사항)', '단일판매·공급계약체결(자회사의 주요경영사항)', '2026-08-11', '코오롱', '유', true, 1, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811800822', '00152862', '코오롱', '002020', 'Y',
    '현금ㆍ현물배당결정(자회사의 주요경영사항)', '현금·현물배당결정(자회사의 주요경영사항)', '2026-08-11', '코오롱', '유', true, 2, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000616', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-11', '홍희연', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000609', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-11', '키스와이어홀딩스', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000595', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-11', '키스트론홀딩스', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000587', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-11', '홍석표', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000550', '00532855', '한국유니온제약', '080720', 'K',
    '[기재정정]주요사항보고서(유상증자결정)', '주요사항보고서(유상증자결정)', '2026-08-11', '한국유니온제약', NULL, true, 1, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000548', '00532855', '한국유니온제약', '080720', 'K',
    '[기재정정]주요사항보고서(감자결정)', '주요사항보고서(감자결정)', '2026-08-11', '한국유니온제약', NULL, true, 1, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000546', '00532855', '한국유니온제약', '080720', 'K',
    '[기재정정]주요사항보고서(유상증자결정)', '주요사항보고서(유상증자결정)', '2026-08-11', '한국유니온제약', NULL, true, 1, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000580', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-11', '홍덕산업', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811900646', '00204323', '키스트론', '475430', 'K',
    '최대주주변경', '최대주주변경', '2026-08-11', '키스트론', '코', true, 1, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000285', '00126380', '삼성전자', '005930', 'Y',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-11', '여명구', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260811000011', '00126380', '삼성전자', '005930', 'Y',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-11', '손영수', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814802663', '00152862', '코오롱', '002020', 'Y',
    '[기재정정]단일판매ㆍ공급계약체결(자회사의 주요경영사항)', '단일판매·공급계약체결(자회사의 주요경영사항)', '2026-08-14', '코오롱', '유', true, 1, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003214', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '신재명', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003161', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '신가윤', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003115', '00204323', '키스트론', '475430', 'K',
    '임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '홍영철', NULL, true, 4, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814002248', '00204323', '키스트론', '475430', 'K',
    '반기보고서 (2026.06)', '반기보고서 (2026.06)', '2026-08-14', '키스트론', NULL, true, 5, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814900829', '00532855', '한국유니온제약', '080720', 'K',
    '반기검토(감사)의견부적정등사실확인(자본잠식률100분의50이상또는자기자본10억원미만포함)', '반기검토(감사)의견부적정등사실확인(자본잠식률100분의50이상또는자기자본10억원미만포함)', '2026-08-14', '한국유니온제약', '코', true, 3, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814001219', '00532855', '한국유니온제약', '080720', 'K',
    '반기보고서 (2026.06)', '반기보고서 (2026.06)', '2026-08-14', '한국유니온제약', NULL, true, 5, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003922', '00152862', '코오롱', '002020', 'Y',
    '반기보고서 (2026.06)', '반기보고서 (2026.06)', '2026-08-14', '코오롱', NULL, true, 5, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003973', '00126380', '삼성전자', '005930', 'Y',
    '[기재정정]임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '박태훈', NULL, true, 4, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003950', '00126380', '삼성전자', '005930', 'Y',
    '[기재정정]임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '박태훈', NULL, true, 4, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003912', '00126380', '삼성전자', '005930', 'Y',
    '[기재정정]임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '박태훈', NULL, true, 4, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003844', '00126380', '삼성전자', '005930', 'Y',
    '[기재정정]임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '박태훈', '정', true, 4, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003699', '00126380', '삼성전자', '005930', 'Y',
    '반기보고서 (2026.06)', '반기보고서 (2026.06)', '2026-08-14', '삼성전자', NULL, true, 5, false, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003747', '00126380', '삼성전자', '005930', 'Y',
    '[기재정정]임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '박태훈', NULL, true, 4, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260814003672', '00126380', '삼성전자', '005930', 'Y',
    '[기재정정]임원ㆍ주요주주특정증권등소유상황보고서', '임원·주요주주특정증권등소유상황보고서', '2026-08-14', '박태훈', '정', true, 4, true, now())
ON CONFLICT (rcept_no) DO NOTHING;
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm, is_material, material_tier, is_correction, collected_at)
VALUES ('20260818800175', '00152862', '코오롱', '002020', 'Y',
    '단일판매ㆍ공급계약체결(자회사의 주요경영사항)', '단일판매·공급계약체결(자회사의 주요경영사항)', '2026-08-18', '코오롱', '유', true, 1, false, now())
ON CONFLICT (rcept_no) DO NOTHING;

-- 3) 소유변동 14건 — 위 T4 공시의 rcept_no와 매칭되는 elestock 실측 행만
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260811000011', '00126380', '005930', '손영수', '부사장',
    false, NULL, '2026-08-11', 5349, -700,
    0.00, 0.00, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260811000285', '00126380', '005930', '여명구', '부사장',
    false, NULL, '2026-08-11', 8805, -1000,
    0.00, 0.00, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260814003747', '00126380', '005930', '박태훈', '상무',
    false, NULL, '2026-08-14', 2912, 34,
    0.00, 0.00, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260814003950', '00126380', '005930', '박태훈', '상무',
    false, NULL, '2026-08-14', 2878, 614,
    0.00, 0.00, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260814003973', '00126380', '005930', '박태훈', '상무',
    false, NULL, '2026-08-14', 3501, 589,
    0.00, 0.00, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260814003912', '00126380', '005930', '박태훈', '상무',
    false, NULL, '2026-08-14', 3304, -197,
    0.00, 0.00, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260811000580', '00204323', '475430', '홍덕산업', NULL,
    NULL, '사실상지배주주', '2026-08-11', 0, -1066625,
    0, -5.98, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260811000587', '00204323', '475430', '홍석표', NULL,
    NULL, '10%이상주주', '2026-08-11', 1784811, -455784,
    10.00, -2.55, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260811000616', '00204323', '475430', '홍희연', NULL,
    NULL, '10%이상주주', '2026-08-11', 3082786, 940520,
    17.27, 5.27, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260811000609', '00204323', '475430', '키스와이어홀딩스', NULL,
    NULL, '사실상지배주주', '2026-08-11', 0, -940520,
    0, -5.27, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260811000595', '00204323', '475430', '키스트론홀딩스', NULL,
    NULL, '10%이상주주', '2026-08-11', 3549289, 1522409,
    19.89, 8.53, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260814003115', '00204323', '475430', '홍영철', NULL,
    NULL, '10%이상주주', '2026-08-14', 1784811, -841953,
    10.00, -4.72, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260814003161', '00204323', '475430', '신가윤', NULL,
    NULL, '사실상지배주주', '2026-08-14', 165232, 165232,
    0.93, 0.93, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;
INSERT INTO dart_insider_trade (rcept_no, corp_code, stock_code, repror, officer_position,
    is_registered, major_holder_type, report_date, owned_qty, change_qty, owned_rate, change_rate, collected_at)
VALUES ('20260814003214', '00204323', '475430', '신재명', '대표이사',
    true, '사실상지배주주', '2026-08-14', 1065081, 1065081,
    5.97, 5.97, now())
ON CONFLICT (rcept_no, repror) DO NOTHING;

COMMIT;

-- 확인
-- SELECT material_tier, count(*) FROM dart_disclosure GROUP BY 1 ORDER BY 1;
-- SELECT stock_code, count(*) FROM dart_insider_trade GROUP BY 1;

-- ── 되돌리기 ────────────────────────────────────────────────
-- BEGIN;
--   DELETE FROM dart_insider_trade;
--   DELETE FROM dart_disclosure;
--   DELETE FROM ua_assets WHERE user_id='003a88d4-06c1-4bea-bedd-3c62581c9e0f' AND symbol IN ('080720','002020','475430','005930');
-- COMMIT;
