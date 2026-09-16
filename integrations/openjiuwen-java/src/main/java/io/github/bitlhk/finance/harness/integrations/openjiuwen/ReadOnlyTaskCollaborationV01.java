package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.TrustedBusinessTimeV01;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Generic read-only task guidance; it does not plan, authorize, or execute tools. */
public final class ReadOnlyTaskCollaborationV01 {
    public static final String VERSION = "finance-readonly-task-collaboration.v0.1";
    public static final String INSTRUCTION = """
            逐项核对用户当前请求中的独立目标；一个工具结果只覆盖其明确输出范围，不得用相邻能力替代未安装的目标能力。
            已有可信结果足以支持某个目标时无需重复调用；缺少必要参数时澄清，能力未安装或数据缺失时明确说明未完成部分。
            在最终回答前核对每个目标是否有对应工具结果或明确的澄清、缺失、不支持说明。否定或取消的目标不得执行。
            """.trim();

    private ReadOnlyTaskCollaborationV01() { }

    public static String trustedTimeInstruction(TrustedBusinessTimeV01 value) {
        return "宿主提供的可信业务日期为 " + value.businessDate() + "，时区为 " + value.timeZone().getId()
                + "，来源为 " + value.source() + "。相对日期只能依据该上下文解析；"
                + "仅给出月份但没有年份时应澄清，不得用业务日期补年份。";
    }

    public static String digest() {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(INSTRUCTION.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest task collaboration instruction", exception);
        }
    }
}
