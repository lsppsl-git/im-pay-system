package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("red_packet")
public class RedPacket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String packetNo;

    private Long senderId;

    private BigDecimal totalAmount;

    private Integer totalCount;

    private BigDecimal remainAmount;

    private Integer remainCount;

    private String message;

    private String type;

    private String status;

    /** 乐观锁版本号：每次成功扣减 +1，更新时作为 WHERE 条件防止并发覆盖 */
    private Integer version;

    private LocalDateTime expiredAt;

    private LocalDateTime createdAt;
}