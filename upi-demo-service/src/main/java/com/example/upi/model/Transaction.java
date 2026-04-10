package com.example.upi.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String senderUpiId;

    @Column(nullable = false)
    private String receiverUpiId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    private String remark;

    public Transaction() {}

    public Transaction(String senderUpiId, String receiverUpiId, BigDecimal amount,
                       TransactionStatus status, String remark) {
        this.senderUpiId = senderUpiId;
        this.receiverUpiId = receiverUpiId;
        this.amount = amount;
        this.status = status;
        this.remark = remark;
        this.timestamp = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSenderUpiId() { return senderUpiId; }
    public void setSenderUpiId(String senderUpiId) { this.senderUpiId = senderUpiId; }
    public String getReceiverUpiId() { return receiverUpiId; }
    public void setReceiverUpiId(String receiverUpiId) { this.receiverUpiId = receiverUpiId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
