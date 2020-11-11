package com.imooc.miaosha.dao;

import org.apache.ibatis.annotations.*;
import com.imooc.miaosha.domain.MiaoshaUser;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;

@Mapper
@Component
public interface MiaoshaUserDao {
	
	@Select("select * from miaosha_user where id = #{id}")
	public MiaoshaUser getById(@Param("id")long id);

	@Update("update miaosha_user set password = #{password} where id = #{id}")
	public void update(MiaoshaUser toBeUpdate);

	@Insert("insert into miaosha_user(id,password,salt) value(#{id},#{password},#{salt})")
	public void insert(MiaoshaUser newUser);

	@Update("update miaosha_user set login_count = login_count+1 where id = #{id}")
	void incLoginCount(@Param("id")long id);

	@Update("update miaosha_user set last_login_date = #{date} where id = #{id}")
	void updateLastLoginDate(@Param("id")long id, @Param("date")Timestamp date);

	@Update("update miaosha_user set register_date = #{date} where id = #{id}")
	void updateFirstLoginDate(@Param("id")long id, @Param("date")Timestamp date);
}
