package com.embabel.insurance.controller;

import com.embabel.insurance.agent.ClaimsAgent;
import com.embabel.insurance.dto.request.ApproveQuoteRequest;
import com.embabel.insurance.dto.request.ClaimRequest;
import com.embabel.insurance.dto.request.PayRequest;
import com.embabel.insurance.dto.request.ReviewClaimRequest;
import com.embabel.insurance.dto.request.UnderwritingRequest;
import com.embabel.insurance.dto.response.ApproveQuoteResponse;
import com.embabel.insurance.dto.response.ClaimResponse;
import com.embabel.insurance.dto.response.PayResponse;
import com.embabel.insurance.dto.response.PolicyResponse;
import com.embabel.insurance.dto.response.ReviewClaimResponse;
import com.embabel.insurance.dto.response.UnderwritingResponse;
import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.repository.PolicyRepository;
import com.embabel.insurance.service.AgentService;
import com.embabel.insurance.service.PaymentService;
import com.embabel.insurance.service.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 保险核心业务接口，涵盖核保、理赔、保单查询、人工审批和支付签发等完整流程。
 */
@RestController
@RequestMapping("/api/insurance")
@Tag(name = "Insurance", description = "核保与理赔核心接口")
@SecurityRequirement(name = "basicAuth")
public class InsuranceController {

    private static final Logger logger = LoggerFactory.getLogger(InsuranceController.class);

    private final AgentService agentService;
    private final PolicyService policyService;
    private final PaymentService paymentService;
    private final PolicyRepository policyRepository;

    public InsuranceController(AgentService agentService, PolicyService policyService,
                              PaymentService paymentService,
                              PolicyRepository policyRepository) {
        this.agentService = agentService;
        this.policyService = policyService;
        this.paymentService = paymentService;
        this.policyRepository = policyRepository;
    }

    @Operation(summary = "提交核保申请", description = "根据用户输入的车险信息进行智能核保，返回审批结果（批准/转人工/拒绝）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "核保完成",
                    content = @Content(schema = @Schema(implementation = UnderwritingResponse.class))),
            @ApiResponse(responseCode = "422", description = "输入包含未授权指令"),
            @ApiResponse(responseCode = "500", description = "核保处理失败")
    })
    @PostMapping("/underwrite")
    @PreAuthorize("hasAuthority('underwriting:write')")
    public ResponseEntity<?> underwrite(
            @RequestBody @Schema(description = "核保请求", implementation = UnderwritingRequest.class)
            UnderwritingRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            // 优先使用请求体中的业务 userId，回退到认证用户名
            String userId = (request.getUserId() != null && !request.getUserId().isBlank())
                    ? request.getUserId()
                    : auth.getName();

            logger.info("Processing underwriting request for business userId: {} (auth: {})", userId, auth.getName());

            if (agentService.containsUnauthorizedCommand(request.getUserInput())) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("Input contains unauthorized commands");
            }
            logger.info("Processing underwriting request : {}", request.getUserInput());
            UnderwritingResponse response = agentService.processUnderwriting(userId, request.getUserInput());

            if (response == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Failed to process underwriting request");
            }

            // @State 路由返回的错误状态 — 返回 422 并附带错误消息
            if ("ERROR".equals(response.getStatus())) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(response.getMessage());
            }

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "提交理赔申请", description = "根据保单号和事故描述提交理赔申请，通过 ClaimsAgent 进行智能欺诈检测和自动路由")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "理赔处理完成",
                    content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
            @ApiResponse(responseCode = "404", description = "保单未找到"),
            @ApiResponse(responseCode = "500", description = "理赔处理失败")
    })
    @PostMapping("/claims")
    @PreAuthorize("hasAuthority('claims:write')")
    public ResponseEntity<?> fileClaim(@RequestBody @Valid ClaimRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();

        logger.info("Processing claim request for user: {}, policy: {}", userId, request.getPolicyNumber());

        // 校验保单是否存在
        Optional<Policy> policyOpt = policyRepository.findByPolicyNumber(request.getPolicyNumber());
        if (policyOpt.isEmpty()) {
            throw new RuntimeException("Policy not found: " + request.getPolicyNumber());
        }

        // 构建 ClaimsAgent 所需的结构化输入
        String userInput = String.format(
                "policy=%s description=%s amount=%.0f",
                request.getPolicyNumber(),
                request.getDescription(),
                request.getClaimedAmount()
        );

        // 通过 Agent 处理（Utility 规划器 → @State 路由）
        ClaimsAgent.ClaimResult result = agentService.processClaim(userInput);

        // @State 路由返回的错误状态 — 返回 422 并附带错误消息
        if ("ERROR".equals(result.claimStatus())) {
            throw new IllegalArgumentException(result.message());
        }

        double paidAmount = "APPROVED".equals(result.claimStatus()) ? result.approvedAmount() : 0.0;

        ClaimResponse response = new ClaimResponse(
                result.claimNumber(),
                request.getPolicyNumber(),
                result.claimStatus(),
                request.getClaimedAmount(),
                paidAmount,
                result.fraudScore(),
                    request.getDescription(),
                    java.time.LocalDateTime.now(),
                    result.message()
            );

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "获取保单列表", description = "获取指定用户的所有保单信息。不传 userId 时默认为当前认证用户。")
    @ApiResponse(responseCode = "200", description = "保单列表",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class)))
    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('policies:read')")
    public ResponseEntity<?> getPolicies(
            @Parameter(description = "用户 ID（可选），不传则使用当前认证用户", example = "low-risk-user")
            @RequestParam(required = false) String userId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String effectiveUserId = (userId != null && !userId.isBlank()) ? userId : auth.getName();

        logger.info("Fetching policies for user: {} (auth: {})", effectiveUserId, auth.getName());

        List<PolicyResponse> policies = policyService.getPoliciesByUserId(effectiveUserId);

        return ResponseEntity.ok(policies);
    }

    @Operation(summary = "获取单个保单", description = "根据保单号获取保单详细信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "保单详情",
                    content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
            @ApiResponse(responseCode = "404", description = "保单未找到")
    })
    @GetMapping("/policies/{policyNumber}")
    @PreAuthorize("hasAuthority('policies:read')")
    public ResponseEntity<?> getPolicy(
            @Parameter(description = "保单号", example = "POL-1234567890")
            @PathVariable String policyNumber) {
        logger.info("Fetching policy: {}", policyNumber);

            Optional<PolicyResponse> policy = policyService.getPolicyByNumber(policyNumber);

            if (policy.isPresent()) {
                return ResponseEntity.ok(policy.get());
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Policy not found");
            }
    }

    @Operation(summary = "人工审批 REFERRED 报价单", description = "核保员对状态为 REFERRED 的报价单进行人工审批，审批后报价单转为 APPROVED 状态，用户可继续支付。仅 REFERRED 状态的报价单可被审批。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "审批成功",
                    content = @Content(schema = @Schema(implementation = ApproveQuoteResponse.class))),
            @ApiResponse(responseCode = "400", description = "报价单状态不允许审批或已过期"),
            @ApiResponse(responseCode = "404", description = "报价单未找到"),
            @ApiResponse(responseCode = "500", description = "审批处理失败")
    })
    @PostMapping("/quotes/{quoteId}/approve")
    @PreAuthorize("hasAuthority('underwriting:approve')")
    public ResponseEntity<?> approveQuote(
            @Parameter(description = "报价单 ID", example = "1")
            @PathVariable Long quoteId,
            @RequestBody(required = false) ApproveQuoteRequest request) {
        logger.info("Processing manual approval for quote #{}", quoteId);

            Double premiumAmount = (request != null) ? request.getPremiumAmount() : null;
            String comment = (request != null) ? request.getComment() : null;

            ApproveQuoteResponse response = agentService.approveQuote(quoteId, premiumAmount, comment);

            return ResponseEntity.ok(response);


    }

    @Operation(summary = "人工审核理赔单", description = "理赔审核员对状态为 INVESTIGATING 的理赔单进行人工审核（批准或拒绝）。审核结果即为终态，无需再次提交理赔。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "审核成功",
                    content = @Content(schema = @Schema(implementation = ReviewClaimResponse.class))),
            @ApiResponse(responseCode = "400", description = "理赔单状态不允许审核"),
            @ApiResponse(responseCode = "404", description = "理赔单未找到"),
            @ApiResponse(responseCode = "500", description = "审核处理失败")
    })
    @PostMapping("/claims/{claimNumber}/review")
    @PreAuthorize("hasAuthority('claims:review')")
    public ResponseEntity<?> reviewClaim(
            @Parameter(description = "理赔单号", example = "CLM-C1E3A50")
            @PathVariable String claimNumber,
            @RequestBody @Valid ReviewClaimRequest request) {
        logger.info("Processing manual review for claim {}: decision={}", claimNumber, request.getDecision());

            ReviewClaimResponse response = agentService.reviewClaim(
                    claimNumber,
                    request.getDecision(),
                    request.getReviewerNotes(),
                    request.getApprovedAmount()
            );

            return ResponseEntity.ok(response);


    }

    @Operation(summary = "支付保费并签发保单", description = "根据已批准的报价单 ID 支付保费，自动签发正式保单并返回保单号")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "支付成功，保单已签发",
                    content = @Content(schema = @Schema(implementation = PayResponse.class))),
            @ApiResponse(responseCode = "400", description = "报价单状态不允许支付或已过期"),
            @ApiResponse(responseCode = "404", description = "报价单未找到"),
            @ApiResponse(responseCode = "500", description = "支付处理失败")
    })
    @PostMapping("/pay")
    @PreAuthorize("hasAuthority('underwriting:write')")
    public ResponseEntity<?> payAndIssuePolicy(
            @RequestBody @Valid PayRequest request) {
        logger.info("Processing payment for quote #{} via {}",
                    request.getQuoteId(), request.getPaymentMethod());

            Policy policy = paymentService.payAndIssuePolicy(
                    request.getQuoteId(),
                    request.getPaymentMethod() != null ? request.getPaymentMethod() : "ALIPAY");

            PayResponse response = new PayResponse(
                    policy.getPolicyNumber(),
                    policy.getPremiumAmount(),
                    policy.getStatus().name(),
                    policy.getEffectiveDate(),
                    policy.getExpirationDate(),
                    "Payment successful. Policy " + policy.getPolicyNumber() + " has been issued."
            );

            return ResponseEntity.ok(response);


    }

    @Operation(summary = "健康检查", description = "检查保险 API 服务是否正常运行")
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Insurance API is running");
    }
}
