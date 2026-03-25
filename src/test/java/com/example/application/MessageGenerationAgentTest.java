package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import akka.javasdk.JsonSupport;
import com.example.domain.CompanyDetails;
import com.example.domain.OutreachMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class MessageGenerationAgentTest extends TestKitSupport {

    private final TestModelProvider messageGenerationModel = new TestModelProvider();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withModelProvider(MessageGenerationAgent.class, messageGenerationModel);
    }

    @Test
    public void testGenerateReturnsThreeMessages() {
        var expectedMessages = new MessageGenerationAgent.Messages(List.of(
            new MessageGenerationAgent.MessageItem("CONNECTION_REQUEST",
                "Hi! Noticed you're hiring AI engineers at Acme — I'd love to connect."),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_1",
                "Following up — would love 15 minutes to explore how we can help with your LLM platform."),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_2",
                "One last nudge — happy to share a quick case study if that helps.")
        ));
        messageGenerationModel.fixedResponse(JsonSupport.encodeToString(expectedMessages));

        var details = new CompanyDetails("Acme Corp", "Software", "https://acme.com", "VP Engineering", "");
        var signals = List.of("Hiring AI engineers", "Evaluating LLM orchestration platforms", "Expanding team by 40%");
        var request = new MessageGenerationAgent.GenerateRequest(details, signals);

        var result = componentClient
            .forAgent()
            .inSession(UUID.randomUUID().toString())
            .method(MessageGenerationAgent::generate)
            .invoke(request);

        assertThat(result.messages()).hasSize(3);
        assertThat(result.messages())
            .extracting(MessageGenerationAgent.MessageItem::messageType)
            .containsExactlyInAnyOrder("CONNECTION_REQUEST", "FOLLOW_UP_1", "FOLLOW_UP_2");
    }

    @Test
    public void testGeneratedMessageBodiesAreNonEmpty() {
        var messages = new MessageGenerationAgent.Messages(List.of(
            new MessageGenerationAgent.MessageItem("CONNECTION_REQUEST", "Connection request body"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_1", "Follow up 1 body"),
            new MessageGenerationAgent.MessageItem("FOLLOW_UP_2", "Follow up 2 body")
        ));
        messageGenerationModel.fixedResponse(JsonSupport.encodeToString(messages));

        var details = new CompanyDetails("Beta Ltd", "FinTech", "https://beta.io", "CFO", "");
        var request = new MessageGenerationAgent.GenerateRequest(
            details, List.of("Recently raised Series B", "Migrating off legacy monolith", "New CTO hired"));

        var result = componentClient
            .forAgent()
            .inSession(UUID.randomUUID().toString())
            .method(MessageGenerationAgent::generate)
            .invoke(request);

        assertThat(result.messages())
            .allSatisfy(msg -> assertThat(msg.body()).isNotBlank());
        // Verify conversion to domain type works
        assertThat(result.toOutreachMessages()).hasSize(3);
    }
}