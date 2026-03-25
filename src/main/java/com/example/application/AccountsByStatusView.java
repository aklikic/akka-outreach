package com.example.application;

import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.annotations.Component;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.AccountStatus;
import com.example.domain.OutreachMessage;

import java.time.Instant;
import java.util.List;

@Component(id = "accounts-view")
public class AccountsByStatusView extends View {

    public record AccountEntry(
        String accountId,
        String companyName,
        String industry,
        String website,
        String targetPersona,
        String notes,
        String status,
        List<String> signals,
        List<OutreachMessage> messages,
        String hubspotId,
        Instant createdAt
    ) {}

    public record AccountEntries(List<AccountEntry> entries) {}

    @Query("SELECT * AS entries FROM accounts ORDER BY createdAt ASC")
    public QueryEffect<AccountEntries> getAllAccounts() {
        return queryResult();
    }

    @Query("SELECT * FROM accounts WHERE accountId = :accountId")
    public QueryEffect<AccountEntry> getAccountById(String accountId) {
        return queryResult();
    }

    @Consume.FromWorkflow(OutreachGenerationWorkflow.class)
    public static class AccountsUpdater extends TableUpdater<AccountEntry> {

        public Effect<AccountEntry> onUpdate(OutreachGenerationWorkflow.State state) {
            var account = state.account();
            var id = updateContext().eventSubject().orElse(account.accountId());
            return effects().updateRow(new AccountEntry(
                id,
                account.companyName(),
                account.industry(),
                account.website(),
                account.targetPersona(),
                account.notes() != null ? account.notes() : "",
                account.status().name(),
                account.signals() != null ? account.signals() : List.of(),
                account.messages() != null ? account.messages() : List.of(),
                account.hubspotId() != null ? account.hubspotId() : "",
                account.createdAt()
            ));
        }
    }
}