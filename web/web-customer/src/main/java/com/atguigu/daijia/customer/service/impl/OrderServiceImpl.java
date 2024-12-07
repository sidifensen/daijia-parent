package com.atguigu.daijia.customer.service.impl;

import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.coupon.client.CouponFeignClient;
import com.atguigu.daijia.customer.client.CustomerInfoFeignClient;
import com.atguigu.daijia.customer.service.OrderService;
import com.atguigu.daijia.dispatch.client.NewOrderFeignClient;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.map.client.MapFeignClient;
import com.atguigu.daijia.map.client.WxPayFeignClient;
import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.coupon.UseCouponForm;
import com.atguigu.daijia.model.form.customer.ExpectOrderForm;
import com.atguigu.daijia.model.form.customer.SubmitOrderForm;
import com.atguigu.daijia.model.form.map.CalculateDrivingLineForm;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.payment.CreateWxPaymentForm;
import com.atguigu.daijia.model.form.payment.PaymentInfoForm;
import com.atguigu.daijia.model.form.rules.FeeRuleRequestForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.customer.ExpectOrderVo;
import com.atguigu.daijia.model.vo.dispatch.NewOrderTaskVo;
import com.atguigu.daijia.model.vo.driver.DriverInfoVo;
import com.atguigu.daijia.model.vo.map.DrivingLineVo;
import com.atguigu.daijia.model.vo.map.OrderLocationVo;
import com.atguigu.daijia.model.vo.map.OrderServiceLastLocationVo;
import com.atguigu.daijia.model.vo.order.CurrentOrderInfoVo;
import com.atguigu.daijia.model.vo.order.OrderBillVo;
import com.atguigu.daijia.model.vo.order.OrderInfoVo;
import com.atguigu.daijia.model.vo.order.OrderPayVo;
import com.atguigu.daijia.model.vo.payment.WxPrepayVo;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponseVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.atguigu.daijia.rules.client.FeeRuleFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderServiceImpl implements OrderService {

    @Autowired
    private MapFeignClient mapFeignClient;

    @Autowired
    private FeeRuleFeignClient feeRuleFeignClient;

    //预估订单
    @Override
    public ExpectOrderVo expectOrder(ExpectOrderForm expectOrderForm) {
        //1.将传入参数转换为CalculateDrivingLineForm(计算驾驶路线表单)对象
        CalculateDrivingLineForm calculateDrivingLineForm = new CalculateDrivingLineForm();
        BeanUtils.copyProperties(expectOrderForm, calculateDrivingLineForm);

        //2.TODO 远程调用service-map的MapController里的计算驾驶路线的方法,通过返回结果获取DrivingLineVo(驾驶路线方案),包含距离,时间,路线坐标点串
        DrivingLineVo drivingLineVo = mapFeignClient.calculateDrivingLine(calculateDrivingLineForm).getData();
        log.info("驾驶路线方案:{}", drivingLineVo);

        //3.把驾驶路线方案转换为FeeRuleRequestForm(费用规则请求表单)对象,并设置费用规则计算所需参数
        FeeRuleRequestForm calculateOrderFeeForm = new FeeRuleRequestForm();
        calculateOrderFeeForm.setDistance(drivingLineVo.getDistance());//设置路线的距离
        calculateOrderFeeForm.setStartTime(new Date());
        calculateOrderFeeForm.setWaitMinute(0);//预估订单时长待定,暂时设置为0

        //4.TODO 远程调用service-rules的FeeRuleController里的计算订单费用的方法,
        // 通过返回结果获取FeeRuleResponseVo(费用规则返回对象),包含总金额,里程费,等时费,远程费,和基础及超出的里程和价格
        FeeRuleResponseVo feeRuleResponseVo = feeRuleFeignClient.calculateOrderFee(calculateOrderFeeForm).getData();
        log.info("费用规则返回对象:{}", feeRuleResponseVo);

        //5.预估订单实体,包含驾驶车路线方案和订单费用,把两个远程调用的结构放入预估订单实体中
        ExpectOrderVo expectOrderVo = new ExpectOrderVo();
        expectOrderVo.setDrivingLineVo(drivingLineVo);
        expectOrderVo.setFeeRuleResponseVo(feeRuleResponseVo);

        //6.返回预估订单实体
        return expectOrderVo;
    }

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;

    @Autowired
    private NewOrderFeignClient newOrderFeignClient;

    //乘客下单
    @Override
    public Long submitOrder(SubmitOrderForm submitOrderForm) {

        //1.根据提交订单表单,重新计算驾驶线路
        CalculateDrivingLineForm calculateDrivingLineForm = new CalculateDrivingLineForm();
        BeanUtils.copyProperties(submitOrderForm, calculateDrivingLineForm);
        //TODO 远程调用service-map的MapController里的计算驾驶路线的方法,
        // 通过返回结果获取DrivingLineVo(驾驶路线方案),包含距离,时间,路线坐标点串
        DrivingLineVo drivingLineVo = mapFeignClient.calculateDrivingLine(calculateDrivingLineForm).getData();
        log.info("驾驶路线方案:{}", drivingLineVo);


        //2.重新计算订单费用
        FeeRuleRequestForm feeRuleRequestForm = new FeeRuleRequestForm();
        feeRuleRequestForm.setDistance(drivingLineVo.getDistance());//设置代驾里程为驾驶路线方案的距离
        feeRuleRequestForm.setStartTime(new Date());//设置订单开始时间为当前时间
        feeRuleRequestForm.setWaitMinute(0);//预估订单时长待定,暂时设置为0

        //TODO 远程调用service-rules的FeeRuleController里的计算订单费用的方法
        FeeRuleResponseVo feeRuleResponseVo = feeRuleFeignClient.calculateOrderFee(feeRuleRequestForm).getData();
        log.info("费用规则返回对象:{}", feeRuleResponseVo);

        //3.封装订单信息对象
        OrderInfoForm orderInfoForm = new OrderInfoForm();
        //把FeeRuleResponseVo(提交订单表单)中的信息复制到OrderInfoForm(订单信息表单)中
        BeanUtils.copyProperties(submitOrderForm, orderInfoForm);

        //把远程调用的计算的预估费用,和远程调用的计算的驾驶路线的预估里程,加入订单信息表单中
        orderInfoForm.setExpectAmount(feeRuleResponseVo.getTotalAmount());
        orderInfoForm.setExpectDistance(drivingLineVo.getDistance());

        //4.保存订单信息
        //TODO 远程调用service-order的OrderController里的保存订单信息(saveOrderInfo)的方法
        Long orderId = orderInfoFeignClient.saveOrderInfo(orderInfoForm).getData();
        log.info("订单id:{}",orderId);

        //5. 查找附近可以接单的司机,通知司机接单(通过任务调度实现)
        NewOrderTaskVo newOrderTaskVo = new NewOrderTaskVo();
        BeanUtils.copyProperties(orderInfoForm, newOrderTaskVo);
        newOrderTaskVo.setOrderId(orderId);
        newOrderTaskVo.setExpectTime(drivingLineVo.getDuration());//预估订单时长为路线方案的时长
        newOrderTaskVo.setCreateTime(new Date());

        //TODO 远程调用service-dispatch的DispatchController里的添加任务和启动任务的方法 把NewOrderTaskVo传进去
        Result<Long> longResult = newOrderFeignClient.addAndStartTask(newOrderTaskVo);
        Long jobId = longResult.getData();
        log.info("任务id:{}", jobId);

        //6.返回订单id
        return orderId;
    }


    //根据id获取订单状态
    @Override
    public Integer getOrderStatus(Long orderId) {
        // 远程调用service-order的OrderController里的获取订单状态的方法
        Integer orderStatus = orderInfoFeignClient.getOrderStatus(orderId).getData();
        log.info("订单状态:{}", orderStatus);
        return orderStatus;
    }

    //根据乘客id获取是否有正在进行中的订单
    @Override
    public CurrentOrderInfoVo searchCustomerCurrentOrder(Long customerId) {
        return orderInfoFeignClient.searchCustomerCurrentOrder(customerId).getData();

    }

    @Override
    public OrderInfoVo getOrderInfo(Long orderId, Long customerId) {
        //远程调用根据乘客id获取订单信息
        OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderId).getData();

        if (orderInfo.getCustomerId().longValue() != customerId.longValue()) {
            throw new GuiguException(ResultCodeEnum.ILLEGAL_REQUEST);//(205, "非法请求")
        }

        //封装订单的账单信息,只给乘客返回部分信息
        OrderBillVo orderBillVo = null;
        //判断是否结束代驾 订单状态大于等于7(待付款)    ((6, "结束服务,未付款"))
        if (orderInfo.getStatus().intValue() >= OrderStatus.END_SERVICE.getStatus().intValue()) {

            orderBillVo = orderInfoFeignClient.getOrderBillInfo(orderId).getData();
        }

        //封装订单信息
        OrderInfoVo orderInfoVo = new OrderInfoVo();
        orderInfoVo.setOrderId(orderId);
        BeanUtils.copyProperties(orderInfo, orderInfoVo);

        //TODO 自己新增的; 要往订单信息中添加司机信息,不然账单显示不出来
        orderInfoVo.setDriverInfoVo(driverInfoFeignClient.getDriverInfo(orderInfo.getDriverId()).getData());

        orderInfoVo.setOrderBillVo(orderBillVo);//传入账单信息

        //返回订单信息
        return orderInfoVo;
    }

    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;

    //司乘同显 乘客端获取司机的信息
    @Override
    public DriverInfoVo getDriverInfo(Long orderId, Long customerId) {

        OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderId).getData();

        if (orderInfo.getCustomerId().longValue() != customerId.longValue()) {
            throw new GuiguException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        DriverInfoVo data = driverInfoFeignClient.getDriverInfo(orderInfo.getDriverId()).getData();
        log.info("司机信息:{}", data);
        return data;

    }

    @Autowired
    private LocationFeignClient locationFeignClient;

    //司机赶往代驾起始点：获取订单经纬度位置
    @Override
    public OrderLocationVo getCacheOrderLocation(Long orderId) {

        OrderLocationVo data = locationFeignClient.getCacheOrderLocation(orderId).getData();
        log.info("订单经纬度位置:{}", data);
        return data;

    }

    //计算司机到乘客下单点的距离的驾驶路线
    @Override
    public DrivingLineVo calculateDrivingLine(CalculateDrivingLineForm calculateDrivingLineForm) {

        DrivingLineVo data = mapFeignClient.calculateDrivingLine(calculateDrivingLineForm).getData();
        log.info("驾驶路线方案:{}", data);
        return data;
    }

    //司乘同显
    //根据订单id从mongodb中用分页查询获取订单服务的最后一个位置信息(只包含经纬度信息)
    @Override
    public OrderServiceLastLocationVo getOrderServiceLastLocation(Long orderId) {
        OrderServiceLastLocationVo data = locationFeignClient.getOrderServiceLastLocation(orderId).getData();
        log.info("订单服务的最后一个位置信息:{}", data);
        return data;

    }

    //根据订单id用分页查询获取我的订单列表
    @Override
    public PageVo findCustomerOrderPage(Long customerId, Long page, Long limit) {
        return orderInfoFeignClient.findCustomerOrderPage(customerId, page, limit).getData();
    }


    @Autowired
    private CustomerInfoFeignClient customerInfoFeignClient;

    @Autowired
    private WxPayFeignClient wxPayFeignClient;

    @Autowired
    private CouponFeignClient couponFeignClient;


    //创建微信支付订单

    @Override
    public WxPrepayVo createWxPayment(CreateWxPaymentForm createWxPaymentForm) {

        //1.远程调用service-order的OrderController里的获取订单支付相关信息
        OrderPayVo orderPayVo = orderInfoFeignClient.getOrderPayVo(createWxPaymentForm.getOrderNo(), createWxPaymentForm.getCustomerId()).getData();
        //判断是否在未支付状态 如果不是(7, "待付款")状态,抛出非法请求异常
        if (orderPayVo.getStatus().intValue() != OrderStatus.UNPAID.getStatus().intValue()) {
            throw new GuiguException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        //2.获取乘客微信openId
        String customerOpenId = customerInfoFeignClient.getCustomerOpenId(orderPayVo.getCustomerId()).getData();

        //3.获取司机微信openId
        String driverOpenId = driverInfoFeignClient.getDriverOpenId(orderPayVo.getDriverId()).getData();

        //4.使用优惠券
        BigDecimal couponAmount = null;
        //支付时选择过一次优惠券，如果支付失败或未支付，下次支付时不能再次选择，只能使用第一次选中的优惠券（前端已控制，后端再次校验）
        if (null == orderPayVo.getCouponAmount() && //CouponAmount为null 表示未使用优惠券
                null != createWxPaymentForm.getCustomerCouponId() &&//选择了某个优惠券
                createWxPaymentForm.getCustomerCouponId() != 0) {//选择的优惠券id不为0

            UseCouponForm useCouponForm = new UseCouponForm();
            useCouponForm.setOrderId(orderPayVo.getOrderId());
            useCouponForm.setCustomerCouponId(createWxPaymentForm.getCustomerCouponId());
            useCouponForm.setOrderAmount(orderPayVo.getPayAmount());
            useCouponForm.setCustomerId(createWxPaymentForm.getCustomerId());

            //TODO 远程调用service-coupon的CouponController里的useCoupon方法 得到优惠券金额
            couponAmount = couponFeignClient.useCoupon(useCouponForm).getData();
        }

        //5.更新账单中优惠后的金额
        //支付金额
        BigDecimal payAmount = orderPayVo.getPayAmount();
        if (null != couponAmount) {
            //TODO 远程调用service-order的OrderController里的updateCouponAmount方法 如果使用了优惠券，则更新order_bill表中的账单优惠后的金额
            Boolean isUpdate = orderInfoFeignClient.updateCouponAmount(orderPayVo.getOrderId(), couponAmount).getData();
            if (!isUpdate) {
                throw new GuiguException(ResultCodeEnum.DATA_ERROR);
            }
            //当前支付金额 = 支付金额 - 优惠券金额
            payAmount = payAmount.subtract(couponAmount);
        }


        //6.封装微信下单对象，微信支付只关注以下订单属性
        PaymentInfoForm paymentInfoForm = new PaymentInfoForm();
        paymentInfoForm.setCustomerOpenId(customerOpenId);//乘客微信openId
        paymentInfoForm.setDriverOpenId(driverOpenId);//司机微信openId
        paymentInfoForm.setOrderNo(orderPayVo.getOrderNo());//订单号
        paymentInfoForm.setAmount(payAmount);//修改当前的支付金额(使用优惠券后)
        paymentInfoForm.setContent(orderPayVo.getContent());//订单描述
        paymentInfoForm.setPayWay(1);//支付方式1- 微信支付

        //7.远程调用service-payment的WxPayController里的createWxPayment方法，调用微信支付接口，获取微信支付预支付信息
        WxPrepayVo wxPrepayVo = wxPayFeignClient.createWxPayment(paymentInfoForm).getData();

        return wxPrepayVo;
    }


    @Override
    public Boolean queryPayStatus(String orderNo) {
        return wxPayFeignClient.queryPayStatus(orderNo).getData();
    }

}
