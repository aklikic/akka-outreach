package com.example.domain;

import java.time.Instant;
import java.util.List;

public record Account(
    String accountId,
    String companyName,
    String industry,
    String website,
    String targetPersona,
    String notes,
    AccountStatus status,
    List<String> signals,
    List<OutreachMessage> messages,
    String hubspotId,
    Instant createdAt
) {
    public Account withStatus(AccountStatus newStatus) {
        return new Account(accountId, companyName, industry, website, targetPersona, notes,
            newStatus, signals, messages, hubspotId, createdAt);
    }

    public Account withSignals(List<String> newSignals) {
        return new Account(accountId, companyName, industry, website, targetPersona, notes,
            status, newSignals, messages, hubspotId, createdAt);
    }

    public Account withMessages(List<OutreachMessage> newMessages) {
        return new Account(accountId, companyName, industry, website, targetPersona, notes,
            status, signals, newMessages, hubspotId, createdAt);
    }

    public Account withHubspotId(String newHubspotId) {
        return new Account(accountId, companyName, industry, website, targetPersona, notes,
            status, signals, messages, newHubspotId, createdAt);
    }
}