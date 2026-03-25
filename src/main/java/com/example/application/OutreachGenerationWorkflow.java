package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.example.domain.Account;
import com.example.domain.AccountStatus;
import com.example.domain.CompanyDetails;

import java.time.Instant;
import java.util.List;

import static java.time.Duration.ofSeconds;

@Component(id = "outreach-generation")
public class OutreachGenerationWorkflow extends Workflow<OutreachGenerationWorkflow.State> {

    public record CreateRequest(
        String companyName,
        String industry,
        String website,
        String targetPersona,
        String notes
    ) {}

    public record State(Account account, String bulkJobId) {
        public State withAccount(Account a) {
            return new State(a, bulkJobId);
        }

        public State withBulkJobId(String id) {
            return new State(account, id);
        }
    }

    private final ComponentClient componentClient;

    public OutreachGenerationWorkflow(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    @Override
    public WorkflowSettings settings() {
        return WorkflowSettings.builder()
            .defaultStepTimeout(ofSeconds(60))
            .stepTimeout(OutreachGenerationWorkflow::updateStatusStep, ofSeconds(5))
            .stepTimeout(OutreachGenerationWorkflow::errorStep, ofSeconds(5))
            .defaultStepRecovery(maxRetries(1).failoverTo(OutreachGenerationWorkflow::errorStep))
            .build();
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    public Effect<Done> create(CreateRequest request) {
        if (currentState() != null) {
            return effects().error("Account already exists");
        }
        var accountId = commandContext().workflowId();
        var account = new Account(
            accountId,
            request.companyName(),
            request.industry(),
            request.website(),
            request.targetPersona(),
            request.notes(),
            AccountStatus.NEW,
            List.of(),
            List.of(),
            null,
            Instant.now()
        );
        return effects()
            .updateState(new State(account, null))
                .pause()
            .thenReply(Done.getInstance());
    }

    /** Trigger generation for a single-account flow (no bulk job). */
    public Effect<Done> startGeneration() {
        if (currentState() == null) {
            return effects().error("Account not found");
        }
        return effects()
            .updateState(currentState().withBulkJobId(null))
            .transitionTo(OutreachGenerationWorkflow::researchSignalsStep)
            .thenReply(Done.getInstance());
    }

    /** Trigger generation as part of a bulk job — OGW will notify BGW when done. */
    public Effect<Done> startBulkGeneration(String bulkJobId) {
        if (currentState() == null) {
            return effects().error("Account not found");
        }
        return effects()
            .updateState(currentState().withBulkJobId(bulkJobId))
            .transitionTo(OutreachGenerationWorkflow::researchSignalsStep)
            .thenReply(Done.getInstance());
    }

    public Effect<Done> markAsResponded() {
        if (currentState() == null) {
            return effects().error("Account not found");
        }
        var updated = currentState().account().withStatus(AccountStatus.RESPONDED);
        return effects()
            .updateState(currentState().withAccount(updated))
                .pause()
            .thenReply(Done.getInstance());
    }

    public Effect<Done> recordHubSpotSync(String hubspotId) {
        if (currentState() == null) {
            return effects().error("Account not found");
        }
        var updated = currentState().account().withHubspotId(hubspotId);
        return effects()
            .updateState(currentState().withAccount(updated))
                .pause()
            .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<State> getState() {
        if (currentState() == null) {
            return effects().error("Account not found");
        }
        return effects().reply(currentState());
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    @StepName("research-signals")
    private StepEffect researchSignalsStep() {
        var details = CompanyDetails.from(currentState().account());

        var result = componentClient
            .forAgent()
            .inSession(sessionId())
            .method(SignalResearchAgent::research)
            .invoke(details);

        var updatedAccount = currentState().account().withSignals(result.signals());

        return stepEffects()
            .updateState(currentState().withAccount(updatedAccount))
            .thenTransitionTo(OutreachGenerationWorkflow::generateMessagesStep);
    }

    @StepName("generate-messages")
    private StepEffect generateMessagesStep() {
        var account = currentState().account();
        var request = new MessageGenerationAgent.GenerateRequest(
            CompanyDetails.from(account),
            account.signals()
        );

        var result = componentClient
            .forAgent()
            .inSession(sessionId())
            .method(MessageGenerationAgent::generate)
            .invoke(request);

        var updatedAccount = account.withMessages(result.toOutreachMessages());

        return stepEffects()
            .updateState(currentState().withAccount(updatedAccount))
            .thenTransitionTo(OutreachGenerationWorkflow::updateStatusStep);
    }

    @StepName("update-status")
    private StepEffect updateStatusStep() {
        var updatedAccount = currentState().account().withStatus(AccountStatus.MESSAGE_READY);
        var updatedState = currentState().withAccount(updatedAccount);

        notifyBulkJobIfNeeded(true);

        return stepEffects()
            .updateState(updatedState)
            .thenPause();
    }

    @StepName("error")
    private StepEffect errorStep() {
        var updatedAccount = currentState().account().withStatus(AccountStatus.ERROR);
        var updatedState = currentState().withAccount(updatedAccount);

        notifyBulkJobIfNeeded(false);

        return stepEffects()
            .updateState(updatedState)
            .thenPause();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String sessionId() {
        return commandContext().workflowId();
    }

    private void notifyBulkJobIfNeeded(boolean success) {
        var bulkJobId = currentState().bulkJobId();
        if (bulkJobId != null) {
            componentClient
                .forWorkflow(bulkJobId)
                .method(BulkGenerationWorkflow::notifyDone)
                .invoke(new BulkGenerationWorkflow.NotifyDoneRequest(
                    currentState().account().accountId(), success));
        }
    }
}