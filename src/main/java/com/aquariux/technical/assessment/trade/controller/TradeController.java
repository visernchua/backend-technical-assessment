package com.aquariux.technical.assessment.trade.controller;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trades")
@Tag(name = "Trade", description = "Trading operations")
@RequiredArgsConstructor
public class TradeController {
    private final TradeServiceInterface tradeService;

    @PostMapping(value = "/execute", produces = "application/json", consumes = "application/json")
    @Operation(
            summary = "Execute an internal market trade",
            description =
                    "Quantity is in base asset units. Retry with the same Idempotency-Key after"
                            + " timeouts.")
    public ResponseEntity<TradeResponse> executeTrade(
            @Valid @RequestBody TradeRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        // REASON: The service proxy commits before returning; never acknowledge uncommitted
        // settlement.
        return ResponseEntity.ok(tradeService.executeTrade(request, key));
    }
}
