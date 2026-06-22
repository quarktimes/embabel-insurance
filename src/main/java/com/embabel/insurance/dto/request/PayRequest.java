package com.embabel.insurance.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "支付与签发保单请求")
public class PayRequest {

    @Schema(description = "报价单 ID", example = "1")
    private Long quoteId;

    @Schema(description = "支付方式", example = "ALIPAY", allowableValues = {"WECHAT_PAY", "ALIPAY", "CREDIT_CARD"})
    private String paymentMethod;

    public PayRequest() {}

    public PayRequest(Long quoteId, String paymentMethod) {
        this.quoteId = quoteId;
        this.paymentMethod = paymentMethod;
    }

    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
}
