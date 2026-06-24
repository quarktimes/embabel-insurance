package com.embabel.insurance.e2e;

import com.embabel.insurance.dto.request.ClaimRequest;
import com.embabel.insurance.dto.request.PayRequest;
import com.embabel.insurance.dto.request.UnderwritingRequest;
import com.embabel.insurance.dto.response.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端冒烟测试 — 测试最核心的 3 条业务链路。
 *
 * <p>H1 健康检查 / P1 核保全链路 / C1 理赔全链路
 * 其余业务路径由集成测试覆盖（Fake LLM，更稳定更快）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "embabel.models.default-llm=deepseek-chat",
        "spring.profiles.active=e2e"
})
@ActiveProfiles("e2e-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
public class CompleteE2ETest {

    static {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
    }

    private static final Logger log = LoggerFactory.getLogger(CompleteE2ETest.class);

    private static final String LOW_RISK_USER = "low-risk-user";
    private static final String DEFAULT_PASSWORD = "password";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl;
    private Long lowRiskQuoteId;
    private String lowRiskPolicyNumber;

    @BeforeAll
    void setupBaseUrl() {
        baseUrl = "http://localhost:" + port + "/api/insurance";
        log.info("E2E smoke test base URL: {}", baseUrl);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  H1: 健康检查
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("H1 [健康检查] GET /api/insurance/health → 200")
    void testHealthCheck() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                baseUrl + "/health", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("Insurance API is running", resp.getBody());
        log.info("✓ 健康检查通过");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  P1: 低风险核保 → 支付 → 保单
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("P1 [核保] 低风险 → APPROVED → 支付 → 保单")
    void testLowRiskUnderwritingPayPolicy() {
        log.info("═══ P1: 低风险核保全链路 ═══");

        // Step 1: 核保
        UnderwritingRequest uwReq = new UnderwritingRequest(LOW_RISK_USER,
                "I want to insure my Toyota RAV4, license plate 京A12345");

        ResponseEntity<UnderwritingResponse> uwResp = restTemplate
                .withBasicAuth(LOW_RISK_USER, DEFAULT_PASSWORD)
                .exchange(baseUrl + "/underwrite", HttpMethod.POST,
                        new HttpEntity<>(uwReq, jsonHeaders()), UnderwritingResponse.class);

        assertEquals(HttpStatus.OK, uwResp.getStatusCode(), "低风险核保应返回 200");
        UnderwritingResponse uwBody = uwResp.getBody();
        assertNotNull(uwBody);
        assertEquals("APPROVED", uwBody.getStatus(), "低风险应自动批准");
        assertTrue(uwBody.getRiskScore() <= 60, "风险评分应 ≤ 60");
        assertTrue(uwBody.getPremiumAmount() > 0, "保费应 > 0");
        assertNotNull(uwBody.getQuoteId(), "应生成报价单 ID");

        lowRiskQuoteId = uwBody.getQuoteId();
        log.info("✓ 核保 APPROVED: quoteId={}, premium=¥{}",
                lowRiskQuoteId, String.format("%.0f", uwBody.getPremiumAmount()));

        // Step 2: 支付签发保单
        PayRequest payReq = new PayRequest(lowRiskQuoteId, "ALIPAY");
        ResponseEntity<PayResponse> payResp = restTemplate
                .withBasicAuth(LOW_RISK_USER, DEFAULT_PASSWORD)
                .exchange(baseUrl + "/pay", HttpMethod.POST,
                        new HttpEntity<>(payReq, jsonHeaders()), PayResponse.class);

        assertEquals(HttpStatus.OK, payResp.getStatusCode(), "支付应返回 200");
        PayResponse payBody = payResp.getBody();
        assertNotNull(payBody);
        assertTrue(payBody.getPolicyNumber().startsWith("POL-"), "保单号应以 POL- 开头");
        assertEquals("ACTIVE", payBody.getStatus(), "保单状态应为 ACTIVE");

        lowRiskPolicyNumber = payBody.getPolicyNumber();
        log.info("✓ 保单签发: policyNumber={}", lowRiskPolicyNumber);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  C1: 小额理赔自动批准（基于 P1 的保单）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("C1 [理赔] 小额 ¥2000 → AutoApproved")
    void testSmallClaimAutoApproved() {
        assertNotNull(lowRiskPolicyNumber, "前置条件：P1 应已创建保单");

        log.info("═══ C1: 小额理赔 AutoApproved ═══");

        ClaimRequest req = new ClaimRequest(lowRiskPolicyNumber,
                "Minor scratch on bumper in parking lot at downtown mall. Driver only, clear liability.",
                2000.0);

        ResponseEntity<ClaimResponse> resp = restTemplate
                .withBasicAuth("claims", "claims")
                .exchange(baseUrl + "/claims", HttpMethod.POST,
                        new HttpEntity<>(req, jsonHeaders()), ClaimResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), "小额理赔应返回 200");
        ClaimResponse body = resp.getBody();
        assertNotNull(body);
        assertEquals("APPROVED", body.getStatus(), "小额理赔应自动批准");
        assertTrue(body.getFraudScore() < 30, "欺诈评分应 < 30");
        assertTrue(body.getPaidAmount() > 0, "赔付金额应 > 0");
        log.info("✓ 小额理赔 APPROVED: claimNumber={}, fraudScore={}, paid=¥{}",
                body.getClaimNumber(), String.format("%.0f", body.getFraudScore()),
                String.format("%.0f", body.getPaidAmount()));
    }
}
