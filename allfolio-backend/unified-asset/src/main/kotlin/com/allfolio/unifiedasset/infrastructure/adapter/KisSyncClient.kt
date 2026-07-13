package com.allfolio.unifiedasset.infrastructure.adapter

interface KisSyncClient {
    /** appkey/appsecret(client_credentials)로 access_token 발급. 실패 시 예외. */
    fun issueToken(appKey: String, appSecret: String): String

    /** 주식잔고조회(TTTC8434R). 첫 페이지만 조회. */
    fun fetchBalance(appKey: String, appSecret: String, cano: String, acntPrdtCd: String): KisBalanceResponse
}
