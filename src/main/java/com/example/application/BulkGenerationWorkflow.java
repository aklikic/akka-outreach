package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;

import java.util.List;

import static java.time.Duration.ofSeconds;

@Component(id = "bulk-generation")
public class BulkGenerationWorkflow extends Workflow<BulkGenerationWorkflow.State> {

    public record StartRequest(String bulkJobId, List<String> accountIds) {}

    public record NotifyDoneRequest(String accountId, boolean success) {}

    public record State(List<String> accountIds, int currentIndex) {
        public State advance() {
            return new State(accountIds, currentIndex + 1);
        }

        public boolean hasMore() {
            return currentIndex < accountIds.size();
        }

        public String currentAccountId() {
            return accountIds.get(currentIndex);
        }
    }

    private final ComponentClient componentClient;

    public BulkGenerationWorkflow(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    @Override
    public WorkflowSettings settings() {
        return WorkflowSettings.builder()
            .defaultStepTimeout(ofSeconds(10))
            .build();
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    public Effect<Done> start(StartRequest request) {
        if (currentState() != null) {
            return effects().error("Bulk job already started");
        }
        if (request.accountIds().isEmpty()) {
            return effects()
                .updateState(new State(List.of(), 0))
                .pause()
                .thenReply(Done.getInstance());
        }
        return effects()
            .updateState(new State(request.accountIds(), 0))
            .transitionTo(BulkGenerationWorkflow::delegateStep)
            .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<State> getState() {
        if (currentState() == null) {
            return effects().error("Bulk job not found");
        }
        return effects().reply(currentState());
    }

    public Effect<Done> notifyDone(NotifyDoneRequest request) {
        if (currentState() == null) {
            return effects().error("Bulk job not found");
        }
        var next = currentState().advance();
        if (next.hasMore()) {
            return effects()
                .updateState(next)
                .transitionTo(BulkGenerationWorkflow::delegateStep)
                .thenReply(Done.getInstance());
        } else {
            // No more accounts — update state and pause; workflow is at terminal state
            return effects()
                .updateState(next)
                .pause()
                .thenReply(Done.getInstance());
        }
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    @StepName("delegate")
    private StepEffect delegateStep() {
        var accountId = currentState().currentAccountId();
        var bulkJobId = commandContext().workflowId();

        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::startBulkGeneration)
            .invoke(bulkJobId);

        return stepEffects()
            .thenPause();
    }
}