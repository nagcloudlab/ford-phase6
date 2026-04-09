package com.example.upi.controller;

import com.example.upi.dto.BalanceResponse;
import com.example.upi.dto.TransferRequest;
import com.example.upi.dto.TransferResponse;
import com.example.upi.model.Account;
import com.example.upi.model.Transaction;
import com.example.upi.service.AccountCacheService;
import com.example.upi.service.ITransferService;
import com.example.upi.service.RateLimiterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransferController {

    private final ITransferService transferService;
    private final AccountCacheService cacheService;
    private final RateLimiterService rateLimiter;

    public TransferController(ITransferService transferService,
                              AccountCacheService cacheService,
                              RateLimiterService rateLimiter) {
        this.transferService = transferService;
        this.cacheService = cacheService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.transfer(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/balance/{upiId}")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String upiId) {
        // Check cache first — if hit, return with cached=true flag
        var cached = cacheService.getFromCache(upiId);
        if (cached.isPresent()) {
            Account a = cached.get();
            return ResponseEntity.ok(new BalanceResponse(a.getUpiId(), a.getHolderName(), a.getBalance(), true));
        }
        Account account = transferService.getAccount(upiId);
        return ResponseEntity.ok(new BalanceResponse(account.getUpiId(), account.getHolderName(), account.getBalance()));
    }

    @GetMapping("/transactions/{upiId}")
    public ResponseEntity<List<Transaction>> getTransactions(@PathVariable String upiId) {
        return ResponseEntity.ok(transferService.getTransactions(upiId));
    }

    // ── Redis-specific endpoints for demo/teaching ──

    @GetMapping("/rate-limit/{upiId}")
    public ResponseEntity<Map<String, Object>> getRateLimit(@PathVariable String upiId) {
        int remaining = rateLimiter.getRemainingTransfers(upiId);
        return ResponseEntity.ok(Map.of(
                "upiId", upiId,
                "remainingTransfers", remaining,
                "maxPerWindow", 10,
                "windowSeconds", 60
        ));
    }

    @DeleteMapping("/cache/{upiId}")
    public ResponseEntity<Map<String, String>> evictCache(@PathVariable String upiId) {
        cacheService.evictFromCache(upiId);
        return ResponseEntity.ok(Map.of("message", "Cache evicted for " + upiId));
    }
}
