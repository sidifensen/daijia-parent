package com.atguigu.daijia.model.vo.order;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class CurrentOrderInfoVo {

	@Schema(description = "订单id")
	private Long orderId;

	@Schema(description = "订单状态")
	private Integer status;

	@Schema(description = "当前是否有正在进行的订单信息")
	private Boolean isHasCurrentOrder;
}