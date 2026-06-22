package com.embabel.insurance.dto;

public class VehicleInfo {
    private String model;
    private String brand;
    private String licensePlate;

    public VehicleInfo() {}

    public VehicleInfo(String model, String brand, String licensePlate) {
        this.model = model;
        this.brand = brand;
        this.licensePlate = licensePlate;
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }
}