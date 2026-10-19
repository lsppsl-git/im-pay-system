package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.entity.RedPacket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RedPacketMapper extends BaseMapper<RedPacket> {

    @Select("SELECT * FROM red_packet WHERE packet_no = #{packetNo}")
    RedPacket findByPacketNo(@Param("packetNo") String packetNo);

    @Select("SELECT * FROM red_packet WHERE id = #{id} FOR UPDATE")
    RedPacket findByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE red_packet SET remain_amount = remain_amount - #{amount}, remain_count = remain_count - 1 WHERE id = #{id} AND remain_count > 0 AND remain_amount >= #{amount}")
    int grab(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount);

    /** 乐观锁扣减：version 必须等于读取时的值才会更新成功，并同时 version+1 */
    @Update("UPDATE red_packet SET remain_amount = remain_amount - #{amount}, remain_count = remain_count - 1, version = version + 1 WHERE id = #{id} AND version = #{version} AND remain_count > 0 AND remain_amount >= #{amount}")
    int grabWithVersion(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount, @Param("version") Integer version);

    /** 【仅教学演示】无任何保护的无条件扣减，用于演示并发超抢 */
    @Update("UPDATE red_packet SET remain_amount = remain_amount - #{amount}, remain_count = remain_count - 1 WHERE id = #{id}")
    int grabUnsafe(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount);

    @Select("SELECT * FROM red_packet WHERE status = 'ACTIVE' ORDER BY created_at DESC LIMIT #{limit}")
    List<RedPacket> findActiveRedPackets(@Param("limit") Integer limit);
}