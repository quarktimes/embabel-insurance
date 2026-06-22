package com.embabel.insurance.repository;

import com.embabel.insurance.entity.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClaimRepository extends JpaRepository<Claim, Long> {
    Optional<Claim> findByClaimNumber(String claimNumber);
    List<Claim> findByPolicyId(Long policyId);
    List<Claim> findByStatus(Claim.ClaimStatus status);
}