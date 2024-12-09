package com.atguigu.daijia.order.controller;

import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.model.entity.order.OrderMonitor;
import com.atguigu.daijia.model.entity.order.OrderMonitorRecord;
import com.atguigu.daijia.order.service.OrderMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "订单监控")
@RestController
@RequestMapping("/order/monitor")
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderMonitorController {

    @Autowired
    private OrderMonitorService orderMonitorService;

    //- 司机开始代驾之后，整个过程中，记录对话信息，直到代驾结束
    //- 在前端小程序，同声传译，把录音转换文本，保存文本内容,上传到mongodb数据库中
    @Operation(summary = "保存订单监控记录数据")
    @PostMapping("/saveOrderMonitorRecord")
    public Result<Boolean> saveMonitorRecord(@RequestBody OrderMonitorRecord orderMonitorRecord) {
        Boolean result = orderMonitorService.saveOrderMonitorRecord(orderMonitorRecord);
        return Result.ok(result);
    }

    //司乘对话内容的审核的结果更新到mysql的order_monitor数据表中，每个订单一条记录。
    @Operation(summary = "根据订单id获取订单监控信息状态")
    @GetMapping("/getOrderMonitor/{orderId}")
    public Result<OrderMonitor> getOrderMonitor(@PathVariable Long orderId) {
        return Result.ok(orderMonitorService.getOrderMonitor(orderId));
    }

    //司乘对话内容的审核的结果更新到mysql的order_monitor数据表中，每个订单一条记录。
    @Operation(summary = "更新订单监控信息状态")
    @PostMapping("/updateOrderMonitor")
    public Result<Boolean> updateOrderMonitor(@RequestBody OrderMonitor OrderMonitor) {
        return Result.ok(orderMonitorService.updateOrderMonitor(OrderMonitor));
    }



}




