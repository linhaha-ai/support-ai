package com.haha.supportai.data.assistant.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.assistant.enums.ToolCallStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.JdbcType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "tool_call", autoResultMap = true)
public class ToolCall {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 工具调用唯一标识，由服务端生成 UUID

    @TableField("run_id")
    private UUID runId; // 所属问答运行标识

    @TableField("call_no")
    private Integer callNo; // 同一运行内由服务端分配的调用序号

    @TableField("provider_call_id")
    private String providerCallId; // 模型或框架调用标识，可为空且不参与授权

    @TableField("tool_name")
    private String toolName; // 服务端白名单工具名

    @TableField(value = "arguments", jdbcType = JdbcType.VARCHAR)
    private String arguments; // 经过边界处理的参数 JSON

    @TableField(value = "result_snapshot", jdbcType = JdbcType.VARCHAR)
    private String resultSnapshot; // 成功结果的受限 JSON 快照

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private ToolCallStatusEnum status; // 工具调用状态，按枚举名称存储

    @TableField("error_code")
    private String errorCode; // 失败或取消时的稳定错误码

    @TableField("started_at")
    private OffsetDateTime startedAt; // 工具调用开始时间

    @TableField("finished_at")
    private OffsetDateTime finishedAt; // 工具调用终态完成时间
}
