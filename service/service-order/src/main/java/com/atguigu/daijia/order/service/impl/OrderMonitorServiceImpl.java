package com.atguigu.daijia.order.service.impl;

import com.atguigu.daijia.model.entity.order.OrderMonitor;
import com.atguigu.daijia.model.entity.order.OrderMonitorRecord;
import com.atguigu.daijia.order.mapper.OrderMonitorMapper;
import com.atguigu.daijia.order.repository.OrderMonitorRecordRepository;
import com.atguigu.daijia.order.service.OrderMonitorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderMonitorServiceImpl extends ServiceImpl<OrderMonitorMapper, OrderMonitor> implements OrderMonitorService {


    @Autowired
    private OrderMonitorRecordRepository orderMonitorRecordRepository;


    // 5 保存订单监控记录数据
    // 司机开始代驾之后，整个过程中，记录对话信息，直到代驾结束
    // 在前端小程序，同声传译，把录音转换文本，保存文本内容
    @Override
    public Boolean saveOrderMonitorRecord(OrderMonitorRecord orderMonitorRecord) {
        //上传到mongodb
        orderMonitorRecordRepository.save(orderMonitorRecord);
        return true;
    }

    @Autowired
    private OrderMonitorMapper orderMonitorMapper;

    @Override
    public Long saveOrderMonitor(OrderMonitor orderMonitor) {
        //向mysql的order_monitor表中插入订单监控的总状态表
        orderMonitorMapper.insert(orderMonitor);
        return orderMonitor.getId();
    }

    @Override
    public OrderMonitor getOrderMonitor(Long orderId) {
        //根据订单id查询mysql的order_monitor(订单监控的总状态表)
        return this.getOne(new LambdaQueryWrapper<OrderMonitor>().eq(OrderMonitor::getOrderId, orderId));
    }

    @Override
    public Boolean updateOrderMonitor(OrderMonitor orderMonitor) {
        //更新mysql的order_monitor(订单监控的总状态表)
        return this.updateById(orderMonitor);
    }


}
