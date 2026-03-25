package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import akka.javasdk.JsonSupport;
import com.example.domain.AccountStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class BulkGenerationWorkflowTest extends TestKitSupport {

    private final TestModelProvider signalResearchModel = new TestModelProvider();
    private final TestModelProvider messageGenerationModel = new TestModelProvider();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withModelProvider(SignalResearchAgent.class, signalResearchModel)
            .withModelProvider(MessageGenerationAgent.class, messageGenerationModel);
    }

    private void mockAgentResponses() {
        var signals = new SignalResearchAgent.Signals(
            List.of("Hiring AI engineers", "Evaluating LLM platforms", "Expanding team"));
        signalResearchModel.fixedResponse(JsonSupport.encodeToString(signals));

        var messages = new MessageGenerationAgent.Messages(List.of(
            new MessageGenerationAgent.MessageItem("CONNECTION_REQUEST", "CR body"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_1", "FU1 body"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_2", "FU2 body")
        ));
        messageGenerationModel.fixedResponse(JsonSupport.encodeToString(messages));
    }

    @Test
    public void testBulkJobDelegatesAndAllAccountsReachMessageReady() {
        mockAgentResponses();

        var accountId1 = UUID.randomUUID().toString();
        var accountId2 = UUID.randomUUID().toString();
        var bulkJobId = UUID.randomUUID().toString();

        // Create OGW accounts first (BGW delegate step will call startBulkGeneration on them)
        componentClient.forWorkflow(accountId1)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                "Bulk Corp A", "SaaS", "https://bulka.com", "CTO", ""));

        componentClient.forWorkflow(accountId2)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                "Bulk Corp B", "AI", "https://bulkb.com", "VP Engineering", ""));

        // Start bulk job
        componentClient.forWorkflow(bulkJobId)
            .method(BulkGenerationWorkflow::start)
            .invoke(new BulkGenerationWorkflow.StartRequest(bulkJobId, List.of(accountId1, accountId2)));

        // Wait for both accounts to reach MESSAGE_READY
        Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var state1 = componentClient.forWorkflow(accountId1)
                    .method(OutreachGenerationWorkflow::getState).invoke();
                assertThat(state1.account().status()).isEqualTo(AccountStatus.MESSAGE_READY);

                var state2 = componentClient.forWorkflow(accountId2)
                    .method(OutreachGenerationWorkflow::getState).invoke();
                assertThat(state2.account().status()).isEqualTo(AccountStatus.MESSAGE_READY);
            });
    }

    @Test
    public void testBulkJobWithEmptyListCompletes() {
        var bulkJobId = UUID.randomUUID().toString();

        componentClient.forWorkflow(bulkJobId)
            .method(BulkGenerationWorkflow::start)
            .invoke(new BulkGenerationWorkflow.StartRequest(bulkJobId, List.of()));

        // No delegation should happen — job starts and immediately pauses with empty list
        // State should reflect 0 accountIds and index 0
        var state = componentClient.forWorkflow(bulkJobId)
            .method(BulkGenerationWorkflow::getState).invoke();
        assertThat(state.accountIds()).isEmpty();
        assertThat(state.currentIndex()).isEqualTo(0);
    }

    @Test
    public void testNotifyDoneAdvancesToNextAccount() {
        mockAgentResponses();

        var accountId1 = UUID.randomUUID().toString();
        var accountId2 = UUID.randomUUID().toString();
        var bulkJobId = UUID.randomUUID().toString();

        componentClient.forWorkflow(accountId1)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                "Advance Corp A", "SaaS", "https://adva.com", "CFO", ""));

        componentClient.forWorkflow(accountId2)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                "Advance Corp B", "Tech", "https://advb.com", "CTO", ""));

        componentClient.forWorkflow(bulkJobId)
            .method(BulkGenerationWorkflow::start)
            .invoke(new BulkGenerationWorkflow.StartRequest(bulkJobId, List.of(accountId1, accountId2)));

        // Wait for account1 to reach MESSAGE_READY (first delegation triggered)
        Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var state1 = componentClient.forWorkflow(accountId1)
                    .method(OutreachGenerationWorkflow::getState).invoke();
                assertThat(state1.account().status()).isEqualTo(AccountStatus.MESSAGE_READY);
            });

        // Account2 should eventually reach MESSAGE_READY too
        Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var state2 = componentClient.forWorkflow(accountId2)
                    .method(OutreachGenerationWorkflow::getState).invoke();
                assertThat(state2.account().status()).isEqualTo(AccountStatus.MESSAGE_READY);
            });
    }
}