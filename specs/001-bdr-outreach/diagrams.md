# Diagrams: BDR LinkedIn Outreach Tool

**Feature**: 001-bdr-outreach | **Plan**: [plan.md](plan.md)

## Color Conventions

| Flow Type | Color | Hex |
|-----------|-------|-----|
| Submission / happy-path | blue | `#2196F3` |
| Validation / processing | amber | `#FF9800` |
| Routing / delivery / success | green | `#4CAF50` |
| Error / failure | red | `#F44336` / `#B71C1C` |

---

## 1. Component Dependencies

```mermaid
flowchart TD
    subgraph ext["External"]
        Browser([Browser / BDR])
    end

    subgraph api["API Layer"]
        EP[OutreachEndpoint]
    end

    subgraph application["Application Layer"]
        OGW[OutreachGenerationWorkflow]
        BGW[BulkGenerationWorkflow]
        SRA[SignalResearchAgent]
        MGA[MessageGenerationAgent]
        ASV[AccountsByStatusView]
        HSC[HubSpotSyncConsumer]
    end

    subgraph domain["Domain & Stubs"]
        SP[SignalPool]
        HS[HubSpotService]
    end

    Browser -.->|"① REST / SSE"| EP
    EP -->|"② create / respond / sfdc"| OGW
    EP -->|"③ getAllAccounts"| ASV
    EP -->|"④ start"| BGW
    OGW -->|"⑤ research"| SRA
    OGW -->|"⑥ generate"| MGA
    BGW -->|"⑦ delegate per account"| OGW
    OGW -->|"⑧ notifyDone"| BGW
    OGW -->|"⑨ state updates async"| ASV
    OGW -->|"⑩ state updates async"| HSC
    SRA -->|"⑪ signalsFor"| SP
    HSC -->|"⑫ syncAccount"| HS

    linkStyle 0 stroke:#2196F3,stroke-width:2px
    linkStyle 1,2,3 stroke:#2196F3,stroke-width:2px
    linkStyle 4,5 stroke:#FF9800,stroke-width:2px
    linkStyle 6 stroke:#FF9800,stroke-width:2px
    linkStyle 7,8,9 stroke:#4CAF50,stroke-width:2px
    linkStyle 10 stroke:#FF9800,stroke-width:2px
    linkStyle 11 stroke:#4CAF50,stroke-width:2px

    style Browser stroke-dasharray:5 5,stroke:#999,fill:#f5f5f5,color:#333
```

---

## 2. Sequence Diagram

```mermaid
sequenceDiagram
    participant BDR as BDR
    participant EP  as OutreachEndpoint
    participant OGW as OutreachGenerationWorkflow
    participant SRA as SignalResearchAgent
    participant MGA as MessageGenerationAgent
    participant ASV as AccountsByStatusView
    participant HSC as HubSpotSyncConsumer

    rect rgb(33,150,243)
        Note over BDR,ASV: Account Creation Flow
        BDR ->> EP: POST /accounts {companyName, industry, website, targetPersona}
        EP ->> OGW: create(accountId, details)
        OGW -->> EP: Done
        EP -->> BDR: 201 {accountId, status: NEW}
        OGW -->> ASV: state update async
    end

    rect rgb(255,152,0)
        Note over BDR,ASV: AI Generation Flow
        BDR ->> EP: POST /accounts/{id}/generate
        EP ->> OGW: startGeneration()
        OGW -->> EP: Done
        EP -->> BDR: 200 {accountId, status: NEW}
        OGW ->> SRA: research(companyDetails)
        SRA -->> OGW: ["Hiring AI engineers", ...]
        OGW ->> MGA: generate(details, signals)
        MGA -->> OGW: Messages(connectionRequest, followUp1, followUp2)
        OGW -->> OGW: status = MESSAGE_READY, signals + messages stored
        OGW -->> ASV: state update async
    end

    rect rgb(33,150,243)
        Note over BDR,ASV: SSE Real-time Update
        BDR ->> EP: GET /events (SSE stream open)
        EP ->> ASV: getAllAccounts() every 2s
        ASV -->> EP: AccountEntries {status: MESSAGE_READY}
        EP -->> BDR: data: {entries: [...status: MESSAGE_READY...]}
    end

    rect rgb(76,175,80)
        Note over BDR,HSC: Responded & HubSpot Sync Flow
        BDR ->> EP: POST /accounts/{id}/respond
        EP ->> OGW: markAsResponded()
        OGW -->> EP: Done
        EP -->> BDR: 200 {accountId, status: RESPONDED}
        OGW -->> HSC: state update async
        HSC ->> HSC: HubSpotService.syncAccount(companyName) → hs_123456
        OGW -->> ASV: state update async
    end

    rect rgb(183,28,28)
        Note over OGW,ASV: Error Path — generation fails after 1 retry
        OGW ->> SRA: research(companyDetails) [retry]
        SRA -->> OGW: AgentException
        OGW -->> OGW: status = ERROR
        OGW -->> ASV: state update async
    end
```

---

## 3. Workflow State Machines

### 3.1 OutreachGenerationWorkflow

```mermaid
flowchart TD
    START([START]) --> RESEARCH_SIGNALS

    RESEARCH_SIGNALS[researchSignalsStep\nCall SignalResearchAgent]
    GENERATE_MESSAGES[generateMessagesStep\nCall MessageGenerationAgent]
    UPDATE_ACCOUNT[updateAccountStep\nrecordGenerationResult]
    ERROR_HANDLER[errorStep\nmarkGenerationFailed]
    COMPLETED([COMPLETED])
    FAILED([FAILED])

    RESEARCH_SIGNALS -->|"success"| GENERATE_MESSAGES
    RESEARCH_SIGNALS -->|"fails after 1 retry"| ERROR_HANDLER

    GENERATE_MESSAGES -->|"success"| UPDATE_ACCOUNT
    GENERATE_MESSAGES -->|"fails after 1 retry"| ERROR_HANDLER

    UPDATE_ACCOUNT -->|"success"| COMPLETED
    UPDATE_ACCOUNT -->|"fails"| ERROR_HANDLER

    ERROR_HANDLER --> FAILED

    style START          fill:#2196F3,color:#fff
    style RESEARCH_SIGNALS fill:#FF9800,color:#fff
    style GENERATE_MESSAGES fill:#FF9800,color:#fff
    style UPDATE_ACCOUNT fill:#FF9800,color:#fff
    style ERROR_HANDLER  fill:#F44336,color:#fff
    style COMPLETED      fill:#4CAF50,color:#fff
    style FAILED         fill:#B71C1C,color:#fff
```

### 3.2 BulkGenerationWorkflow

```mermaid
flowchart TD
    START([START\naccounts list, index=0]) --> DELEGATING

    DELEGATING[delegateStep\nstart OutreachGenerationWorkflow\nfor accounts index\npassing bulkJobId]
    AWAITING[AWAITING COMPLETION\npaused, waiting for\nOGW notifyDone command]
    COMPLETED([COMPLETED\nall accounts processed])

    DELEGATING -->|"OGW started"| AWAITING
    AWAITING -->|"notifyDone: more accounts remain"| DELEGATING
    AWAITING -->|"notifyDone: no more accounts"| COMPLETED

    style START      fill:#2196F3,color:#fff
    style DELEGATING fill:#FF9800,color:#fff
    style AWAITING   fill:#2196F3,color:#fff
    style COMPLETED  fill:#4CAF50,color:#fff
```
