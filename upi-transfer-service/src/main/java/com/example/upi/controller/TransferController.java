package com.example.upi.controller;

import com.example.upi.dto.BalanceResponse;
import com.example.upi.dto.TransferRequest;
import com.example.upi.dto.TransferResponse;
import com.example.upi.model.Account;
import com.example.upi.model.Transaction;
import com.example.upi.service.ITransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TransferController {

    private final ITransferService transferService;

    public TransferController(ITransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.transfer(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/balance/{upiId}")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String upiId) {
        Account account = transferService.getAccount(upiId);
        return ResponseEntity.ok(new BalanceResponse(account.getUpiId(), account.getHolderName(), account.getBalance()));
    }

    @GetMapping("/transactions/{upiId}")
    public ResponseEntity<List<Transaction>> getTransactions(@PathVariable String upiId) {
        return ResponseEntity.ok(transferService.getTransactions(upiId));
    }
}
