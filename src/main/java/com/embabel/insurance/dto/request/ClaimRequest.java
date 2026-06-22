package com.embabel.insurance.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "理赔请求")
public class ClaimRequest {

    @NotBlank(message = "Policy number is required")
    @Schema(description = "保单号", example = "POL-1234567890")
    private String policyNumber;

    @NotBlank(message = "Description is required")
    @Schema(description = "事故描述", example = "Rear-ended at a traffic light, bumper damaged")
    private String description;

    @NotNull(message = "Claimed amount is required")
    @Positive(message = "Claimed amount must be positive")
    @Schema(description = "理赔金额", example = "15000.0")
    private Double claimedAmount;

    public ClaimRequest() {}

    public ClaimRequest(String policyNumber, String description, Double claimedAmount) {
        this.policyNumber = policyNumber;
        this.description = description;
        this.claimedAmount = claimedAmount;
    }

    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Double getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(Double claimedAmount) { this.claimedAmount = claimedAmount; }
}