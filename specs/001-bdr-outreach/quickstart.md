# Quickstart: BDR LinkedIn Outreach Tool

**Feature**: 001-bdr-outreach | **Date**: 2026-03-25

## Prerequisites

- Java 21+
- Maven 3.9+
- A Google API key for Gemini (free tier available at [aistudio.google.com](https://aistudio.google.com))

## 1. Set the API Key

```bash
export GOOGLE_API_KEY=your_api_key_here
```

## 2. Start the Service

```bash
mvn compile exec:java
```

The service starts and logs the local port. Open the UI at:
```
http://localhost:<port>
```

## 3. Single Account Flow

1. Click the **Single Account** tab.
2. Fill in: Company Name, Industry, Website, Target Persona, and optional Notes.
3. Click **Create Account** — the account appears in the list below with status **NEW**.
4. Click **Generate Messages** — a loading indicator shows while the AI process runs.
5. When complete, the status changes to **MESSAGE_READY** and you see:
   - 3 company intent signals
   - 3 persona-aware LinkedIn messages (connection request, follow-up 1, follow-up 2)
6. Click **Mark as Responded** — status becomes **RESPONDED** and a HubSpot ID appears.
7. Click **Get SFDC Payload** — a Salesforce-ready JSON payload appears on the page.

## 4. Bulk Upload Flow

1. Prepare a CSV file with headers: `companyName,industry,website,targetPersona,notes`
2. Click the **Bulk Upload** tab and upload your CSV.
3. All accounts appear in the table with status **NEW**.
4. Click **Generate All** to run AI generation for every row sequentially.
5. Watch statuses update in real time as each row completes.
6. Click any row's **Generate** button to expand the signal + message detail panel.
7. Use the **Responded** and **SFDC** buttons per row as needed.

## 5. Example CSV

```csv
companyName,industry,website,targetPersona,notes
Acme Corp,Software,https://acme.example.com,VP Engineering,Met at KubeCon
Globex Inc,Finance,https://globex.example.com,CFO,Saw their Series B announcement
```

## 6. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Account stays in NEW after Generate | Check `GOOGLE_API_KEY` is set and valid |
| Account shows ERROR status | Click Generate again to retry |
| CSV upload rejected | Check headers match exactly: `companyName,industry,website,targetPersona,notes` |