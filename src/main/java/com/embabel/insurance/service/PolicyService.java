package com.embabel.insurance.service;

import com.embabel.insurance.dto.response.PolicyResponse;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.repository.CustomerRepository;
import com.embabel.insurance.repository.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 保单查询服务，提供按用户 ID 和保单号查询保单的功能。
 */
@Service
public class PolicyService {

    private static final Logger logger = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyRepository policyRepository;
    private final CustomerRepository customerRepository;

    public PolicyService(PolicyRepository policyRepository, CustomerRepository customerRepository) {
        this.policyRepository = policyRepository;
        this.customerRepository = customerRepository;
    }

    @Transactional(readOnly = true)
    public List<PolicyResponse> getPoliciesByUserId(String userId) {
        logger.info("Fetching policies for user: {}", userId);
        
        Optional<Customer> customerOpt = customerRepository.findByUserId(userId);
        
        if (customerOpt.isEmpty()) {
            logger.warn("Customer not found for user: {}", userId);
            return List.of();
        }
        
        List<Policy> policies = policyRepository.findByCustomerId(customerOpt.get().getId());
        logger.info("Found {} policies for user: {}", policies.size(), userId);
        
        return policies.stream()
                .map(this::toPolicyResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PolicyResponse> getPolicyByNumber(String policyNumber) {
        logger.info("Fetching policy by number: {}", policyNumber);
        
        return policyRepository.findByPolicyNumber(policyNumber)
                .map(this::toPolicyResponse);
    }

    private PolicyResponse toPolicyResponse(Policy policy) {
        return new PolicyResponse(
                policy.getPolicyNumber(),
                policy.getCustomer().getName(),
                policy.getVehicle().getBrand(),
                policy.getVehicle().getModel(),
                policy.getVehicle().getLicensePlate(),
                policy.getCoverageType(),
                policy.getPremiumAmount(),
                policy.getEffectiveDate(),
                policy.getExpirationDate(),
                policy.getStatus().name()
        );
    }
}