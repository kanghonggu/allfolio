package com.allfolio.unifiedasset.domain.common

/**
 * 계좌번호 마스킹 (QA P2) — externalId는 기관명("삼성증권")과 계좌번호("44855393_01")가
 * 혼재 저장되므로, 숫자 6자리 이상이면 계좌번호로 보고 목록/카드 응답에서 마스킹한다.
 */
fun isAccountNumberLike(externalId: String?): Boolean =
    externalId != null && externalId.count { it.isDigit() } >= 6

/** 앞 4자리 유지, 마지막 '_' 이후 접미(상품코드)는 유지, 나머지 숫자는 마스킹. */
fun maskAccountNumber(externalId: String): String {
    val sep = externalId.lastIndexOf('_')
    val body = if (sep >= 0) externalId.substring(0, sep) else externalId
    val suffix = if (sep >= 0) externalId.substring(sep) else ""
    val maskedBody = body.take(4) + body.drop(4).map { if (it.isDigit()) '*' else it }.joinToString("")
    return maskedBody + suffix
}
