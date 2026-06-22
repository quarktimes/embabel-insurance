package com.embabel.insurance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "核保结果")
public class UnderwritingResponse {

    @Schema(description = "报价单 ID")
    private Long quoteId;

    @Schema(description = "核保状态", example = "APPROVED", allowableValues = {"APPROVED", "REFERRED", "DECLINED", "PENDING"})
    private String status;

    @Schema(description = "风险评分 (0-100)")
    private double riskScore;

    @Schema(description = "保费金额 (CNY)")
    private double premiumAmount;

    @Schema(description = "结果描述消息")
    private String message;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public UnderwritingResponse() {}

    public UnderwritingResponse(Long quoteId, String status, double riskScore, 
                                double premiumAmount, String message, 
                                LocalDateTime createdAt) {
        this.quoteId = quoteId;
        this.status = status;
        this.riskScore = riskScore;
        this.premiumAmount = premiumAmount;
        this.message = message;
        this.createdAt = createdAt;
    }

    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getRiskScore() { return riskScore; }
    public void setRiskScore(double riskScore) { this.riskScore = riskScore; }
    public double getPremiumAmount() { return premiumAmount; }
    public void setPremiumAmount(double premiumAmount) { this.premiumAmount = premiumAmount; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}