package com.imooc.miaosha.controller;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;


import com.imooc.miaosha.access.AccessLimit;
import com.imooc.miaosha.redis.*;
import com.imooc.miaosha.util.MD5Util;
import com.imooc.miaosha.util.UUIDUtil;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.rabbitmq.MQSender;
import com.imooc.miaosha.rabbitmq.MiaoshaMessage;
import com.imooc.miaosha.result.CodeMsg;
import com.imooc.miaosha.result.Result;
import com.imooc.miaosha.service.GoodsService;
import com.imooc.miaosha.service.MiaoshaService;
import com.imooc.miaosha.service.MiaoshaUserService;
import com.imooc.miaosha.service.OrderService;
import com.imooc.miaosha.vo.GoodsVo;

import javax.imageio.ImageIO;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/miaosha")
public class MiaoshaController implements InitializingBean {

	@Autowired
	MiaoshaUserService userService;
	
	@Autowired
	RedisService redisService;
	
	@Autowired
	GoodsService goodsService;
	
	@Autowired
	OrderService orderService;
	
	@Autowired
	MiaoshaService miaoshaService;
	
	@Autowired
	MQSender sender;
	
	private HashMap<Long, Boolean> localOverMap =  new HashMap<Long, Boolean>();
	
	/**
	 * 系统初始化
	 * */
	public void afterPropertiesSet() throws Exception {
		List<GoodsVo> goodsList = goodsService.listGoodsVo();
		if(goodsList == null) {
			return;
		}
		for(GoodsVo goods : goodsList) {
			redisService.set(GoodsKey.getMiaoshaGoodsStock, ""+goods.getId(), goods.getStockCount());
			localOverMap.put(goods.getId(), false);
		}
	}
	
	@RequestMapping(value="/reset", method=RequestMethod.GET)
    @ResponseBody
    public Result<Boolean> reset(Model model) {
		List<GoodsVo> goodsList = goodsService.listGoodsVo();
		for(GoodsVo goods : goodsList) {
			goods.setStockCount(10);
			redisService.set(GoodsKey.getMiaoshaGoodsStock, ""+goods.getId(), 10);
			localOverMap.put(goods.getId(), false);
		}
		redisService.delete(OrderKey.getMiaoshaOrderByUidGid);
		redisService.delete(MiaoshaKey.isGoodsOver);
		miaoshaService.reset(goodsList);
		return Result.success(true);
	}
	@RequestMapping(value="/verifyCode", method=RequestMethod.GET)
	@ResponseBody
	public Result<String> getMiaoshaVerifyCode(HttpServletResponse response, MiaoshaUser user,
											   @RequestParam("goodsId")long goodsId) {
		if (user == null) {
			return Result.error(CodeMsg.SESSION_ERROR);
		}
		BufferedImage image = miaoshaService.createMiaoshaVerifyCode(user,goodsId);
		try{
			ServletOutputStream os = response.getOutputStream();
			ImageIO.write(image,"JPEG",os);
			os.flush();
			os.close();
			return null;
		}catch (Exception e){
			e.printStackTrace();
			return Result.error(CodeMsg.MIAOSHA_FAILED);
		}
	}

	@RequestMapping(value="/getpath", method=RequestMethod.GET)
	@ResponseBody
	@AccessLimit(seconds=5,maxCount=5)
	public Result<String> getMiaoshaPath(MiaoshaUser user,
										 @RequestParam("goodsId") long goodsId,
										 @RequestParam(value = "verifyCode") int verifyCode) {
		if (user == null) {
			return Result.error(CodeMsg.SESSION_ERROR);
		}
		boolean checkVerifyCode = miaoshaService.checkVerifyCode(user,goodsId,verifyCode);
		if(!checkVerifyCode){
			return Result.error(CodeMsg.VERIFY_CODE_ERROR);
		}
		String randPath = MD5Util.md5(UUIDUtil.uuid());
		redisService.set(MiaoshaKey.miaoshaPath,"" +user.getId()+"_"+goodsId,randPath);
		return Result.success(randPath);
	}
	/**
	 * QPS:1306
	 * 5000 * 10
	 * QPS: 2114
	 * */
    @RequestMapping(value="/do_miaosha/{path}", method=RequestMethod.POST)
    @ResponseBody
	public Result<Integer> miaosha(Model model, MiaoshaUser user,
								   @RequestParam("goodsId") long goodsId,
								   @PathVariable String path) {
    	model.addAttribute("user", user);
    	if(user == null) {
    		return Result.error(CodeMsg.SESSION_ERROR);
    	}
    	//检查前端传入的随机地址是否与后端存入的一致
		String calPath = redisService.get(MiaoshaKey.miaoshaPath, "" + user.getId() + "_" + goodsId, String.class);
    	if (!calPath.equals(path)){
    		return Result.error(CodeMsg.INVALID_REQUEST);
		}
		//内存标记，减少redis访问
    	boolean over = localOverMap.get(goodsId);
    	if(over) {
    		return Result.error(CodeMsg.MIAO_SHA_OVER);
    	}
    	//预减库存
    	long stock = redisService.decr(GoodsKey.getMiaoshaGoodsStock, ""+goodsId);//10
    	if(stock < 0) {
    		 localOverMap.put(goodsId, true);
    		return Result.error(CodeMsg.MIAO_SHA_OVER);
    	}
    	//判断是否已经秒杀到了
    	MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
    	if(order != null) {
    		return Result.error(CodeMsg.REPEATE_MIAOSHA);
    	}
    	//入队
    	MiaoshaMessage mm = new MiaoshaMessage();
    	mm.setUser(user);
    	mm.setGoodsId(goodsId);
    	sender.sendMiaoshaMessage(mm);
    	return Result.success(0);//排队中
    }
    
    /**
     * orderId：成功
     * -1：秒杀失败
     * 0： 排队中
     * */
    @RequestMapping(value="/result", method=RequestMethod.GET)
    @ResponseBody
    public Result<Long> miaoshaResult(Model model,MiaoshaUser user,
    		@RequestParam("goodsId")long goodsId) {
    	model.addAttribute("user", user);
    	if(user == null) {
    		return Result.error(CodeMsg.SESSION_ERROR);
    	}
    	long result  =miaoshaService.getMiaoshaResult(user.getId(), goodsId);
    	return Result.success(result);
    }

}
