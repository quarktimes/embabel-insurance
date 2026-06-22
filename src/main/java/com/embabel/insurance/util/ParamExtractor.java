package com.embabel.insurance.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参数提取工具类，从格式化文本（如 {@code key1=value1 key2=value2}）中提取命名参数。
 * <p>由 {@code UnderwritingAgent} 和 {@code ClaimsAgent} 共享，避免重复代码。
 */
public final class ParamExtractor {

    /** 匹配下一个参数边界：一个或多个空格 + 字母开头 + 单词字符 + "=" */
    private static final Pattern NEXT_PARAM = Pattern.compile("\\s+[a-zA-Z]\\w*=");

    private ParamExtractor() {
        // 工具类，禁止实例化
    }

    /**
     * 从输入字符串中提取指定参数的值。
     * <p>参数值终止于下一个 {@code key=} 参数前缀或字符串末尾，
     * 因此即使值包含空格也能正确提取。
     *
     * @param content   原始输入字符串
     * @param paramName 参数名（不含 "="）
     * @return 参数值，未找到时返回 {@code null}
     */
    public static String extract(String content, String paramName) {
        String prefix = paramName + "=";
        int startIdx = content.indexOf(prefix);
        if (startIdx == -1) {
            return null;
        }
        startIdx += prefix.length();

        Matcher m = NEXT_PARAM.matcher(content);
        int endIdx = content.length();
        if (m.find(startIdx)) {
            endIdx = m.start();
        }

        return content.substring(startIdx, endIdx).trim();
    }
}
