package com.atguigu.daijia.driver.service.impl;

import com.alibaba.fastjson2.JSON;
import com.atguigu.daijia.common.constant.SystemConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.util.LocationUtil;
import com.atguigu.daijia.dispatch.client.NewOrderFeignClient;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.driver.service.OrderService;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.map.client.MapFeignClient;
import com.atguigu.daijia.model.entity.driver.DriverInfo;
import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.map.CalculateDrivingLineForm;
import com.atguigu.daijia.model.form.order.OrderFeeForm;
import com.atguigu.daijia.model.form.order.StartDriveForm;
import com.atguigu.daijia.model.form.order.UpdateOrderBillForm;
import com.atguigu.daijia.model.form.order.UpdateOrderCartForm;
import com.atguigu.daijia.model.form.rules.FeeRuleRequestForm;
import com.atguigu.daijia.model.form.rules.ProfitsharingRuleRequestForm;
import com.atguigu.daijia.model.form.rules.RewardRuleRequestForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.driver.DriverInfoVo;
import com.atguigu.daijia.model.vo.map.DrivingLineVo;
import com.atguigu.daijia.model.vo.map.OrderLocationVo;
import com.atguigu.daijia.model.vo.map.OrderServiceLastLocationVo;
import com.atguigu.daijia.model.vo.order.*;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponseVo;
import com.atguigu.daijia.model.vo.rules.ProfitsharingRuleResponseVo;
import com.atguigu.daijia.model.vo.rules.RewardRuleResponseVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.atguigu.daijia.rules.client.FeeRuleFeignClient;
import com.atguigu.daijia.rules.client.ProfitsharingRuleFeignClient;
import com.atguigu.daijia.rules.client.RewardRuleFeignClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;
    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;

    @Override
    public Integer getOrderStatus(Long orderId) {
        //根据orderId查询订单状态
        return orderInfoFeignClient.getOrderStatus(orderId).getData();
    }

    @Autowired
    private NewOrderFeignClient newOrderFeignClient;

    @Override
    public List<NewOrderDataVo> findNewOrderQueueData(Long driverId) {
        //根据driverId查询新订单队列数据
        return newOrderFeignClient.findNewOrderQueueData(driverId).getData();
    }


    @Override
    public Boolean robNewOrder(Long driverId, Long orderId) {
        return orderInfoFeignClient.robNewOrder(driverId, orderId).getData();
    }

    @Override
    public CurrentOrderInfoVo searchDriverCurrentOrder(Long driverId) {
        return orderInfoFeignClient.searchDriverCurrentOrder(driverId).getData();
    }

    @Override
    public OrderInfoVo getOrderInfo(Long orderId, Long driverId) {
        //订单信息
        OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderId).getData();

        //数据一致性：确保只有合法的司机可以操作特定的订单。如果司机ID不一致，说明请求可能是伪造的或非法的，从而保护系统的数据完整性和安全性。
        //防止错误操作：避免错误的操作导致数据混乱或错误。例如，一个司机不应该能够操作另一个司机负责的订单。
        //提高系统安全性：通过验证司机ID，可以防止未授权的访问和操作，从而提高系统的安全性。
        //日志记录：抛出异常时，可以记录详细的错误信息，便于后续的审计和问题追踪。
        //用户体验：通过抛出异常，可以及时反馈给调用方请求是非法的，从而避免系统出现不可预期的行为，提高用户体验。
        //业务逻辑的严谨性：确保业务逻辑的严谨性，符合业务规则，避免因逻辑错误导致的系统问题。
        if(orderInfo.getDriverId().longValue() != driverId.longValue()) {
            throw new GuiguException(ResultCodeEnum.ILLEGAL_REQUEST);//(205, "非法请求")
        }

        //账单信息
        OrderBillVo orderBillVo = null;

        //分账信息
        OrderProfitsharingVo orderProfitsharing = null;
//        if (orderInfo.getStatus().intValue() >= OrderStatus.END_SERVICE.getStatus().intValue()) { 注释
            //获取账单信息
            orderBillVo = orderInfoFeignClient.getOrderBillInfo(orderId).getData();

            //获取分账信息
            orderProfitsharing = orderInfoFeignClient.getOrderProfitsharing(orderId).getData();
//        }

        //封装订单信息,只给司机返回部分信息
        OrderInfoVo orderInfoVo = new OrderInfoVo();
        orderInfoVo.setOrderId(orderId);
        BeanUtils.copyProperties(orderInfo, orderInfoVo);
        orderInfoVo.setOrderBillVo(orderBillVo);
        orderInfoVo.setOrderProfitsharingVo(orderProfitsharing);

        //TODO 自己新增的; 要往订单信息中添加司机信息,不然账单显示不出来
        DriverInfoVo driverInfo = driverInfoFeignClient.getDriverInfo(orderInfo.getDriverId()).getData();

        orderInfoVo.setDriverInfoVo(driverInfo);

        log.info("OrderServiceImpl.getOrderInfo订单信息:{}", JSON.toJSONString(orderInfoVo));

        return orderInfoVo;
    }


    @Autowired
    private MapFeignClient mapFeignClient;

    //计算驾驶路线
    @Override
    public DrivingLineVo calculateDrivingLine(CalculateDrivingLineForm calculateDrivingLineForm) {
        return mapFeignClient.calculateDrivingLine(calculateDrivingLineForm).getData();
    }

    //司机到达起点
    @Override
    public Boolean driverArriveStartLocation(Long orderId, Long driverId) {
        //防止刷单，计算司机的经纬度与代驾的起始经纬度是否在1公里范围内
        //调用service-order的OrderInfoController的方法,根据订单id直接从MybatisPlus里查找订单信息,找到上车点的位置
        OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderId).getData();

        //从redis "update:order:location"=${orderId} 获取司机的实时位置
        //远程调用service-map的LocationController的getCacheOrderLocation ("司机赶往代驾起始点：获取司机的经纬度位置")
        OrderLocationVo orderLocationVo = locationFeignClient.getCacheOrderLocation(orderId).getData();
        //司机的位置与代驾起始点位置的距离
        double distance = LocationUtil.getDistance(
                orderInfo.getStartPointLatitude().doubleValue(),
                orderInfo.getStartPointLongitude().doubleValue(),
                orderLocationVo.getLatitude().doubleValue(),
                orderLocationVo.getLongitude().doubleValue()
            );

        if(distance > SystemConstant.DRIVER_START_LOCATION_DISTION) {//如果司机位置与上车点超过1公里
            throw new GuiguException(ResultCodeEnum.DRIVER_START_LOCATION_DISTION_ERROR);//( 217, "距离代驾起始点1公里以内才能确认")
        }
        log.info("司机到达起点，司机id:{}, 订单id:{}, 与代驾出发点的距离:{}", driverId, orderId, distance);

        //司机到达起始点 更新订单状态码
        Boolean success = orderInfoFeignClient.driverArriveStartLocation(orderId, driverId).getData();
        log.info("司机到达起点，司机id:{}, 订单id:{}, 更新订单状态码:{}", driverId, orderId, success);

        return success;
    }

    //司机上传拍照车辆信息
    @Override
    public Boolean updateOrderCart(UpdateOrderCartForm updateOrderCartForm) {
        return orderInfoFeignClient.updateOrderCart(updateOrderCartForm).getData();
    }

    //司机开始代驾
    @Override
    public Boolean startDrive(StartDriveForm startDriveForm) {
        return orderInfoFeignClient.startDrive(startDriveForm).getData();
    }

    @Autowired
    private LocationFeignClient locationFeignClient;

    @Autowired
    private FeeRuleFeignClient feeRuleFeignClient;

    @Autowired
    private RewardRuleFeignClient rewardRuleFeignClient;

    @Autowired
    private ProfitsharingRuleFeignClient profitsharingRuleFeignClient;


    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    //多线程
    @SneakyThrows
    @Override
    public Boolean endDriveThread(OrderFeeForm orderFeeForm) {

        CompletableFuture<OrderInfo> orderInfoCompletableFuture =
                CompletableFuture.supplyAsync(() -> {

            //1.调用service-order的Controller直接通过mybatis-plus从数据库中获取订单信息
            OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderFeeForm.getOrderId()).getData();
            if (orderInfo.getDriverId().longValue() != orderFeeForm.getDriverId().longValue()) {
                throw new GuiguException(ResultCodeEnum.ARGUMENT_VALID_ERROR);//(210, "参数校验异常")
            }
            return orderInfo;
        }, threadPoolExecutor);


        CompletableFuture<OrderServiceLastLocationVo> orderServiceLastLocationVoCompletableFuture =
                CompletableFuture.supplyAsync(() -> {

            //2.防止刷单，计算司机的经纬度与代驾的终点经纬度是否在2公里范围内
            //远程调用service-map的LocationController的getOrderServiceLastLocation从mongodb中获取司机的位置
            OrderServiceLastLocationVo orderServiceLastLocationVo = locationFeignClient.getOrderServiceLastLocation(orderFeeForm.getOrderId()).getData();
            return orderServiceLastLocationVo;
        }, threadPoolExecutor);


        //合并两个异步任务
        CompletableFuture.allOf(orderInfoCompletableFuture, orderServiceLastLocationVoCompletableFuture);

        //获取两个异步任务的结果
        OrderInfo orderInfo = orderInfoCompletableFuture.get();//订单信息
        OrderServiceLastLocationVo orderServiceLastLocationVo = orderServiceLastLocationVoCompletableFuture.get();//司机的位置信息


        //计算司机的位置与代驾终点位置的距离
        double distance = LocationUtil.getDistance(
                orderInfo.getEndPointLatitude().doubleValue(),
                orderInfo.getEndPointLongitude().doubleValue(),
                orderServiceLastLocationVo.getLatitude().doubleValue(),
                orderServiceLastLocationVo.getLongitude().doubleValue());
        //注释掉,方便测试
//        if(distance > SystemConstant.DRIVER_END_LOCATION_DISTION) {//如果超过2公里
//            throw new GuiguException(ResultCodeEnum.DRIVER_END_LOCATION_DISTION_ERROR);//( 217, "距离代驾终点2公里以内才能确认")
//        }


        CompletableFuture<BigDecimal> realDistanceCompletableFuture =
                CompletableFuture.supplyAsync(() -> {

            //3.调用service-map的MapController的calculateOrderRealDistance方法,从mongodb中获得订单实际里程
//            BigDecimal realDistance = locationFeignClient.calculateOrderRealDistance(orderFeeForm.getOrderId()).getData();

            BigDecimal realDistance = orderInfo.getExpectDistance();//因为mongodb里没有存储订单的实际里程，所以设置为订单预计里程

            log.info("结束代驾，订单实际里程realDistance：{}", realDistance);
            return realDistance;
        }, threadPoolExecutor);


        CompletableFuture<FeeRuleResponseVo> feeRuleResponseVoCompletableFuture =
                realDistanceCompletableFuture.thenApplyAsync((realDistance) -> {

            //4.计算代驾实际费用
            FeeRuleRequestForm feeRuleRequestForm = new FeeRuleRequestForm();
            feeRuleRequestForm.setDistance(realDistance);
            feeRuleRequestForm.setStartTime(orderInfo.getStartServiceTime());


            long acceptTime = orderInfo.getAcceptTime().getTime();//司机接单时间
            long arriveTime = orderInfo.getArriveTime().getTime();//司机到达目的地的时间

            //4.1等候司机的时间
            Integer waitMinute = Math.abs((int) ((arriveTime - acceptTime) / (1000 * 60)));//毫秒除以1000后再除以60得到分钟数
            feeRuleRequestForm.setWaitMinute(waitMinute);
            log.info("结束代驾，费用参数：{}", JSON.toJSONString(feeRuleRequestForm));

            //4.2通过远程调用service-rules的FeeRuleController的calculateOrderFee方法，用Drools获得费用明细
            FeeRuleResponseVo feeRuleResponseVo = feeRuleFeignClient.calculateOrderFee(feeRuleRequestForm).getData();
            log.info("里程费 distanceFee: {}"+feeRuleResponseVo.getDistanceFee());
            log.info("费用明细：{}", JSON.toJSONString(feeRuleResponseVo));

            //订单总金额 需加上 路桥费、停车费、其他费用、乘客好处费
            BigDecimal totalAmount = feeRuleResponseVo.getTotalAmount();//总金额
            BigDecimal tollFee = orderFeeForm.getTollFee();//路桥费
            BigDecimal parkingFee = orderFeeForm.getParkingFee();//停车费
            BigDecimal otherFee = orderFeeForm.getOtherFee();//其他费用
            BigDecimal favourFee = orderInfo.getFavourFee();//乘客好处费

            //4.3计算全部的费用,再重新插入
            BigDecimal allAmount = totalAmount.add(tollFee).add(parkingFee).add(otherFee).add(favourFee);

            feeRuleResponseVo.setTotalAmount(allAmount);

            return feeRuleResponseVo;
        }, threadPoolExecutor);

        CompletableFuture<Long> orderNumCompletableFuture =
                CompletableFuture.supplyAsync(() -> {

            //5.计算系统奖励
            //5.1.获取订单数
            String startTime = new DateTime(orderInfo.getStartServiceTime()).toString("yyyy-MM-dd") + " 00:00:00";//开始服务时间 日期加时间
            //在 MySQL 中，DATETIME 格式的取值范围是从 0000-00-00 00:00:00 到 9999-12-31 23:59:59
            //endTime 被设置为 "yyyy-MM-dd 24:00:00" 格式，这在数据库查询中会导致错误，因为 24:00:00 在 MySQL 中是无效的 DATETIME 格式。
            //为了避免这种问题，应该在代码中将结束时间设置为第二天的 00:00:00 或者写成23:59:59。
            String endTime = new DateTime(orderInfo.getEndServiceTime()).toString("yyyy-MM-dd") + " 23:59:59";//结束服务时间 日期加时间

            //5.2调用service-order的OrderInfoController的getOrderNumByTime方法,从数据库中查询订单数
            Long orderNum = orderInfoFeignClient.getOrderNumByTime(startTime, endTime).getData();

            return orderNum;
        }, threadPoolExecutor);

        CompletableFuture<RewardRuleResponseVo> rewardRuleResponseVoCompletableFuture =
                orderNumCompletableFuture.thenApplyAsync((orderNum) -> {

            //6 封装平台奖励规则请求参数 参数包含订单开始时间、订单数
            RewardRuleRequestForm rewardRuleRequestForm = new RewardRuleRequestForm();
            rewardRuleRequestForm.setStartTime(orderInfo.getStartServiceTime());
            rewardRuleRequestForm.setOrderNum(orderNum);//订单数

            //6.1 调用service-rules的RewardRuleController的calculateOrderRewardFee方法,计算系统奖励
            RewardRuleResponseVo rewardRuleResponseVo = rewardRuleFeignClient.calculateOrderRewardFee(rewardRuleRequestForm).getData();
            log.info("结束代驾，系统奖励：{}", JSON.toJSONString(rewardRuleResponseVo));

            return rewardRuleResponseVo;
        }, threadPoolExecutor);


        CompletableFuture<ProfitsharingRuleResponseVo> profitsharingRuleResponseVoCompletableFuture =
                feeRuleResponseVoCompletableFuture.thenCombineAsync(
                        orderNumCompletableFuture, (feeRuleResponseVo, orderNum) -> {

                    BigDecimal totalAmount = feeRuleResponseVo.getTotalAmount();

                    //7.计算分账信息
                    //7.1封装分账规则请求表单 包含订单金额和当天完成的订单数
                    ProfitsharingRuleRequestForm profitsharingRuleRequestForm = new ProfitsharingRuleRequestForm();
                    profitsharingRuleRequestForm.setOrderAmount(totalAmount);
                    profitsharingRuleRequestForm.setOrderNum(orderNum);

                    //7.2调用service-rules的ProfitsharingRuleController的calculateOrderProfitsharingFee方法,计算分账信息
                    ProfitsharingRuleResponseVo profitsharingRuleResponseVo = profitsharingRuleFeignClient.calculateOrderProfitsharingFee(profitsharingRuleRequestForm).getData();
                    log.info("结束代驾，分账信息：{}", JSON.toJSONString(profitsharingRuleResponseVo));

                    return profitsharingRuleResponseVo;
                }, threadPoolExecutor);

        CompletableFuture.allOf(
                realDistanceCompletableFuture,
                feeRuleResponseVoCompletableFuture,
                orderNumCompletableFuture,
                rewardRuleResponseVoCompletableFuture,
                profitsharingRuleResponseVoCompletableFuture
        ).join();

        //获取异步任务的结果
        BigDecimal realDistance = realDistanceCompletableFuture.get();
        FeeRuleResponseVo feeRuleResponseVo = feeRuleResponseVoCompletableFuture.get();
        log.info("endDriveThread : distanceFee " + (feeRuleResponseVo.getDistanceFee()));
        RewardRuleResponseVo rewardRuleResponseVo = rewardRuleResponseVoCompletableFuture.get();
        ProfitsharingRuleResponseVo profitsharingRuleResponseVo = profitsharingRuleResponseVoCompletableFuture.get();

        //封装更新订单账单Bill相关实体对象
        UpdateOrderBillForm updateOrderBillForm = new UpdateOrderBillForm();
        updateOrderBillForm.setOrderId(orderFeeForm.getOrderId());
        updateOrderBillForm.setDriverId(orderFeeForm.getDriverId());
        //路桥费、停车费、其他费用
        updateOrderBillForm.setTollFee(orderFeeForm.getTollFee());
        updateOrderBillForm.setParkingFee(orderFeeForm.getParkingFee());
        updateOrderBillForm.setOtherFee(orderFeeForm.getOtherFee());
        //乘客好处费
        updateOrderBillForm.setFavourFee(orderInfo.getFavourFee());

        //实际里程
        updateOrderBillForm.setRealDistance(realDistance);

        //订单奖励信息
        BeanUtils.copyProperties(rewardRuleResponseVo, updateOrderBillForm);
        //代驾费用信息
        BeanUtils.copyProperties(feeRuleResponseVo, updateOrderBillForm);

        //分账相关信息
        BeanUtils.copyProperties(profitsharingRuleResponseVo, updateOrderBillForm);
//        updateOrderBillForm.setProfitsharingRuleId(profitsharingRuleResponseVo.getProfitsharingRuleId());//分账规则id 现在数据库中没有分账规则，所以注释
        log.info("结束代驾，更新账单信息：{}", JSON.toJSONString(updateOrderBillForm));


        //8.远程调用service-order的OrderInfoController的endService结束代驾更新账单,
        //①.更新order_info订单状态为(6,结束服务/未付款) ②.插入订单的实际order_bill账单数据 ③.向order_profitsharing表中插入订单的分账信息数据
        orderInfoFeignClient.endDrive(updateOrderBillForm);

        return true;
    }

    @Override
    public PageVo findDriverOrderPage(Long driverId, Long page, Long limit) {
        return orderInfoFeignClient.findDriverOrderPage(driverId, page, limit).getData();
    }


//    //原方法
//    @SneakyThrows
//    @Override
//    public Boolean endDrive(OrderFeeForm orderFeeForm) {
//        //1.调用service-order的Controller直接通过mybatis-plus从数据库中获取订单信息
//        OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderFeeForm.getOrderId()).getData();
//        if (orderInfo.getDriverId().longValue() != orderFeeForm.getDriverId().longValue()) {
//            throw new GuiguException(ResultCodeEnum.ARGUMENT_VALID_ERROR);//(210, "参数校验异常")
//        }
//
//
//        //2.防止刷单，计算司机的经纬度与代驾的终点经纬度是否在2公里范围内
//        //远程调用service-map的LocationController的getOrderServiceLastLocation从mongodb中获取司机的位置
//        OrderServiceLastLocationVo orderServiceLastLocationVo = locationFeignClient.getOrderServiceLastLocation(orderFeeForm.getOrderId()).getData();
//
//
//        //计算司机的位置与代驾终点位置的距离
//        double distance = LocationUtil.getDistance(
//                orderInfo.getEndPointLatitude().doubleValue(),
//                orderInfo.getEndPointLongitude().doubleValue(),
//                orderServiceLastLocationVo.getLatitude().doubleValue(),
//                orderServiceLastLocationVo.getLongitude().doubleValue());
//        //取消距离限制,方便测试
////        if(distance > SystemConstant.DRIVER_END_LOCATION_DISTION) {//如果超过2公里
////            throw new GuiguException(ResultCodeEnum.DRIVER_END_LOCATION_DISTION_ERROR);//( 217, "距离代驾终点2公里以内才能确认")
////        }
//
//
//        //3.调用service-map的MapController的calculateOrderRealDistance方法,从mongodb中获得订单实际里程
//        BigDecimal realDistance = locationFeignClient.calculateOrderRealDistance(orderFeeForm.getOrderId()).getData();
//
//        if (realDistance == null) {
//            realDistance = BigDecimal.ONE;//因为mongodb里没有存储订单的实际里程，所以默认设置为1km
//        }
//        log.info("结束代驾，订单实际里程：{}", realDistance);
//
//        //4.计算代驾实际费用
//        FeeRuleRequestForm feeRuleRequestForm = new FeeRuleRequestForm();
//        feeRuleRequestForm.setDistance(realDistance);
//        feeRuleRequestForm.setStartTime(orderInfo.getStartServiceTime());
//
//        long acceptTime = orderInfo.getAcceptTime().getTime();//司机接单时间
//        long arriveTime = orderInfo.getArriveTime().getTime();//司机到达目的地的时间
//
//        //4.1等候司机的时间
//        Integer waitMinute = Math.abs((int) ((arriveTime - acceptTime) / (1000 * 60)));//毫秒除以1000后再除以60得到分钟数
//        feeRuleRequestForm.setWaitMinute(waitMinute);
//        log.info("结束代驾，费用参数：{}", JSON.toJSONString(feeRuleRequestForm));
//
//        //4.2通过远程调用service-rules的FeeRuleController的calculateOrderFee方法，用Drools获得费用明细
//        FeeRuleResponseVo feeRuleResponseVo = feeRuleFeignClient.calculateOrderFee(feeRuleRequestForm).getData();
//
//
//        log.info("费用明细：{}", JSON.toJSONString(feeRuleResponseVo));
//
//        //订单总金额 需加上 路桥费、停车费、其他费用、乘客好处费
//        BigDecimal totalAmount = feeRuleResponseVo.getTotalAmount();//总金额
//        BigDecimal tollFee = orderFeeForm.getTollFee();//路桥费
//        BigDecimal parkingFee = orderFeeForm.getParkingFee();//停车费
//        BigDecimal otherFee = orderFeeForm.getOtherFee();//其他费用
//        BigDecimal favourFee = orderInfo.getFavourFee();//乘客好处费
//
//        //4.3计算全部的费用,再重新插入
//        BigDecimal allAmount = totalAmount.add(tollFee).add(parkingFee).add(otherFee).add(favourFee);
//        feeRuleResponseVo.setTotalAmount(allAmount);
//
//        //5.计算系统奖励
//        //5.1.获取订单数
//        String startTime = new DateTime(orderInfo.getStartServiceTime()).toString("yyyy-MM-dd") + " 00:00:00";//开始服务时间 日期加时间
//        String endTime = new DateTime(orderInfo.getEndServiceTime()).toString("yyyy-MM-dd") + " 24:00:00";//结束服务时间 日期加时间
//
//        //5.2调用service-order的OrderInfoController的getOrderNumByTime方法,从数据库中查询订单数
//        Long orderNum = orderInfoFeignClient.getOrderNumByTime(startTime, endTime).getData();
//
//
//        //6 封装平台奖励规则请求参数 参数包含订单开始时间、订单数
//        RewardRuleRequestForm rewardRuleRequestForm = new RewardRuleRequestForm();
//        rewardRuleRequestForm.setStartTime(orderInfo.getStartServiceTime());
//        rewardRuleRequestForm.setOrderNum(orderNum);//订单数
//
//        //6.1 调用service-rules的RewardRuleController的calculateOrderRewardFee方法,计算系统奖励
//        RewardRuleResponseVo rewardRuleResponseVo = rewardRuleFeignClient.calculateOrderRewardFee(rewardRuleRequestForm).getData();
//        log.info("结束代驾，系统奖励：{}", JSON.toJSONString(rewardRuleResponseVo));
//
//
//        //7.计算分账信息
//        //7.1封装分账规则请求表单 包含订单金额和当天完成的订单数
//        ProfitsharingRuleRequestForm profitsharingRuleRequestForm = new ProfitsharingRuleRequestForm();
//        profitsharingRuleRequestForm.setOrderAmount(totalAmount);
//        profitsharingRuleRequestForm.setOrderNum(orderNum);
//
//        //7.2调用service-rules的ProfitsharingRuleController的calculateOrderProfitsharingFee方法,计算分账信息
//        ProfitsharingRuleResponseVo profitsharingRuleResponseVo = profitsharingRuleFeignClient.calculateOrderProfitsharingFee(profitsharingRuleRequestForm).getData();
//        log.info("结束代驾，分账信息：{}", JSON.toJSONString(profitsharingRuleResponseVo));
//
//
//        //封装更新订单账单Bill相关实体对象
//        UpdateOrderBillForm updateOrderBillForm = new UpdateOrderBillForm();
//        updateOrderBillForm.setOrderId(orderFeeForm.getOrderId());
//        updateOrderBillForm.setDriverId(orderFeeForm.getDriverId());
//        //路桥费、停车费、其他费用
//        updateOrderBillForm.setTollFee(orderFeeForm.getTollFee());
//        updateOrderBillForm.setParkingFee(orderFeeForm.getParkingFee());
//        updateOrderBillForm.setOtherFee(orderFeeForm.getOtherFee());
//        //乘客好处费
//        updateOrderBillForm.setFavourFee(orderInfo.getFavourFee());
//
//        //实际里程
//        updateOrderBillForm.setRealDistance(realDistance);
//        //订单奖励信息
//        BeanUtils.copyProperties(rewardRuleResponseVo, updateOrderBillForm);
//        //代驾费用信息
//        BeanUtils.copyProperties(feeRuleResponseVo, updateOrderBillForm);
//
//        //分账相关信息
//        BeanUtils.copyProperties(profitsharingRuleResponseVo, updateOrderBillForm);
////        updateOrderBillForm.setProfitsharingRuleId(profitsharingRuleResponseVo.getProfitsharingRuleId());//分账规则id 现在数据库中没有分账规则，所以注释
//        log.info("结束代驾，更新账单信息：{}", JSON.toJSONString(updateOrderBillForm));
//
//
//        //8.远程调用service-order的OrderInfoController的endService结束代驾更新账单,
//        //①.更新order_info订单状态为(6,结束服务/未付款) ②.插入订单的实际order_bill账单数据 ③.向order_profitsharing表中插入订单的分账信息数据
//        orderInfoFeignClient.endDrive(updateOrderBillForm);
//
//        return true;
//    }

    @Override
    public Boolean sendOrderBillInfo(Long orderId, Long driverId) {
        return orderInfoFeignClient.sendOrderBillInfo(orderId, driverId).getData();
    }


}
