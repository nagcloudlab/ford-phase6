package com.example.upi.controller;

import com.example.upi.model.Account;
import com.example.upi.model.Transaction;
import com.example.upi.service.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final TransferService transferService;

    public ApiController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/balance/{upiId}")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String upiId) {
        Account account = transferService.getAccount(upiId);
        return ResponseEntity.ok(Map.of(
                "upiId", account.getUpiId(),
                "holderName", account.getHolderName(),
                "balance", account.getBalance()
        ));
    }

    @GetMapping("/transactions/{upiId}")
    public ResponseEntity<List<Transaction>> getTransactions(@PathVariable String upiId) {
        return ResponseEntity.ok(transferService.getTransactions(upiId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleError(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
