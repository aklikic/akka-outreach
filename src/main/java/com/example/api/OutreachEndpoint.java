package com.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import com.example.application.AccountsByStatusView;
import com.example.application.BulkGenerationWorkflow;
import com.example.application.OutreachGenerationWorkflow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@HttpEndpoint("/")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class OutreachEndpoint extends AbstractHttpEndpoint {

    // ── Request / response records ────────────────────────────────────────────

    public record CreateAccountRequest(
        String companyName,
        String industry,
        String website,
        String targetPersona,
        String notes
    ) {}

    public record CreateAccountResponse(String accountId, String status) {}

    public record AccountActionResponse(String accountId, String status) {}

    public record SfdcMessages(String connectionRequest, String followUp1, String followUp2) {}

    public record SfdcPayload(
        String company,
        String industry,
        String website,
        String targetPersona,
        String leadStatus,
        List<String> intentSignals,
        SfdcMessages linkedInMessages,
        String hubSpotId
    ) {}

    public record RejectedRow(int rowIndex, String companyName, String reason) {}

    public record BulkUploadResult(int total, int created, List<RejectedRow> rejected) {}

    public record GenerateAllResponse(String bulkJobId, int accountCount) {}

    // ── Constructor ───────────────────────────────────────────────────────────

    private final ComponentClient componentClient;

    public OutreachEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    // ── Static content ────────────────────────────────────────────────────────

    @Get("/")
    public HttpResponse index() {
        return HttpResponses.staticResource("index.html");
    }

    // ── Account endpoints ─────────────────────────────────────────────────────

    @Post("/accounts")
    public HttpResponse createAccount(CreateAccountRequest request) {
        if (request.companyName() == null || request.companyName().isBlank()) {
            return HttpResponses.badRequest("companyName is required");
        }
        if (request.industry() == null || request.industry().isBlank()) {
            return HttpResponses.badRequest("industry is required");
        }
        if (request.website() == null || request.website().isBlank()) {
            return HttpResponses.badRequest("website is required");
        }
        if (request.targetPersona() == null || request.targetPersona().isBlank()) {
            return HttpResponses.badRequest("targetPersona is required");
        }

        var accountId = UUID.randomUUID().toString();
        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::create)
            .invoke(new OutreachGenerationWorkflow.CreateRequest(
                request.companyName(),
                request.industry(),
                request.website(),
                request.targetPersona(),
                request.notes() != null ? request.notes() : ""
            ));

        return HttpResponses.created(new CreateAccountResponse(accountId, "NEW"));
    }

    @Get("/accounts")
    public AccountsByStatusView.AccountEntries listAccounts() {
        return componentClient
            .forView()
            .method(AccountsByStatusView::getAllAccounts)
            .invoke();
    }

    @Post("/accounts/{accountId}/generate")
    public AccountActionResponse generateMessages(String accountId) {
        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::startGeneration)
            .invoke();

        return new AccountActionResponse(accountId, "NEW");
    }

    @Post("/accounts/{accountId}/respond")
    public AccountActionResponse markResponded(String accountId) {
        componentClient
            .forWorkflow(accountId)
            .method(OutreachGenerationWorkflow::markAsResponded)
            .invoke();

        return new AccountActionResponse(accountId, "RESPONDED");
    }

    @Get("/accounts/{accountId}/sfdc")
    public SfdcPayload getSfdcPayload(String accountId) {
        var entry = componentClient
            .forView()
            .method(AccountsByStatusView::getAccountById)
            .invoke(accountId);

        String connectionRequest = "";
        String followUp1 = "";
        String followUp2 = "";

        for (var msg : entry.messages()) {
                switch (msg.messageType()) {
                    case CONNECTION_REQUEST -> connectionRequest = msg.body();
                    case FOLLOW_UP_1 -> followUp1 = msg.body();
                    case FOLLOW_UP_2 -> followUp2 = msg.body();
                }
            }

        return new SfdcPayload(
            entry.companyName(),
            entry.industry(),
            entry.website(),
            entry.targetPersona(),
            entry.status(),
            entry.signals(),
            new SfdcMessages(connectionRequest, followUp1, followUp2),
            entry.hubspotId()
        );
    }

    // ── Bulk upload endpoints ─────────────────────────────────────────────────

    @Post("/accounts/bulk")
    public BulkUploadResult bulkUpload(String csvBody) {
        var rejected = new ArrayList<RejectedRow>();
        var createdIds = new ArrayList<String>();
        int total = 0;

        try (var parser = CSVParser.parse(new StringReader(csvBody),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            // Validate required headers exist
            var headers = parser.getHeaderMap();
            for (var required : List.of("companyName", "industry", "website", "targetPersona")) {
                if (!headers.containsKey(required)) {
                    return new BulkUploadResult(0, 0, List.of(
                        new RejectedRow(0, "", "Missing required column: " + required)));
                }
            }

            int rowIndex = 1;
            for (var record : parser) {
                total++;
                var companyName = record.get("companyName");
                var industry = record.get("industry");
                var website = record.get("website");
                var targetPersona = record.get("targetPersona");
                var notes = headers.containsKey("notes") ? record.get("notes") : "";

                if (companyName == null || companyName.isBlank()) {
                    rejected.add(new RejectedRow(rowIndex, companyName, "companyName is required"));
                    rowIndex++;
                    continue;
                }
                if (industry == null || industry.isBlank()) {
                    rejected.add(new RejectedRow(rowIndex, companyName, "industry is required"));
                    rowIndex++;
                    continue;
                }
                if (website == null || website.isBlank()) {
                    rejected.add(new RejectedRow(rowIndex, companyName, "website is required"));
                    rowIndex++;
                    continue;
                }
                if (targetPersona == null || targetPersona.isBlank()) {
                    rejected.add(new RejectedRow(rowIndex, companyName, "targetPersona is required"));
                    rowIndex++;
                    continue;
                }

                var accountId = UUID.randomUUID().toString();
                componentClient
                    .forWorkflow(accountId)
                    .method(OutreachGenerationWorkflow::create)
                    .invoke(new OutreachGenerationWorkflow.CreateRequest(
                        companyName, industry, website, targetPersona,
                        notes != null ? notes : ""));
                createdIds.add(accountId);
                rowIndex++;
            }
        } catch (Exception e) {
            return new BulkUploadResult(0, 0, List.of(
                new RejectedRow(0, "", "Failed to parse CSV: " + e.getMessage())));
        }

        return new BulkUploadResult(total, createdIds.size(), rejected);
    }

    @Post("/accounts/generate-all")
    public GenerateAllResponse generateAll() {
        var accounts = componentClient
            .forView()
            .method(AccountsByStatusView::getAllAccounts)
            .invoke();

        var eligibleIds = accounts.entries().stream()
            .filter(e -> "NEW".equals(e.status()) || "ERROR".equals(e.status()))
            .map(AccountsByStatusView.AccountEntry::accountId)
            .toList();

        if (eligibleIds.isEmpty()) {
            return new GenerateAllResponse("", 0);
        }

        var bulkJobId = UUID.randomUUID().toString();
        componentClient
            .forWorkflow(bulkJobId)
            .method(BulkGenerationWorkflow::start)
            .invoke(new BulkGenerationWorkflow.StartRequest(bulkJobId, eligibleIds));

        return new GenerateAllResponse(bulkJobId, eligibleIds.size());
    }

    // ── SSE endpoint ──────────────────────────────────────────────────────────

    @Get("/events")
    public HttpResponse streamAccountUpdates() {
        var source = Source.tick(
            Duration.ZERO,
            Duration.ofSeconds(2),
            "tick"
        ).map(__ -> componentClient
            .forView()
            .method(AccountsByStatusView::getAllAccounts)
            .invoke());

        return HttpResponses.serverSentEvents(source);
    }
}