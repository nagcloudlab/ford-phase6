package com.example.notification;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionEvent {
    private Long transactionId;
    private String senderUpiId;
    private String receiverUpiId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime timestamp;

    public TransactionEvent() {}

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    public String getSenderUpiId() { return senderUpiId; }
    public void setSenderUpiId(String senderUpiId) { this.senderUpiId = senderUpiId; }

    public String getReceiverUpiId() { return receiverUpiId; }
    public void setReceiverUpiId(String receiverUpiId) { this.receiverUpiId = receiverUpiId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "TransactionEvent{txnId=" + transactionId +
                ", from=" + senderUpiId + ", to=" + receiverUpiId +
                ", amount=" + amount + ", status=" + status + "}";
    }
}
