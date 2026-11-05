package com.healthcare.demo.triage.a2a;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Service
public class PharmacistA2AClientTool {

    private final RestClient restClient;
    private final String pharmacistBaseUrl;

    public PharmacistA2AClientTool(@Value("${a2a.pharmacist-agent-url}") String pharmacistBaseUrl) {
        this.pharmacistBaseUrl = pharmacistBaseUrl;
        this.restClient = RestClient.builder().baseUrl(pharmacistBaseUrl).build();
    }

    @Tool(description = "Consult the specialized Pharmacist Agent via A2A protocol to review prescription safety and allergy contraindications.")
    public String consultPharmacistAgent(
            @ToolParam(description = "Patient ID, e.g. P101") String patientId,
            @ToolParam(description = "Proposed medication and dosage") String proposedMedication,
            @ToolParam(description = "Clinical diagnosis reason") String diagnosis) {

        // 1. A2A Dynamic Discovery check (fetches agent card)
        Map<?, ?> agentCard = restClient.get()
                .uri("/.well-known/agent-card.json")
                .retrieve()
                .body(Map.class);

        System.out.println("[A2A Discovery] Connected to Agent: " + agentCard.get("name"));

        // 2. Dispatch task via A2A JSON-RPC
        Map<String, Object> rpcPayload = Map.of(
            "jsonrpc", "2.0",
            "method", "sendMessage",
            "params", Map.of(
                "query", String.format("Validate safety for patient '%s'. Proposed Rx: '%s'. Diagnosis: '%s'.", 
                                       patientId, proposedMedication, diagnosis)
            ),
            "id", UUID.randomUUID().toString()
        );

        Map<?, ?> response = restClient.post()
                .uri("/a2a")
                .body(rpcPayload)
                .retrieve()
                .body(Map.class);

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        return (String) result.get("artifact");
    }
}