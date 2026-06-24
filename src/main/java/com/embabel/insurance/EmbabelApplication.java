package com.embabel.insurance;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能保险平台启动入口。
 */
@SpringBootApplication
public class EmbabelApplication {

    public static void main(String[] args) {
        // 在 MySQL 驱动加载前注册 OpenTelemetry，避免冲突
        initOpenTelemetry();
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
        SpringApplication.run(EmbabelApplication.class, args);
    }

    /**
     * 手动初始化 OpenTelemetry SDK。
     *
     * 若配置了 LANGFUSE_ENDPOINT / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY，
     * 创建 OTLP gRPC 导出器将追踪数据发送到 Langfuse。
     * 否则创建 no-op 实例，阻止 MySQL 驱动自行创建导致冲突。
     */
    private static void initOpenTelemetry() {
        String endpoint = System.getenv("LANGFUSE_ENDPOINT");
        String pubKey = System.getenv("LANGFUSE_PUBLIC_KEY");
        String secKey = System.getenv("LANGFUSE_SECRET_KEY");

        if (endpoint != null && pubKey != null && secKey != null
                && !pubKey.isEmpty() && !secKey.isEmpty()) {
            var spanExporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .addHeader("Authorization", "Basic "
                            + java.util.Base64.getEncoder().encodeToString(
                                    (pubKey + ":" + secKey).getBytes()))
                    .build();

            var tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                    .build();

            OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .buildAndRegisterGlobal();
        } else {
            // no-op 实例，阻止 MySQL JDBC 抢占注册
            OpenTelemetrySdk.builder().buildAndRegisterGlobal();
        }
    }
}
