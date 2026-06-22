package com.embabel.insurance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能保险平台启动入口。
 *
 * <p>关闭 embabel 框架的交互式 Shell 模式，以 Servlet 容器方式运行，
 * 通过 REST API 对外提供核保、理赔、AI 客服等服务。
 */
@SpringBootApplication
public class EmbabelApplication {

    public static void main(String[] args) {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
        SpringApplication.run(EmbabelApplication.class, args);
    }
}
