package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.AgentRole;
import akka.javasdk.annotations.Component;
import com.example.domain.CompanyDetails;
import com.example.domain.OutreachMessage;

import java.util.List;

@Component(id = "message-generation")
@AgentRole("LinkedIn outreach message writer. Given company details, intent signals, and a target persona, generates exactly 3 LinkedIn messages: a connection request, a first follow-up, and a second follow-up.")
public class MessageGenerationAgent extends Agent {

    public record GenerateRequest(CompanyDetails details, List<String> signals) {}

    /** LLM response DTO — uses String for messageType to avoid enum schema issues. */
    public record MessageItem(String messageType, String body) {
        public OutreachMessage toOutreachMessage() {
            return new OutreachMessage(OutreachMessage.MessageType.valueOf(messageType), body);
        }
    }

    public record Messages(List<MessageItem> messages) {
        public List<OutreachMessage> toOutreachMessages() {
            return messages.stream().map(MessageItem::toOutreachMessage).toList();
        }
    }

    public Effect<Messages> generate(GenerateRequest request) {
        var details = request.details();
        var signals = String.join("\n- ", request.signals());

        return effects()
            .systemMessage("""
                You are a LinkedIn outreach message writer.
                Generate exactly 3 LinkedIn messages tailored to the target persona.
                Each message MUST reference one or more of the company's intent signals to feel grounded and relevant.
                The tone and focus MUST reflect what matters to the specific persona.
                Generate exactly: 1 CONNECTION_REQUEST, 1 FOLLOW_UP_1, 1 FOLLOW_UP_2.
                Keep each message under 300 characters for LinkedIn's connection request limit.
                """)
            .userMessage("""
                Company: %s
                Industry: %s
                Website: %s
                Target Persona: %s
                Notes: %s
                Intent Signals:
                - %s

                Write 3 LinkedIn outreach messages targeting the %s at %s.
                """.formatted(
                    details.companyName(),
                    details.industry(),
                    details.website(),
                    details.targetPersona(),
                    details.notes() != null ? details.notes() : "",
                    signals,
                    details.targetPersona(),
                    details.companyName()
                ))
            .responseConformsTo(Messages.class)
            .thenReply();
    }
}