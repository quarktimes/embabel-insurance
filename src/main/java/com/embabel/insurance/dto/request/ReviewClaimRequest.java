package com.embabel.insurance.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 理赔人工审核请求 DTO，审核员据此批准或拒绝理赔。
 */
@Schema(description = "理赔人工审核请求")
public class ReviewClaimRequest {

    @Schema(description = "审核决定: APPROVED 或 DENIED", example = "APPROVED", allowableValues = {"APPROVED", "DENIED"})
    private String decision;

    @Schema(description = "审核员备注", example = "Verified with police report. Legitimate claim.")
    private String reviewerNotes;

    @Schema(description = "可选的赔付金额覆盖（不填则使用申请金额的赔付上限）", example = "8000.0")
    private Double approvedAmount;

    public ReviewClaimRequest() {}

    public ReviewClaimRequest(String decision, String reviewerNotes, Double approvedAmount) {
        this.decision = decision;
        this.reviewerNotes = reviewerNotes;
        this.approvedAmount = approvedAmount;
    }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReviewerNotes() { return reviewerNotes; }
    public void setReviewerNotes(String reviewerNotes) { this.reviewerNotes = reviewerNotes; }
    public Double getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(Double approvedAmount) { this.approvedAmount = approvedAmount; }
}
