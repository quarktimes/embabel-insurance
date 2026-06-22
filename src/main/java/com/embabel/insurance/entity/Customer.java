package com.embabel.insurance.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Column(nullable = false)
    private int drivingExperienceYears;

    @Column(nullable = false)
    private int accidentCount;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
    private List<Vehicle> vehicles = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
    private List<Policy> policies = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
    private List<Quote> quotes = new ArrayList<>();

    public Customer() {}

    public Customer(String userId, String name, LocalDate dateOfBirth, 
                    int drivingExperienceYears, int accidentCount, 
                    String email, String phone) {
        this.userId = userId;
        this.name = name;
        this.dateOfBirth = dateOfBirth;
        this.drivingExperienceYears = drivingExperienceYears;
        this.accidentCount = accidentCount;
        this.email = email;
        this.phone = phone;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public int getDrivingExperienceYears() { return drivingExperienceYears; }
    public void setDrivingExperienceYears(int drivingExperienceYears) { this.drivingExperienceYears = drivingExperienceYears; }
    public int getAccidentCount() { return accidentCount; }
    public void setAccidentCount(int accidentCount) { this.accidentCount = accidentCount; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public List<Vehicle> getVehicles() { return vehicles; }
    public void setVehicles(List<Vehicle> vehicles) { this.vehicles = vehicles; }
    public List<Policy> getPolicies() { return policies; }
    public void setPolicies(List<Policy> policies) { this.policies = policies; }
    public List<Quote> getQuotes() { return quotes; }
    public void setQuotes(List<Quote> quotes) { this.quotes = quotes; }

    public int getAge() {
        return java.time.Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    /**
     * 创建一个"查找失败"的 sentinel 对象，用于占位 Blackboard 上的 Customer 类型。
     * <p>当数据库中查不到客户时，lookupCustomer 返回此对象而非 null，
     * 确保 embabel UTILITY 规划器能匹配 assessRisk(Customer, Vehicle, context) 的签名，
     * 避免因类型缺失导致进程 STUCK。
     *
     * @see #isLookupFailed(Customer)
     */
    public static Customer lookupFailed() {
        return new Customer("__sentinel__", "SENTINEL",
                LocalDate.of(1970, 1, 1), 0, 0,
                "sentinel@placeholder", "0000000000");
    }

    /**
     * 检查是否为 lookupFailed() 创建的 sentinel 占位对象。
     */
    public static boolean isLookupFailed(Customer customer) {
        return customer == null || "__sentinel__".equals(customer.userId);
    }
}