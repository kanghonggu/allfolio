package com.allfolio.api.admin

import com.allfolio.fx.FxRateService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/admin/fx")
class FxRateAdminController(
    private val fxRateService: FxRateService,
) {
    /** GET /api/admin/fx/usdtkrw — 현재 환율 조회 */
    @GetMapping("/usdtkrw")
    fun getUsdtKrw(): ResponseEntity<FxRateResponse> =
        ResponseEntity.ok(FxRateResponse(fxRateService.getUsdtToKrw()))

    /** PUT /api/admin/fx/usdtkrw — 환율 갱신 (어드민 전용) */
    @PutMapping("/usdtkrw")
    fun setUsdtKrw(@RequestBody req: FxRateRequest): ResponseEntity<FxRateResponse> {
        fxRateService.setUsdtToKrw(req.rate)
        return ResponseEntity.ok(FxRateResponse(req.rate))
    }
}

data class FxRateRequest(val rate: BigDecimal)
data class FxRateResponse(val usdtKrw: BigDecimal)
