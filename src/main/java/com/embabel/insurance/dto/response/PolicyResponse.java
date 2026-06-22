package com.embabel.insurance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "保单信息")
public class PolicyResponse {

    @Schema(description = "保单号")
    private String policyNumber;

    @Schema(description = "客户姓名")
    private String customerName;

    @Schema(description = "车辆品牌")
    private String vehicleBrand;

    @Schema(description = "车辆型号")
    private String vehicleModel;

    @Schema(description = "车牌号")
    private String vehicleLicensePlate;

    @Schema(description = "险种类型", example = "COMPREHENSIVE")
    private String coverageType;

    @Schema(description = "保费金额 (CNY)")
    private double premiumAmount;

    @Schema(description = "生效日期")
    private LocalDateTime effectiveDate;

    @Schema(description = "到期日期")
    private LocalDateTime expirationDate;

    @Schema(description = "保单状态", example = "ACTIVE", allowableValues = {"ACTIVE", "EXPIRED", "CANCELLED"})
    private String status;

    public PolicyResponse() {}

    public PolicyResponse(String policyNumber, String customerName, String vehicleBrand,
                         String vehicleModel, String vehicleLicensePlate, String coverageType,
                         double premiumAmount, LocalDateTime effectiveDate, 
                         LocalDateTime expirationDate, String status) {
        this.policyNumber = policyNumber;
        this.customerName = customerName;
        this.vehicleBrand = vehicleBrand;
        this.vehicleModel = vehicleModel;
        this.vehicleLicensePlate = vehicleLicensePlate;
        this.coverageType = coverageType;
        this.premiumAmount = premiumAmount;
        this.effectiveDate = effectiveDate;
        this.expirationDate = expirationDate;
        this.status = status;
    }

    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getVehicleBrand() { return vehicleBrand; }
    public void setVehicleBrand(String vehicleBrand) { this.vehicleBrand = vehicleBrand; }
    public String getVehicleModel() { return vehicleModel; }
    public void setVehicleModel(String vehicleModel) { this.vehicleModel = vehicleModel; }
    public String getVehicleLicensePlate() { return vehicleLicensePlate; }
    public void setVehicleLicensePlate(String vehicleLicensePlate) { this.vehicleLicensePlate = vehicleLicensePlate; }
    public String getCoverageType() { return coverageType; }
    public void setCoverageType(String coverageType) { this.coverageType = coverageType; }
    public double getPremiumAmount() { return premiumAmount; }
    public void setPremiumAmount(double premiumAmount) { this.premiumAmount = premiumAmount; }
    public LocalDateTime getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDateTime effectiveDate) { this.effectiveDate = effectiveDate; }
    public LocalDateTime getExpirationDate() { return expirationDate; }
    public void setExpirationDate(LocalDateTime expirationDate) { this.expirationDate = expirationDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}