package com.embabel.insurance.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "claims")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String claimNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ClaimStatus status;

    @Column(nullable = false)
    private double claimedAmount;

    @Column
    private double paidAmount;

    @Column(nullable = false)
    private double fraudScore;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private String processId;

    public enum ClaimStatus {
        PENDING,
        INVESTIGATING,
        APPROVED,
        DENIED,
        PAID
    }

    public Claim() {}

    public Claim(String claimNumber, Policy policy, ClaimStatus status, 
                 double claimedAmount, double fraudScore, String description,
                 String processId) {
        this.claimNumber = claimNumber;
        this.policy = policy;
        this.status = status;
        this.claimedAmount = claimedAmount;
        this.fraudScore = fraudScore;
        this.description = description;
        this.createdAt = LocalDateTime.now();
        this.processId = processId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClaimNumber() { return claimNumber; }
    public void setClaimNumber(String claimNumber) { this.claimNumber = claimNumber; }
    public Policy getPolicy() { return policy; }
    public void setPolicy(Policy policy) { this.policy = policy; }
    public ClaimStatus getStatus() { return status; }
    public void setStatus(ClaimStatus status) { this.status = status; }
    public double getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(double claimedAmount) { this.claimedAmount = claimedAmount; }
    public double getPaidAmount() { return paidAmount; }
    public void setPaidAmount(double paidAmount) { this.paidAmount = paidAmount; }
    public double getFraudScore() { return fraudScore; }
    public void setFraudScore(double fraudScore) { this.fraudScore = fraudScore; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getProcessId() { return processId; }
    public void setProcessId(String processId) { this.processId = processId; }
}