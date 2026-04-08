package com.example.upi.service;

import com.example.upi.dto.TransactionEvent;
import com.example.upi.dto.TransferRequest;
import com.example.upi.dto.TransferResponse;
import com.example.upi.model.Account;
import com.example.upi.model.Transaction;
import com.example.upi.model.TransactionStatus;
import com.example.upi.repository.AccountRepository;
import com.example.upi.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransferService implements ITransferService {

        private final AccountRepository accountRepository;
        private final TransactionRepository transactionRepository;

        private final TransactionEventPublisher eventPublisher;

        // Update constructor to include eventPublisher
        public TransferService(AccountRepository accountRepository,
                        TransactionRepository transactionRepository,
                        TransactionEventPublisher eventPublisher) {
                this.accountRepository = accountRepository;
                this.transactionRepository = transactionRepository;
                this.eventPublisher = eventPublisher;
        }

        @Transactional
        public TransferResponse transfer(TransferRequest request) {

                // 1. Validate sender exists
                Account sender = accountRepository.findByUpiId(request.getSenderUpiId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Sender UPI ID not found: " + request.getSenderUpiId()));

                // 2. Validate receiver exists
                Account receiver = accountRepository.findByUpiId(request.getReceiverUpiId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Receiver UPI ID not found: " + request.getReceiverUpiId()));

                // 3. Cannot send to self
                if (sender.getUpiId().equals(receiver.getUpiId())) {
                        throw new IllegalArgumentException("Cannot transfer to yourself");
                }

                // 4. Check sufficient balance
                if (sender.getBalance().compareTo(request.getAmount()) < 0) {
                        Transaction failed = new Transaction(
                                        request.getSenderUpiId(), request.getReceiverUpiId(),
                                        request.getAmount(), TransactionStatus.FAILED, request.getRemark());
                        transactionRepository.save(failed);

                        return new TransferResponse(failed.getId(), failed.getSenderUpiId(),
                                        failed.getReceiverUpiId(), failed.getAmount(),
                                        TransactionStatus.FAILED, "Insufficient balance", failed.getTimestamp());
                }

                // 5. Debit sender, credit receiver
                sender.setBalance(sender.getBalance().subtract(request.getAmount()));
                receiver.setBalance(receiver.getBalance().add(request.getAmount()));

                accountRepository.save(sender);
                accountRepository.save(receiver);

                // 6. Record transaction
                Transaction txn = new Transaction(
                                request.getSenderUpiId(), request.getReceiverUpiId(),
                                request.getAmount(), TransactionStatus.SUCCESS, request.getRemark());
                Transaction savedTransaction = transactionRepository.save(txn);

                // Publish event after successful transfer // asynchronously
                TransactionEvent event = new TransactionEvent(
                                savedTransaction.getId(),
                                request.getSenderUpiId(),
                                request.getReceiverUpiId(),
                                request.getAmount(),
                                savedTransaction.getStatus().name());
                eventPublisher.publishTransactionEvent(event); // GCP Pub/Sub

                return new TransferResponse(txn.getId(), txn.getSenderUpiId(),
                                txn.getReceiverUpiId(), txn.getAmount(),
                                TransactionStatus.SUCCESS, "Transfer successful", txn.getTimestamp());

        }

        public Account getAccount(String upiId) {
                return accountRepository.findByUpiId(upiId)
                                .orElseThrow(() -> new IllegalArgumentException("UPI ID not found: " + upiId));
        }

        public List<Transaction> getTransactions(String upiId) {
                return transactionRepository.findByUpiId(upiId);
        }
}
