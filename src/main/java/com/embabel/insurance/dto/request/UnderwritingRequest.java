package com.embabel.insurance.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "核保请求")
public class UnderwritingRequest {

    @Schema(description = "用户 ID", example = "user123")
    private String userId;

    @Schema(description = "用户输入的车险需求描述", example = "Insure my Toyota RAV4 with license ABC123")
    private String userInput;

    public UnderwritingRequest() {}

    public UnderwritingRequest(String userId, String userInput) {
        this.userId = userId;
        this.userInput = userInput;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserInput() { return userInput; }
    public void setUserInput(String userInput) { this.userInput = userInput; }
}