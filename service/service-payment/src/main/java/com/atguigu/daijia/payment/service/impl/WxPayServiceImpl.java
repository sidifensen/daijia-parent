package com.atguigu.daijia.payment.service.impl;

import com.alibaba.fastjson2.JSON;
import com.atguigu.daijia.common.constant.MqConst;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.service.RabbitService;
import com.atguigu.daijia.common.util.RequestUtils;
import com.atguigu.daijia.driver.client.DriverAccountFeignClient;
import com.atguigu.daijia.model.entity.payment.PaymentInfo;
import com.atguigu.daijia.model.enums.TradeType;
import com.atguigu.daijia.model.form.driver.TransferForm;
import com.atguigu.daijia.model.form.payment.PaymentInfoForm;
import com.atguigu.daijia.model.vo.order.OrderRewardVo;
import com.atguigu.daijia.model.vo.payment.WxPrepayVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.atguigu.daijia.payment.config.WxPayV3Properties;
import com.atguigu.daijia.payment.mapper.PaymentInfoMapper;
import com.atguigu.daijia.payment.service.WxPayService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;

import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
//import io.seata.spring.annotation.GlobalTransactional;
import io.seata.spring.annotation.GlobalTransactional;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;

@Service
@Slf4j
public class WxPayServiceImpl implements WxPayService {

    @Autowired
    private PaymentInfoMapper paymentInfoMapper;

    @Autowired
    private WxPayV3Properties wxPayV3Properties;

    @Autowired
    private RSAAutoCertificateConfig rsaAutoCertificateConfig;

    @Override
    public WxPrepayVo createWxPayment(PaymentInfoForm paymentInfoForm) {
        try {
            //1 添加支付记录到表中
            //判断:如果表存在该订单号，则不再插入
            LambdaQueryWrapper<PaymentInfo> queryWrapper = new LambdaQueryWrapper<PaymentInfo>().eq(PaymentInfo::getOrderNo, paymentInfoForm.getOrderNo());
            PaymentInfo paymentInfo = paymentInfoMapper.selectOne(queryWrapper);
            if(null == paymentInfo) {
                paymentInfo = new PaymentInfo();
                BeanUtils.copyProperties(paymentInfoForm, paymentInfo);
                paymentInfo.setPaymentStatus(0);//0 未支付

                paymentInfoMapper.insert(paymentInfo);
            }

            //2 创建微信支付使用对象 构建service  //import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
            JsapiServiceExtension service = new JsapiServiceExtension.Builder().config(rsaAutoCertificateConfig).build();

            //3 创建request对象,封装微信支付所需参数
            // request.setXxx(val)设置所需参数，具体参数可见Request定义
            PrepayRequest request = new PrepayRequest();//import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;

            Amount amount = new Amount();
            amount.setTotal(paymentInfoForm.getAmount().multiply(new BigDecimal(100)).intValue());//单位为分

            request.setAmount(amount);//金额
            request.setAppid(wxPayV3Properties.getAppid());//appid
            request.setMchid(wxPayV3Properties.getMerchantId());//商户号
            //string[1,127]
            String description = paymentInfo.getContent();
            if(description.length() > 127) { //description最大长度为127 要进行截取
                description = description.substring(0, 127);
            }
            request.setDescription(paymentInfo.getContent());//描述
            request.setNotifyUrl(wxPayV3Properties.getNotifyUrl());//回调地址
            request.setOutTradeNo(paymentInfo.getOrderNo());//订单号

            //获取用户信息
            Payer payer = new Payer();
            payer.setOpenid(paymentInfoForm.getCustomerOpenId());//设置乘客的openid
            request.setPayer(payer);

            //是否指定分账，不指定不能分账
            SettleInfo settleInfo = new SettleInfo();
            settleInfo.setProfitSharing(true);//是否指定分账
            request.setSettleInfo(settleInfo);

            //4 调用下单方法，得到应答,实现微信支付
            // response包含了调起支付所需的所有参数，可直接用于前端调起支付
            PrepayWithRequestPaymentResponse response = service.prepayWithRequestPayment(request);
            log.info("微信支付下单返回参数：{}", JSON.toJSONString(response));

            //5 根据返回结果,封装到WxPrepayVo对象中返回
            WxPrepayVo wxPrepayVo = new WxPrepayVo();
            BeanUtils.copyProperties(response, wxPrepayVo);
            wxPrepayVo.setTimeStamp(response.getTimeStamp());//因为两个vo的时间戳的属性名不同，所以需要单独设置
            return wxPrepayVo;

        } catch (Exception e) {
            e.printStackTrace();
            throw new GuiguException(ResultCodeEnum.WX_CREATE_ERROR);
        }
    }

    @Autowired
    private RabbitService rabbitService;

    //微信支付后,进行的回调
    @Transactional//事务管理
    @Override
    public void wxnotify(HttpServletRequest request) {

        //1.回调通知的验签与解密
        //从request头信息获取参数
        //HTTP 请求体 body。切记使用原始报文，不要用 JSON 对象序列化后的字符串，避免验签的 body 和原文不一致。
        String wechatPaySerial = request.getHeader("Wechatpay-Serial");//HTTP 头 Wechatpay-Serial 微信支付证书序列号
        String nonce = request.getHeader("Wechatpay-Nonce");// HTTP 头 Wechatpay-Nonce 微信支付随机字符串
        String timestamp = request.getHeader("Wechatpay-Timestamp");//HTTP 头 Wechatpay-Timestamp 微信支付时间戳
        String signature = request.getHeader("Wechatpay-Signature"); //HTTP 头 Wechatpay-Signature 微信支付签名
        String requestBody = RequestUtils.readData(request);//用RequestUtils工具类读取请求体,将通知参数转化为字符串

        log.info("wechatPaySerial：{}", wechatPaySerial);
        log.info("nonce：{}", nonce);
        log.info("timestamp：{}", timestamp);
        log.info("signature：{}", signature);
        log.info("requestBody：{}", requestBody);

        //2.构造 RequestParam
        RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(wechatPaySerial)
                .nonce(nonce)
                .signature(signature)
                .timestamp(timestamp)
                .body(requestBody)
                .build();


        //3.初始化 NotificationParser 对象 用于解析微信支付通知
        NotificationParser parser = new NotificationParser(rsaAutoCertificateConfig);

        //4.以支付通知回调为例，验签、解密并转换成 Transaction
        Transaction transaction = parser.parse(requestParam, Transaction.class);

        log.info("成功解析：{}", JSON.toJSONString(transaction));

        if(null != transaction && transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS) {

            //5.调用下面自定义的方法,处理支付业务,更新数据库里的订单状态
            this.handlePayment(transaction);
        }

    }


    @Override
    public Boolean queryPayStatus(String orderNo) {
        //1.创建微信支付对象 构建service
        JsapiServiceExtension service = new JsapiServiceExtension.Builder().config(rsaAutoCertificateConfig).build();

        //2. 封装查询支付状态需要的
        QueryOrderByOutTradeNoRequest queryRequest = new QueryOrderByOutTradeNoRequest();
        queryRequest.setMchid(wxPayV3Properties.getMerchantId());//商户号
        queryRequest.setOutTradeNo(orderNo);//订单号

        try {
            //3.调用微信支付对象的方法实现查询操作
            Transaction transaction = service.queryOrderByOutTradeNo(queryRequest);
            log.info(JSON.toJSONString(transaction));

            //4.判断查询结果，如果查询成功，则更改订单状态 根据transaction的TradeState状态是否为Transaction.TradeStateEnum.SUCCESS进行判断
            if(null != transaction && transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS) {

                //5.调用自定义的方法,更改数据库中的订单状态
                this.handlePayment(transaction);

                return true;
            }
        } catch (GuiguException e) {
            // API返回失败, 例如ORDER_NOT_EXISTS
            System.out.printf("code=[%s], message=[%s]\n", e.getCode(), e.getMessage());
        }
        return false;
    }

    //自定义方法:查询支付状态
    public void handlePayment(Transaction transaction) {

        //1 更新数据库中的订单状态,状态修改为1(已支付)
        String outTradeNo = transaction.getOutTradeNo();//订单号

        LambdaQueryWrapper<PaymentInfo> queryWrapper = new LambdaQueryWrapper();
        queryWrapper.eq(PaymentInfo::getOrderNo, outTradeNo);
        //根据订单号查询支付信息
        PaymentInfo paymentInfo = paymentInfoMapper.selectOne(queryWrapper);

        if (paymentInfo.getPaymentStatus() == 1) {//如果订单状态为1(已支付)，则不再更新
            return;
        }

        //更新支付信息
        paymentInfo.setPaymentStatus(1);
        paymentInfo.setOrderNo(transaction.getOutTradeNo());//订单号
        paymentInfo.setTransactionId(transaction.getTransactionId());//微信支付订单号
        paymentInfo.setCallbackTime(new Date());//回调时间
        paymentInfo.setCallbackContent(JSON.toJSONString(transaction));//回调内容

        paymentInfoMapper.updateById(paymentInfo);
        // 表示交易成功！

        //2 发送端:发送mq消息通知订单支付成功, 传递订单号
        // 后续更新订单状态！ 使用消息队列！
        rabbitService.sendMessage(
                MqConst.EXCHANGE_ORDER, //"daijia.order"
                MqConst.ROUTING_PAY_SUCCESS, //"daijia.pay.success"
                paymentInfo.getOrderNo()// 订单号
        );

        //3 接受端:接收订单编号,完成后续处理

    }

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;
    @Autowired
    private DriverAccountFeignClient driverAccountFeignClient;

    //支付成功后续处理
    @GlobalTransactional//分布式事务    @Transactional//本地事务
    @Override
    public void handleOrder(String orderNo) {
        //1.更改订单支付状态
        orderInfoFeignClient.updateOrderPayStatus(orderNo);

        //2.处理系统奖励，打入司机账户
        OrderRewardVo orderRewardVo = orderInfoFeignClient.getOrderRewardFee(orderNo).getData();
        //判断是否有系统奖励,如果有,则进行转账,如果没有,则不进行转账
        if(null != orderRewardVo.getRewardFee() && orderRewardVo.getRewardFee().doubleValue() > 0) {

            TransferForm transferForm = new TransferForm();
            transferForm.setTradeNo(orderNo);
            transferForm.setTradeType(TradeType.REWARD.getType());//(1, "系统奖励")
            transferForm.setContent(TradeType.REWARD.getContent());// 获取系统奖励描述
            transferForm.setAmount(orderRewardVo.getRewardFee());// 获取系统奖励金额
            transferForm.setDriverId(orderRewardVo.getDriverId());// 获取司机id

            //调用司机账户服务,进行转账
            driverAccountFeignClient.transfer(transferForm);
        }

        //3.TODO分账
    }

}
