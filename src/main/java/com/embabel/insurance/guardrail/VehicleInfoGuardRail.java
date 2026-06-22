package com.embabel.insurance.guardrail;

import com.embabel.agent.api.validation.guardrails.UserInputGuardRail;
import com.embabel.agent.core.Blackboard;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * 车辆信息护栏，在传给 LLM 提取之前校验用户输入是否包含可识别的车辆信息。
 *
 * <p>如果输入中不包含已知品牌 + 型号的模式，将被标记为 CRITICAL 级别错误，
 * 阻止 LLM 调用，避免返回"未知"的兜底结果。
 *
 * <p>同时支持纯车牌号输入（如"车牌 ABC123"），允许用户仅凭车牌查找车辆。
 */
public class VehicleInfoGuardRail implements UserInputGuardRail {

    private static final Logger logger = LoggerFactory.getLogger(VehicleInfoGuardRail.class);

    /**
     * 车辆品牌+型号正则：已知品牌 + 空格 + 型号名称/编号。
     * 覆盖主流汽车品牌及其常见型号命名规则。
     */
    private static final Pattern VEHICLE_PATTERN = Pattern.compile(
            "(?i)(Toyota|Honda|Tesla|BMW|Porsche|Audi|Ferrari|Ford|Chevrolet|Nissan|Mazda|" +
            "Mercedes|Benz|Volkswagen|VW|Volvo|Hyundai|Kia|Subaru|Lexus|Acura|Infiniti|" +
            "Jaguar|Land\\s+Rover|Maserati|Lamborghini|Bentley|Rolls\\s+Royce|Bugatti|" +
            "McLaren|Aston\\s+Martin|Alfa\\s+Romeo|Genesis|Polestar|Rivian|Lucid)\\s+" +
            "(\\w[\\w\\-]*\\w|\\w)"
    );

    @Override
    public String getName() {
        return "VehicleInfoGuardRail";
    }

    @Override
    public String getDescription() {
        return "Validates that user input contains recognizable vehicle brand+model before LLM extraction";
    }

    @Override
    public ValidationResult validate(String input, Blackboard blackboard) {
        logger.debug("VehicleInfoGuardRail validating input: {}", input);

        if (VEHICLE_PATTERN.matcher(input).find()) {
            return ValidationResult.Companion.getVALID();
        }

        // 也接受纯车牌号输入（如 "license plate ABC123" 或 "车牌 ABC123"）
        if (Pattern.compile("(?i)(?:license\\s*plate|plate|车牌)\\s*[:#]?\\s*([A-Z0-9\\-]+)").matcher(input).find()) {
            return ValidationResult.Companion.getVALID();
        }

        var errors = new ArrayList<ValidationError>();
        errors.add(new ValidationError(
                "no-vehicle-info",
                "Input does not contain recognizable vehicle information. " +
                "Please provide a brand and model (e.g., 'Toyota RAV4', 'Tesla Model 3') or a license plate.",
                ValidationSeverity.CRITICAL,
                null
        ));

        logger.warn("VehicleInfoGuardRail rejected input: {}", input);
        return new ValidationResult(false, errors);
    }
}
