package com.example.upi.dto;

import java.math.BigDecimal;

public class BalanceResponse {

    private String upiId;
    private String holderName;
    private BigDecimal balance;
    private InstanceInfo servedBy;

    public BalanceResponse(String upiId, String holderName, BigDecimal balance) {
        this.upiId = upiId;
        this.holderName = holderName;
        this.balance = balance;
        this.servedBy = new InstanceInfo();
    }

    public String getUpiId() { return upiId; }
    public String getHolderName() { return holderName; }
    public BigDecimal getBalance() { return balance; }
    public InstanceInfo getServedBy() { return servedBy; }
}
