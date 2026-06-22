package com.embabel.insurance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "人工审批结果")
public class ApproveQuoteResponse {

    @Schema(description = "报价单 ID")
    private Long quoteId;

    @Schema(description = "审批后状态", example = "APPROVED")
    private String status;

    @Schema(description = "风险评分 (0-100)")
    private double riskScore;

    @Schema(description = "保费金额 (CNY)")
    private double premiumAmount;

    @Schema(description = "客户姓名")
    private String customerName;

    @Schema(description = "车辆型号")
    private String vehicleModel;

    @Schema(description = "结果消息")
    private String message;

    @Schema(description = "审批时间")
    private LocalDateTime approvedAt;

    public ApproveQuoteResponse() {}

    public ApproveQuoteResponse(Long quoteId, String status, double riskScore,
                                double premiumAmount, String customerName,
                                String vehicleModel, String message,
                                LocalDateTime approvedAt) {
        this.quoteId = quoteId;
        this.status = status;
        this.riskScore = riskScore;
        this.premiumAmount = premiumAmount;
        this.customerName = customerName;
        this.vehicleModel = vehicleModel;
        this.message = message;
        this.approvedAt = approvedAt;
    }

    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getRiskScore() { return riskScore; }
    public void setRiskScore(double riskScore) { this.riskScore = riskScore; }
    public double getPremiumAmount() { return premiumAmount; }
    public void setPremiumAmount(double premiumAmount) { this.premiumAmount = premiumAmount; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getVehicleModel() { return vehicleModel; }
    public void setVehicleModel(String vehicleModel) { this.vehicleModel = vehicleModel; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
}
