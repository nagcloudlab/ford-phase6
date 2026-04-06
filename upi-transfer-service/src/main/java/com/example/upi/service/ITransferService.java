package com.example.upi.service;

import com.example.upi.dto.TransferRequest;
import com.example.upi.dto.TransferResponse;
import com.example.upi.model.Account;
import com.example.upi.model.Transaction;

import java.util.List;

public interface ITransferService {

    TransferResponse transfer(TransferRequest request);

    Account getAccount(String upiId);

    List<Transaction> getTransactions(String upiId);
}
