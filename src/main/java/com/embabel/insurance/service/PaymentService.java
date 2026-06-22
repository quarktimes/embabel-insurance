package com.embabel.insurance.service;

import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.entity.Quote;
import com.embabel.insurance.repository.PolicyRepository;
import com.embabel.insurance.repository.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 支付与保单签发服务。
 *
 * <p>对已批准的报价单执行模拟支付，生成保单号并创建正式保单。
 * 校验报价单必须为 APPROVED 状态且未过期。
 */
@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final QuoteRepository quoteRepository;
    private final PolicyRepository policyRepository;

    public PaymentService(QuoteRepository quoteRepository, PolicyRepository policyRepository) {
        this.quoteRepository = quoteRepository;
        this.policyRepository = policyRepository;
    }

    /**
     * 支付保费并签发正式保单。
     *
     * <p>校验报价单状态为 APPROVED 且未过期后，生成保单号并创建一年期有效保单。
     *
     * @param quoteId       已批准的报价单 ID
     * @param paymentMethod 支付方式（如 WECHAT_PAY、ALIPAY、CREDIT_CARD）
     * @return 签发的保单
     */
    @Transactional
    public Policy payAndIssuePolicy(Long quoteId, String paymentMethod) {
        // 1. 查找报价单
        Quote quote = quoteRepository.findById(quoteId)
                .orElseThrow(() -> new RuntimeException("Quote not found: " + quoteId));

        // 2. 校验报价单状态必须是 APPROVED
        if (quote.getStatus() != Quote.QuoteStatus.APPROVED) {
            throw new RuntimeException(
                    "Quote is not approved. Current status: " + quote.getStatus());
        }

        // 3. 校验报价单是否过期
        if (quote.getExpiresAt() != null && quote.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Quote has expired at: " + quote.getExpiresAt());
        }

        // 4. 模拟支付处理
        logger.info("Processing payment of ¥{} via {} for quote #{}",
                quote.getPremiumAmount(), paymentMethod, quoteId);


        // 5. 生成保单号：POL-{时间戳}-{随机串}
        String policyNumber = "POL-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // 6. 创建保单（默认一年有效期）
        LocalDateTime now = LocalDateTime.now();
        Policy policy = new Policy(
                policyNumber,
                quote.getCustomer(),
                quote.getVehicle(),
                quote.getCoverageType(),
                quote.getPremiumAmount(),
                now,                           // effectiveDate
                now.plusYears(1),              // expirationDate
                Policy.PolicyStatus.ACTIVE
        );

        Policy savedPolicy = policyRepository.save(policy);

        logger.info("Policy issued: policyNumber={}, premium=¥{}, effectiveDate={}, expirationDate={}",
                savedPolicy.getPolicyNumber(),
                savedPolicy.getPremiumAmount(),
                savedPolicy.getEffectiveDate(),
                savedPolicy.getExpirationDate());

        return savedPolicy;
    }
}
