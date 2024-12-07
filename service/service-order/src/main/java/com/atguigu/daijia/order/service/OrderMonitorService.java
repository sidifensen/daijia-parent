package com.atguigu.daijia.order.service;

import com.atguigu.daijia.model.entity.order.OrderMonitor;
import com.atguigu.daijia.model.entity.order.OrderMonitorRecord;
import com.baomidou.mybatisplus.extension.service.IService;

public interface OrderMonitorService extends IService<OrderMonitor> {

    Boolean saveOrderMonitorRecord(OrderMonitorRecord orderMonitorRecord);

    OrderMonitor getOrderMonitor(Long orderId);

    Long saveOrderMonitor(OrderMonitor orderMonitor);


    Boolean updateOrderMonitor(OrderMonitor orderMonitor);
}
