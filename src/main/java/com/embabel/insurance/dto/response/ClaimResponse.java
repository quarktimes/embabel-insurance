package com.embabel.insurance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "理赔结果")
public class ClaimResponse {

    @Schema(description = "理赔编号")
    private String claimNumber;

    @Schema(description = "关联保单号")
    private String policyNumber;

    @Schema(description = "理赔状态", example = "APPROVED", allowableValues = {"APPROVED", "REJECTED", "INVESTIGATING"})
    private String status;

    @Schema(description = "申请理赔金额")
    private double claimedAmount;

    @Schema(description = "实际赔付金额")
    private double paidAmount;

    @Schema(description = "欺诈评分 (0-100, 越高越可疑)")
    private double fraudScore;

    @Schema(description = "事故描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "结果描述消息")
    private String message;

    public ClaimResponse() {}

    public ClaimResponse(String claimNumber, String policyNumber, String status,
                        double claimedAmount, double paidAmount, double fraudScore,
                        String description, LocalDateTime createdAt, String message) {
        this.claimNumber = claimNumber;
        this.policyNumber = policyNumber;
        this.status = status;
        this.claimedAmount = claimedAmount;
        this.paidAmount = paidAmount;
        this.fraudScore = fraudScore;
        this.description = description;
        this.createdAt = createdAt;
        this.message = message;
    }

    public String getClaimNumber() { return claimNumber; }
    public void setClaimNumber(String claimNumber) { this.claimNumber = claimNumber; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(double claimedAmount) { this.claimedAmount = claimedAmount; }
    public double getPaidAmount() { return paidAmount; }
    public void setPaidAmount(double paidAmount) { this.paidAmount = paidAmount; }
    public double getFraudScore() { return fraudScore; }
    public void setFraudScore(double fraudScore) { this.fraudScore = fraudScore; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}