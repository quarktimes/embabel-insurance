package com.embabel.insurance.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String licensePlate;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String brand;

    @Column(name = "vehicle_year", nullable = false)
    private int year;

    @Column(name = "vehicle_value", nullable = false)
    private double vehicleValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    public Vehicle() {}

    public Vehicle(String licensePlate, String model, String brand, 
                   int year, double vehicleValue, Customer customer) {
        this.licensePlate = licensePlate;
        this.model = model;
        this.brand = brand;
        this.year = year;
        this.vehicleValue = vehicleValue;
        this.customer = customer;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }
    public double getVehicleValue() { return vehicleValue; }
    public void setVehicleValue(double vehicleValue) { this.vehicleValue = vehicleValue; }
    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }

    /** 缓存的 sentinel Customer，避免每次 lookupFailed() 都创建新实例 */
    private static Customer sentinelCustomer = null;

    /**
     * 创建一个"查找失败"的 sentinel 对象，用于占位 Blackboard 上的 Vehicle 类型。
     * <p>当数据库中查不到车辆时，lookupVehicle 返回此对象而非 null，
     * 确保 embabel UTILITY 规划器能匹配 assessRisk(Customer, Vehicle, context) 的签名，
     * 避免因类型缺失导致进程 STUCK。
     *
     * @see #isLookupFailed(Vehicle)
     */
    public static Vehicle lookupFailed() {
        if (sentinelCustomer == null) {
            sentinelCustomer = Customer.lookupFailed();
        }
        return new Vehicle("__sentinel__", "SENTINEL", "SENTINEL",
                1900, 0.0, sentinelCustomer);
    }

    /**
     * 检查是否为 lookupFailed() 创建的 sentinel 占位对象。
     */
    public static boolean isLookupFailed(Vehicle vehicle) {
        return vehicle == null || "__sentinel__".equals(vehicle.licensePlate);
    }
}