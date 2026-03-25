package com.example.domain;

public class HubSpotService {

    public String syncAccount(String companyName) {
        return String.format("hs_%06d", Math.abs(companyName.hashCode()) % 1_000_000);
    }
}