package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.AgentRole;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.FunctionTool;
import com.example.domain.CompanyDetails;
import com.example.domain.SignalPool;

import java.util.List;

@Component(id = "signal-research")
@AgentRole("Company signal researcher. Given company details, returns exactly 3 intent signals describing recent activity at the company.")
public class SignalResearchAgent extends Agent {

    public record Signals(List<String> signals) {}

    public Effect<Signals> research(CompanyDetails details) {
        return effects()
            .systemMessage("""
                You are a company signal researcher.
                Use the getSignalsForCompany tool to retrieve intent signals for the company.
                Return exactly the 3 signals provided by the tool, unchanged.
                """)
            .userMessage("Research intent signals for company: " + details.companyName())
            .responseConformsTo(Signals.class)
            .thenReply();
    }

    @FunctionTool(description = "Get intent signals for a company by name. Returns exactly 3 signals describing recent company activity.")
    private List<String> getSignalsForCompany(String companyName) {
        return SignalPool.signalsFor(companyName);
    }
}