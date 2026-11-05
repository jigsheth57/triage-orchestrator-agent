package com.healthcare.demo.triage;

import com.healthcare.demo.triage.a2a.PharmacistA2AClientTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/triage")
public class TriageController {

    private final ChatClient chatClient;

    public TriageController(ChatClient.Builder chatClientBuilder, PharmacistA2AClientTool pharmacistA2ATool) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                    You are a Clinical Triage Orchestration Agent.
                    When a physician submits a treatment plan involving medications,
                    you MUST ALWAYS delegate safety clearance to the Pharmacist Agent using your A2A tool.
                    Provide a well-structured final summary including:
                    1. Patient & Diagnosis
                    2. Pharmacist Safety Clearance (Approved/Rejected)
                    3. Specific Contraindication Warning (if any)
                    4. Suggested Safe Alternative (if rejected)
                    """)
                .defaultTools(pharmacistA2ATool)
                .build();
    }

    public record TriageRequest(String patientId, String diagnosis, String proposedPrescription) {}

    @PostMapping("/evaluate")
    public String evaluateCase(@RequestBody TriageRequest request) {
        String prompt = String.format(
            "Patient: %s. Diagnosis: %s. Proposed Medication: %s. Please coordinate safety clearance.",
            request.patientId(), request.diagnosis(), request.proposedPrescription()
        );

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}