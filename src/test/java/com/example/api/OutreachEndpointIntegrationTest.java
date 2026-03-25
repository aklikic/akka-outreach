package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import akka.javasdk.JsonSupport;
import com.example.application.AccountsByStatusView;
import com.example.application.MessageGenerationAgent;
import com.example.application.SignalResearchAgent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class OutreachEndpointIntegrationTest extends TestKitSupport {

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
    public void testCreateAccountReturns201() {
        var request = new OutreachEndpoint.CreateAccountRequest(
            "Acme Corp", "Software", "https://acme.com", "VP Engineering", "Met at KubeCon");

        var response = httpClient
            .POST("/accounts")
            .withRequestBody(request)
            .responseBodyAs(OutreachEndpoint.CreateAccountResponse.class)
            .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.status().intValue()).isEqualTo(201);
        assertThat(response.body().accountId()).isNotBlank();
        assertThat(response.body().status()).isEqualTo("NEW");
    }

    @Test
    public void testCreateAccountMissingFieldReturns400() {
        var response = httpClient
            .POST("/accounts")
            .withRequestBody(new OutreachEndpoint.CreateAccountRequest(
                "", "Software", "https://acme.com", "VP Engineering", ""))
            .invoke();

        assertThat(response.status().intValue()).isEqualTo(400);
    }

    @Test
    public void testListAccountsReturnsCreatedAccount() {
        var request = new OutreachEndpoint.CreateAccountRequest(
            "ListTest Corp", "FinTech", "https://listtest.com", "CFO", "");

        var createResponse = httpClient
            .POST("/accounts")
            .withRequestBody(request)
            .responseBodyAs(OutreachEndpoint.CreateAccountResponse.class)
            .invoke();
        var accountId = createResponse.body().accountId();

        Awaitility.await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var listResponse = httpClient
                    .GET("/accounts")
                    .responseBodyAs(AccountsByStatusView.AccountEntries.class)
                    .invoke();
                assertThat(listResponse.body().entries())
                    .anyMatch(e -> e.accountId().equals(accountId));
            });
    }

    @Test
    public void testGenerateMessagesReturns200() {
        mockAgentResponses();

        var createResp = httpClient
            .POST("/accounts")
            .withRequestBody(new OutreachEndpoint.CreateAccountRequest(
                "GenTest Corp", "AI", "https://gentest.com", "CTO", ""))
            .responseBodyAs(OutreachEndpoint.CreateAccountResponse.class)
            .invoke();
        var accountId = createResp.body().accountId();

        // Wait for view to be consistent
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var listResp = httpClient.GET("/accounts")
                .responseBodyAs(AccountsByStatusView.AccountEntries.class).invoke();
            assertThat(listResp.body().entries()).anyMatch(e -> e.accountId().equals(accountId));
        });

        var genResp = httpClient
            .POST("/accounts/" + accountId + "/generate")
            .responseBodyAs(OutreachEndpoint.AccountActionResponse.class)
            .invoke();

        assertThat(genResp.status().isSuccess()).isTrue();
        assertThat(genResp.body().accountId()).isEqualTo(accountId);
    }

    @Test
    public void testRespondAndHubSpotSync() {
        mockAgentResponses();

        var createResp = httpClient
            .POST("/accounts")
            .withRequestBody(new OutreachEndpoint.CreateAccountRequest(
                "Respond Corp", "SaaS", "https://respond.io", "VP Sales", ""))
            .responseBodyAs(OutreachEndpoint.CreateAccountResponse.class)
            .invoke();
        var accountId = createResp.body().accountId();

        // Generate first
        httpClient.POST("/accounts/" + accountId + "/generate").invoke();

        // Wait for MESSAGE_READY
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var listResp = httpClient.GET("/accounts")
                .responseBodyAs(AccountsByStatusView.AccountEntries.class).invoke();
            assertThat(listResp.body().entries())
                .anyMatch(e -> e.accountId().equals(accountId) && "MESSAGE_READY".equals(e.status()));
        });

        // Mark as responded
        var respondResp = httpClient
            .POST("/accounts/" + accountId + "/respond")
            .responseBodyAs(OutreachEndpoint.AccountActionResponse.class)
            .invoke();

        assertThat(respondResp.status().isSuccess()).isTrue();
        assertThat(respondResp.body().status()).isEqualTo("RESPONDED");

        // Wait for HubSpot sync
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var listResp = httpClient.GET("/accounts")
                .responseBodyAs(AccountsByStatusView.AccountEntries.class).invoke();
            assertThat(listResp.body().entries())
                .anyMatch(e -> e.accountId().equals(accountId)
                    && e.hubspotId() != null
                    && !e.hubspotId().isEmpty()
                    && e.hubspotId().startsWith("hs_"));
        });
    }

    @Test
    public void testBulkUploadParsesCSV() {
        var csv = "companyName,industry,website,targetPersona,notes\n" +
                  "Alpha Inc,Software,https://alpha.com,VP Engineering,\n" +
                  "Beta Ltd,FinTech,https://beta.io,CFO,Met at SaaStr\n" +
                  "Gamma Co,,https://gamma.com,CTO,\n";  // missing industry — rejected

        var response = httpClient
            .POST("/accounts/bulk")
            .withRequestBody(csv)
            .responseBodyAs(OutreachEndpoint.BulkUploadResult.class)
            .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.body().total()).isEqualTo(3);
        assertThat(response.body().created()).isEqualTo(2);
        assertThat(response.body().rejected()).hasSize(1);
        assertThat(response.body().rejected().get(0).companyName()).isEqualTo("Gamma Co");
    }
}