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

public class OutreachGenerationWorkflowTest extends TestKitSupport {

    private final TestModelProvider signalResearchModel = new TestModelProvider();
    private final TestModelProvider messageGenerationModel = new TestModelProvider();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withModelProvider(SignalResearchAgent.class, signalResearchModel)
            .withModelProvider(MessageGenerationAgent.class, messageGenerationModel);
    }

    @Test
    public void testHappyPath_createAndGenerateReachesMessageReady() {
        var accountId = UUID.randomUUID().toString();

        // Mock signal research
        var signals = new SignalResearchAgent.Signals(
            List.of("Hiring AI engineers", "Evaluating LLM platforms", "Expanding team by 40%"));
        signalResearchModel.fixedResponse(JsonSupport.encodeToString(signals));

        // Mock message generation
        var messages = new MessageGenerationAgent.Messages(List.of(
            new MessageGenerationAgent.MessageItem("CONNECTION_REQUEST", "Hi!"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_1", "Follow up 1"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_2", "Follow up 2")
        ));
        messageGenerationModel.fixedResponse(JsonSupport.encodeToString(messages));

        // Create account
        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                "Acme Corp", "Software", "https://acme.com", "VP Engineering", ""));

        // Verify initial state
        var initialState = componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::getState)
            .invoke();
        assertThat(initialState.account().status()).isEqualTo(AccountStatus.NEW);
        assertThat(initialState.account().signals()).isEmpty();
        assertThat(initialState.account().messages()).isEmpty();

        // Start generation
        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::startGeneration)
            .invoke();

        // Wait for MESSAGE_READY
        Awaitility.await()
            .atMost(30, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var state = componentClient
                    .forWorkflow(accountId)
                    .method(OutreachGenerationWorkflow::getState)
                    .invoke();
                assertThat(state.account().status()).isEqualTo(AccountStatus.MESSAGE_READY);
                assertThat(state.account().signals()).hasSize(3);
                assertThat(state.account().messages()).hasSize(3);
            });
    }

    @Test
    public void testErrorPath_generationFailureReachesErrorStatus() {
        var accountId = UUID.randomUUID().toString();

        // No mock set — agent call will fail, triggering errorStep
        // Create and start generation
        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                "Fail Corp", "Unknown", "https://fail.io", "CTO", ""));

        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::startGeneration)
            .invoke();

        // Wait for ERROR status
        Awaitility.await()
            .atMost(30, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var state = componentClient
                    .forWorkflow(accountId)
                    .method(OutreachGenerationWorkflow::getState)
                    .invoke();
                assertThat(state.account().status()).isEqualTo(AccountStatus.ERROR);
            });
    }

    @Test
    public void testMarkAsResponded() {
        var accountId = UUID.randomUUID().toString();

        var signals = new SignalResearchAgent.Signals(List.of("s1", "s2", "s3"));
        signalResearchModel.fixedResponse(JsonSupport.encodeToString(signals));

        var messages = new MessageGenerationAgent.Messages(List.of(
            new MessageGenerationAgent.MessageItem("CONNECTION_REQUEST", "CR"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_1", "FU1"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_2", "FU2")
        ));
        messageGenerationModel.fixedResponse(JsonSupport.encodeToString(messages));

        componentClient.forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                "Respond Corp", "Tech", "https://respond.io", "VP Sales", ""));

        componentClient.forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::startGeneration)
            .invoke();

        Awaitility.await()
            .atMost(30, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var state = componentClient.forWorkflow(accountId)
                    .method(OutreachGenerationWorkflow::getState).invoke();
                assertThat(state.account().status()).isEqualTo(AccountStatus.MESSAGE_READY);
            });

        componentClient.forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::markAsResponded)
            .invoke();

        var finalState = componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::getState)
            .invoke();
        assertThat(finalState.account().status()).isEqualTo(AccountStatus.RESPONDED);
    }
}