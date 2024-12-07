package com.atguigu.daijia.payment.receiver;

import com.alibaba.fastjson2.JSONObject;
import com.atguigu.daijia.common.constant.MqConst;
import com.atguigu.daijia.model.form.payment.ProfitsharingForm;
import com.atguigu.daijia.payment.service.WxPayService;
import com.atguigu.daijia.payment.service.WxProfitsharingService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class PaymentReceiver {

    @Autowired
    private WxPayService wxPayService;

    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(value = MqConst.EXCHANGE_ORDER), //交换机 EXCHANGE_ORDER = "daijia.order"
            value = @Queue(value = MqConst.QUEUE_PAY_SUCCESS,durable = "true"),//队列 "daijia.pay.success" durable=true 队列持久化
            key = {MqConst.ROUTING_PAY_SUCCESS} //路由键 "daijia.pay.success"
    ))


    public void paySuccess(String orderNo, Message message, Channel channel) throws IOException {
        wxPayService.handleOrder(orderNo);
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }



}