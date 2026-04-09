package com.example.upi.dto;

import java.net.InetAddress;

public class InstanceInfo {

    private String hostName;
    private String hostAddress;

    public InstanceInfo() {
        try {
            InetAddress host = InetAddress.getLocalHost();
            this.hostName = host.getHostName();
            this.hostAddress = host.getHostAddress();
        } catch (Exception e) {
            this.hostName = "unknown";
            this.hostAddress = "unknown";
        }
    }

    public String getHostName() { return hostName; }
    public String getHostAddress() { return hostAddress; }
}
