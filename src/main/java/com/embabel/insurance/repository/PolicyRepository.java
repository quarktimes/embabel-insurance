package com.embabel.insurance.repository;

import com.embabel.insurance.entity.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, Long> {
    @Query("SELECT p FROM Policy p JOIN FETCH p.customer JOIN FETCH p.vehicle WHERE p.policyNumber = :policyNumber")
    Optional<Policy> findByPolicyNumber(@Param("policyNumber") String policyNumber);

    @Query("SELECT p FROM Policy p JOIN FETCH p.customer JOIN FETCH p.vehicle WHERE p.customer.id = :customerId")
    List<Policy> findByCustomerId(@Param("customerId") Long customerId);

    List<Policy> findByStatus(Policy.PolicyStatus status);
}