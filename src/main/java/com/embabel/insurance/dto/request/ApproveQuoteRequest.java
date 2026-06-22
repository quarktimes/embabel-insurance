package com.embabel.insurance.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "人工审批 REFERRED 报价单的请求")
public class ApproveQuoteRequest {

    @Schema(description = "保费金额覆盖 (CNY)。不传则使用系统计算的原保费。",
            example = "4500.00", nullable = true)
    private Double premiumAmount;

    @Schema(description = "审批备注", example = "人工审核通过：已确认风险可控。")
    private String comment;

    public ApproveQuoteRequest() {}

    public Double getPremiumAmount() { return premiumAmount; }
    public void setPremiumAmount(Double premiumAmount) { this.premiumAmount = premiumAmount; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
