package com.haha.supportai.data.knowledge.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "kb_member", autoResultMap = true)
public class KbMember {

    @TableField("kb_id")
    private UUID kbId; // 知识库标识

    @TableField("user_id")
    private UUID userId; // 被授权用户标识

    @TableField(value = "permission", typeHandler = EnumTypeHandler.class)
    private KbPermissionEnum permission; // 成员权限，按枚举名称存储

    @TableField("granted_by")
    private UUID grantedBy; // 授权操作人用户标识

    @TableField("created_at")
    private OffsetDateTime createdAt; // 授权创建时间
}
