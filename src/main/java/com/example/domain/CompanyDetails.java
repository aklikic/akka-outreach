package com.example.domain;

public record CompanyDetails(
    String companyName,
    String industry,
    String website,
    String targetPersona,
    String notes
) {
    public static CompanyDetails from(Account account) {
        return new CompanyDetails(
            account.companyName(),
            account.industry(),
            account.website(),
            account.targetPersona(),
            account.notes()
        );
    }
}