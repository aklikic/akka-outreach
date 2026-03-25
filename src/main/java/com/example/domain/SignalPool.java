package com.example.domain;

import java.util.List;

public class SignalPool {

    private static final List<List<String>> POOLS = List.of(
        List.of("Hiring AI engineers", "Evaluating LLM orchestration platforms", "Expanding engineering team by 40%"),
        List.of("Recently raised Series B ($45M)", "Migrating off legacy monolith", "New CTO hired from hyperscaler"),
        List.of("Launched new product line", "Opening offices in EMEA", "Published engineering blog on distributed systems"),
        List.of("Acquired competitor last quarter", "Actively hiring platform engineers", "Migrating to cloud-native infrastructure"),
        List.of("Evaluating developer tooling vendors", "Recently IPO'd", "Tech stack modernization initiative underway")
    );

    public static List<String> signalsFor(String companyName) {
        int index = Math.abs(companyName.hashCode()) % POOLS.size();
        return POOLS.get(index);
    }
}