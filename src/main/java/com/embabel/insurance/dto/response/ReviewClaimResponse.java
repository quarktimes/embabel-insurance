package com.embabel.insurance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 理赔审核结果响应 DTO，包含审核后的状态、赔付金额等信息。
 */
@Schema(description = "理赔审核结果")
public class ReviewClaimResponse {

    @Schema(description = "理赔单 ID")
    private Long claimId;

    @Schema(description = "理赔编号")
    private String claimNumber;

    @Schema(description = "审核后状态", example = "APPROVED")
    private String status;

    @Schema(description = "申请理赔金额")
    private double claimedAmount;

    @Schema(description = "最终赔付金额")
    private double approvedAmount;

    @Schema(description = "欺诈评分")
    private double fraudScore;

    @Schema(description = "审核员备注")
    private String reviewerNotes;

    @Schema(description = "审核时间")
    private LocalDateTime reviewedAt;

    @Schema(description = "结果描述消息")
    private String message;

    public ReviewClaimResponse() {}

    public ReviewClaimResponse(Long claimId, String claimNumber, String status,
                               double claimedAmount, double approvedAmount,
                               double fraudScore, String reviewerNotes,
                               LocalDateTime reviewedAt, String message) {
        this.claimId = claimId;
        this.claimNumber = claimNumber;
        this.status = status;
        this.claimedAmount = claimedAmount;
        this.approvedAmount = approvedAmount;
        this.fraudScore = fraudScore;
        this.reviewerNotes = reviewerNotes;
        this.reviewedAt = reviewedAt;
        this.message = message;
    }

    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public String getClaimNumber() { return claimNumber; }
    public void setClaimNumber(String claimNumber) { this.claimNumber = claimNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(double claimedAmount) { this.claimedAmount = claimedAmount; }
    public double getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(double approvedAmount) { this.approvedAmount = approvedAmount; }
    public double getFraudScore() { return fraudScore; }
    public void setFraudScore(double fraudScore) { this.fraudScore = fraudScore; }
    public String getReviewerNotes() { return reviewerNotes; }
    public void setReviewerNotes(String reviewerNotes) { this.reviewerNotes = reviewerNotes; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
