package com.embabel.insurance;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能保险平台启动入口。
 *
 * <p>在 Spring Boot 启动前手动初始化 OpenTelemetry SDK，
 * 解决 MySQL JDBC 9.x 自动调用 GlobalOpenTelemetry.get() 与 embabel OTEL 的冲突。
 */
@SpringBootApplication
public class EmbabelApplication {

    public static void main(String[] args) {
        initOpenTelemetry();
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
        SpringApplication.run(EmbabelApplication.class, args);
    }

    /**
     * 手动初始化 OpenTelemetry SDK（在 MySQL 驱动加载之前）。
     *
     * <p>如果配置了 LANGFUSE_* 环境变量，则创建 OTLP gRPC 导出器将追踪数据发送到 Langfuse；
     * 否则注册一个 no-op OTEL 实例，阻止 MySQL 驱动自行创建。
     */
    private static void initOpenTelemetry() {
        String langfuseEndpoint = System.getenv("LANGFUSE_ENDPOINT");
        String publicKey = System.getenv("LANGFUSE_PUBLIC_KEY");
        String secretKey = System.getenv("LANGFUSE_SECRET_KEY");

        if (langfuseEndpoint == null || publicKey == null || secretKey == null
                || publicKey.isEmpty() || secretKey.isEmpty()) {
            // no-op 实例，阻止 MySQL 争抢
            OpenTelemetrySdk.builder().buildAndRegisterGlobal();
            return;
        }

        // 构建 OTLP 导出器 → Langfuse
        var spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(langfuseEndpoint)
                .addHeader("Authorization", "Basic "
                        + java.util.Base64.getEncoder().encodeToString(
                                (publicKey + ":" + secretKey).getBytes()))
                .build();

        var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build();

        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }
}
