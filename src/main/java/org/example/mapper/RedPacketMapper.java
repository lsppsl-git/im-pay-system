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

    @Select("SELECT * FROM red_packet WHERE status = 'ACTIVE' ORDER BY created_at DESC LIMIT #{limit}")
    List<RedPacket> findActiveRedPackets(@Param("limit") Integer limit);
}