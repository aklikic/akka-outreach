package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import com.example.domain.AccountStatus;
import com.example.domain.HubSpotService;

@Component(id = "hubspot-sync")
@Consume.FromWorkflow(OutreachGenerationWorkflow.class)
public class HubSpotSyncConsumer extends Consumer {

    private final ComponentClient componentClient;
    private final HubSpotService hubSpotService;

    public HubSpotSyncConsumer(ComponentClient componentClient) {
        this.componentClient = componentClient;
        this.hubSpotService = new HubSpotService();
    }

    public Effect onUpdate(OutreachGenerationWorkflow.State state) {
        var account = state.account();

        if (account.status() == AccountStatus.RESPONDED && account.hubspotId() == null) {
            var hubspotId = hubSpotService.syncAccount(account.companyName());
            componentClient
                .forWorkflow(account.accountId())
                .method(OutreachGenerationWorkflow::recordHubSpotSync)
                .invoke(hubspotId);
            return effects().done();
        }

        return effects().ignore();
    }
}