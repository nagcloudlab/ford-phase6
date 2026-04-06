package com.example.upi.dto;

import com.example.upi.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransferResponse {

    private Long transactionId;
    private String senderUpiId;
    private String receiverUpiId;
    private BigDecimal amount;
    private TransactionStatus status;
    private String message;
    private LocalDateTime timestamp;
    private InstanceInfo servedBy;

    public TransferResponse(Long transactionId, String senderUpiId, String receiverUpiId,
                            BigDecimal amount, TransactionStatus status, String message,
                            LocalDateTime timestamp) {
        this.transactionId = transactionId;
        this.senderUpiId = senderUpiId;
        this.receiverUpiId = receiverUpiId;
        this.amount = amount;
        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
        this.servedBy = new InstanceInfo();
    }

    public Long getTransactionId() { return transactionId; }
    public String getSenderUpiId() { return senderUpiId; }
    public String getReceiverUpiId() { return receiverUpiId; }
    public BigDecimal getAmount() { return amount; }
    public TransactionStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public InstanceInfo getServedBy() { return servedBy; }
}
