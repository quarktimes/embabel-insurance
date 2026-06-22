package com.embabel.insurance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "支付与签发保单结果")
public class PayResponse {

    @Schema(description = "保单号", example = "POL-1718000000000-AB12CD")
    private String policyNumber;

    @Schema(description = "保费金额 (CNY)", example = "5000.00")
    private double premiumAmount;

    @Schema(description = "保单状态", example = "ACTIVE")
    private String status;

    @Schema(description = "保单生效日期")
    private LocalDateTime effectiveDate;

    @Schema(description = "保单到期日期")
    private LocalDateTime expirationDate;

    @Schema(description = "结果消息")
    private String message;

    public PayResponse() {}

    public PayResponse(String policyNumber, double premiumAmount, String status,
                       LocalDateTime effectiveDate, LocalDateTime expirationDate, String message) {
        this.policyNumber = policyNumber;
        this.premiumAmount = premiumAmount;
        this.status = status;
        this.effectiveDate = effectiveDate;
        this.expirationDate = expirationDate;
        this.message = message;
    }

    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public double getPremiumAmount() { return premiumAmount; }
    public void setPremiumAmount(double premiumAmount) { this.premiumAmount = premiumAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDateTime effectiveDate) { this.effectiveDate = effectiveDate; }
    public LocalDateTime getExpirationDate() { return expirationDate; }
    public void setExpirationDate(LocalDateTime expirationDate) { this.expirationDate = expirationDate; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
