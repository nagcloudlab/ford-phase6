package com.example.upi.service;

import com.example.upi.model.Account;
import com.example.upi.model.Transaction;
import com.example.upi.model.TransactionStatus;
import com.example.upi.repository.AccountRepository;
import com.example.upi.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransferService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Transaction transfer(String senderUpiId, String receiverUpiId, BigDecimal amount, String remark) {

        Account sender = accountRepository.findByUpiId(senderUpiId)
                .orElseThrow(() -> new IllegalArgumentException("Sender UPI ID not found: " + senderUpiId));

        Account receiver = accountRepository.findByUpiId(receiverUpiId)
                .orElseThrow(() -> new IllegalArgumentException("Receiver UPI ID not found: " + receiverUpiId));

        if (sender.getUpiId().equals(receiver.getUpiId())) {
            throw new IllegalArgumentException("Cannot transfer to yourself");
        }

        if (sender.getBalance().compareTo(amount) < 0) {
            Transaction failed = new Transaction(senderUpiId, receiverUpiId, amount, TransactionStatus.FAILED, remark);
            return transactionRepository.save(failed);
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accountRepository.save(sender);
        accountRepository.save(receiver);

        Transaction txn = new Transaction(senderUpiId, receiverUpiId, amount, TransactionStatus.SUCCESS, remark);
        Transaction saved = transactionRepository.save(txn);

        log.info("TRANSFER | {} -> {} | amount={} | status=SUCCESS", senderUpiId, receiverUpiId, amount);
        return saved;
    }

    public Account getAccount(String upiId) {
        return accountRepository.findByUpiId(upiId)
                .orElseThrow(() -> new IllegalArgumentException("UPI ID not found: " + upiId));
    }

    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    public List<Transaction> getTransactions(String upiId) {
        return transactionRepository.findByUpiId(upiId);
    }

    public List<Transaction> getAllTransactions() {
        return transactionRepository.findAll();
    }
}
