package com.example.upi.repository;

import com.example.upi.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT t FROM Transaction t WHERE t.senderUpiId = :upiId OR t.receiverUpiId = :upiId ORDER BY t.timestamp DESC")
    List<Transaction> findByUpiId(String upiId);
}
