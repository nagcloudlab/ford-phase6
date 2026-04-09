package com.example.upi.service;

import com.example.upi.dto.TransferRequest;
import com.example.upi.dto.TransferResponse;
import com.example.upi.model.Account;
import com.example.upi.model.Transaction;
import com.example.upi.model.TransactionStatus;
import com.example.upi.repository.AccountRepository;
import com.example.upi.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransferService implements ITransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountCacheService cacheService;
    private final RateLimiterService rateLimiter;

    public TransferService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           AccountCacheService cacheService,
                           RateLimiterService rateLimiter) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cacheService = cacheService;
        this.rateLimiter = rateLimiter;
        log.info("TransferService initialized with Redis caching and rate limiting");
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {

        // 1. Rate limit check
        if (!rateLimiter.isAllowed(request.getSenderUpiId())) {
            throw new IllegalArgumentException(
                    "Rate limit exceeded. Max 10 transfers per minute for: " + request.getSenderUpiId());
        }

        // 2. Validate sender exists
        Account sender = accountRepository.findByUpiId(request.getSenderUpiId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sender UPI ID not found: " + request.getSenderUpiId()));

        // 3. Validate receiver exists
        Account receiver = accountRepository.findByUpiId(request.getReceiverUpiId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Receiver UPI ID not found: " + request.getReceiverUpiId()));

        // 4. Cannot send to self
        if (sender.getUpiId().equals(receiver.getUpiId())) {
            throw new IllegalArgumentException("Cannot transfer to yourself");
        }

        // 5. Check sufficient balance
        if (sender.getBalance().compareTo(request.getAmount()) < 0) {
            Transaction failed = new Transaction(
                    request.getSenderUpiId(), request.getReceiverUpiId(),
                    request.getAmount(), TransactionStatus.FAILED, request.getRemark());
            transactionRepository.save(failed);

            return new TransferResponse(failed.getId(), failed.getSenderUpiId(),
                    failed.getReceiverUpiId(), failed.getAmount(),
                    TransactionStatus.FAILED, "Insufficient balance", failed.getTimestamp());
        }

        // 6. Debit sender, credit receiver
        sender.setBalance(sender.getBalance().subtract(request.getAmount()));
        receiver.setBalance(receiver.getBalance().add(request.getAmount()));

        accountRepository.save(sender);
        accountRepository.save(receiver);

        // 7. Evict stale cache entries for both parties
        cacheService.evictFromCache(sender.getUpiId());
        cacheService.evictFromCache(receiver.getUpiId());

        // 8. Record transaction
        Transaction txn = new Transaction(
                request.getSenderUpiId(), request.getReceiverUpiId(),
                request.getAmount(), TransactionStatus.SUCCESS, request.getRemark());
        transactionRepository.save(txn);

        return new TransferResponse(txn.getId(), txn.getSenderUpiId(),
                txn.getReceiverUpiId(), txn.getAmount(),
                TransactionStatus.SUCCESS, "Transfer successful", txn.getTimestamp());
    }

    public Account getAccount(String upiId) {
        // Try cache first
        return cacheService.getFromCache(upiId)
                .orElseGet(() -> {
                    Account account = accountRepository.findByUpiId(upiId)
                            .orElseThrow(() -> new IllegalArgumentException("UPI ID not found: " + upiId));
                    // Populate cache for next time
                    cacheService.putInCache(account);
                    return account;
                });
    }

    public List<Transaction> getTransactions(String upiId) {
        return transactionRepository.findByUpiId(upiId);
    }
}
