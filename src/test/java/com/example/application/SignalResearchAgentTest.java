package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import akka.javasdk.JsonSupport;
import com.example.domain.CompanyDetails;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SignalResearchAgentTest extends TestKitSupport {

    private final TestModelProvider signalResearchModel = new TestModelProvider();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withModelProvider(SignalResearchAgent.class, signalResearchModel);
    }

    @Test
    public void testResearchReturnsSignals() {
        var expectedSignals = new SignalResearchAgent.Signals(
            List.of("Hiring AI engineers", "Evaluating LLM orchestration platforms", "Expanding engineering team by 40%")
        );
        signalResearchModel.fixedResponse(JsonSupport.encodeToString(expectedSignals));

        var details = new CompanyDetails("Acme Corp", "Software", "https://acme.com", "VP Engineering", "");

        var result = componentClient
            .forAgent()
            .inSession(UUID.randomUUID().toString())
            .method(SignalResearchAgent::research)
            .invoke(details);

        assertThat(result.signals()).hasSize(3);
        assertThat(result.signals()).containsExactlyElementsOf(expectedSignals.signals());
    }

    @Test
    public void testResearchSignalsAreNonEmpty() {
        var signals = new SignalResearchAgent.Signals(
            List.of("Signal A", "Signal B", "Signal C")
        );
        signalResearchModel.fixedResponse(JsonSupport.encodeToString(signals));

        var details = new CompanyDetails("Beta Ltd", "FinTech", "https://beta.io", "CFO", "Met at conference");

        var result = componentClient
            .forAgent()
            .inSession(UUID.randomUUID().toString())
            .method(SignalResearchAgent::research)
            .invoke(details);

        assertThat(result.signals()).isNotEmpty();
        assertThat(result.signals()).allSatisfy(s -> assertThat(s).isNotBlank());
    }
}