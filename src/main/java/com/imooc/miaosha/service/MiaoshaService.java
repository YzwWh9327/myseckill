package com.imooc.miaosha.service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.domain.OrderInfo;
import com.imooc.miaosha.redis.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.vo.GoodsVo;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

@Service
public class MiaoshaService {
	
	@Autowired
	GoodsService goodsService;
	
	@Autowired
	OrderService orderService;
	
	@Autowired
	RedisService redisService;

	@Transactional
	public OrderInfo miaosha(MiaoshaUser user, GoodsVo goods) {
		//减库存 下订单 写入秒杀订单
		boolean success = goodsService.reduceStock(goods);
		if(success) {
			//order_info maiosha_order
			return orderService.createOrder(user, goods);
		}else {
			setGoodsOver(goods.getId());
			return null;
		}
	}

	public long getMiaoshaResult(Long userId, long goodsId) {
		MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(userId, goodsId);
		if(order != null) {//秒杀成功
			return order.getOrderId();
		}else {
			boolean isOver = getGoodsOver(goodsId);
			if(isOver) {
				return -1;
			}else {
				return 0;
			}
		}
	}

	private void setGoodsOver(Long goodsId) {
		redisService.set(MiaoshaKey.isGoodsOver, ""+goodsId, true);
	}
	
	private boolean getGoodsOver(long goodsId) {
		return redisService.exists(MiaoshaKey.isGoodsOver, ""+goodsId);
	}
	
	public void reset(List<GoodsVo> goodsList) {
		goodsService.resetStock(goodsList);
		orderService.deleteOrders();
	}

	public BufferedImage createMiaoshaVerifyCode(MiaoshaUser user, long goodsId) {
		if (user == null || goodsId <= 0){
			return null;
		}
		int width = 80;
		int height = 32;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics graphics = image.getGraphics();
		graphics.setColor(new Color(0xdcdcdc));
		graphics.fillRect(0,0,width,height);
		graphics.setColor(Color.BLACK);
		graphics.drawRect(0,0,width-1,height-1);
		Random random = new Random();
		for (int i = 0; i < 50; i++) {
			int x = random.nextInt(width);
			int y = random.nextInt(height);
			graphics.drawOval(x,y,0,0);
		}
		String verifyCode = createVerifyCode(random);
		graphics.setColor(new Color(0,100,0));
		graphics.setFont(new Font("Candara",Font.BOLD,24));
		graphics.drawString(verifyCode,8,24);
		int calcResult = calc(verifyCode);
		redisService.set(MiaoshaKey.verifyCode,""+user.getId()+"_"+goodsId,calcResult);
		return image;
	}

//	public static void main(String[] args) {
//		Random random = new Random();
//		String verifyCode = createVerifyCode(random);
//		int calc = calc(verifyCode);
//		System.out.println(verifyCode);
//		System.out.println(calc);
//	}
	private static int calc(String exp) {
		try {
			ScriptEngineManager manager = new ScriptEngineManager();
			ScriptEngine engine = manager.getEngineByName("JavaScript");
			return (Integer) engine.eval(exp);
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	private static char[] OPS = {'+','-','*'};
	private static String createVerifyCode(Random random) {
		int num1 = random.nextInt(10);
		int num2= random.nextInt(10);
		int num3 = random.nextInt(10);
		char op1 = OPS[random.nextInt(3)];
		char op2 = OPS[random.nextInt(3)];
		return ""+num1+op1+num2+op2+num3;
	}

	public boolean checkVerifyCode(MiaoshaUser user, long goodsId, int verifyCode) {
		if (user == null || goodsId <= 0){
			return false;
		}
		Integer codeOld = redisService.get(MiaoshaKey.verifyCode, "" + user.getId() + "_" + goodsId, Integer.class);
		if (codeOld == null || codeOld - verifyCode != 0) {
			return false;
		}
		redisService.delete(MiaoshaKey.verifyCode, "" + user.getId() + "_" + goodsId);
		return true;
	}
}
