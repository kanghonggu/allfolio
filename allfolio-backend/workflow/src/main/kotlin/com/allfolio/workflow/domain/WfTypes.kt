package com.allfolio.workflow.domain

/** 하위단계 실행 유형 — CHAIN(동기 연쇄)/POLL(외부 상태 폴링)/MANUAL(수동확인). */
enum class WfActionType { CHAIN, POLL, MANUAL }

/** 실행 로그 상태 (기능명세서 5.3 — N/R/S/E/P 대응). */
enum class WfJobStatus { PENDING, RUNNING, SUCCESS, ERROR, PAUSED }

/** 단계 롤업 상태 (기능명세서 5.3 — 우선순위 순 판정). */
enum class WfStepRollup { STANDBY, FINISH, ERROR, RUNNING, PAUSED }

/** 단계 주기 — D(일)/M(월)/Q(분기). */
enum class WfTermGb { D, M, Q }
