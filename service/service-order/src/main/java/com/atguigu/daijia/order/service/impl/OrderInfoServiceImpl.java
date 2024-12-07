package com.atguigu.daijia.order.service.impl;

import com.alibaba.fastjson2.JSON;
import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.model.entity.order.*;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.order.StartDriveForm;
import com.atguigu.daijia.model.form.order.UpdateOrderBillForm;
import com.atguigu.daijia.model.form.order.UpdateOrderCartForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.order.*;
import com.atguigu.daijia.order.mapper.OrderBillMapper;
import com.atguigu.daijia.order.mapper.OrderInfoMapper;
import com.atguigu.daijia.order.mapper.OrderProfitsharingMapper;
import com.atguigu.daijia.order.mapper.OrderStatusLogMapper;
import com.atguigu.daijia.order.service.OrderInfoService;
import com.atguigu.daijia.order.service.OrderMonitorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderStatusLogMapper orderStatusLogMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    //保存订单信息
    @Transactional(rollbackFor = {Exception.class})
    @Override
    public Long saveOrderInfo(OrderInfoForm orderInfoForm) {

        //1.把OrderInfoForm(乘客下单的临时订单信息)转换为OrderInfo(实际订单信息)(真正存入数据库的实体类)
        OrderInfo orderInfo = new OrderInfo();
        BeanUtils.copyProperties(orderInfoForm, orderInfo);

        //2.生成订单号和设置订单状态
        String orderNo = UUID.randomUUID().toString().replaceAll("-","");
        orderInfo.setStatus(OrderStatus.WAITING_ACCEPT.getStatus());//订单状态设置为 1 等待接单状态
        orderInfo.setOrderNo(orderNo);

        //3.TODO 向数据库插入订单信息
        orderInfoMapper.insert(orderInfo);

        //生成订单之后，调用下面的方法,向redission发送延迟消息
        this.sendDelayMessage(orderInfo.getId());

        //4.记录日志,调用log方法并向数据库插入订单日志
        this.log(orderInfo.getId(), orderInfo.getStatus());

        Long orderId = orderInfo.getId();

        //5.设置redis缓存，用于判断是否在等待(接单标识)，标识不存在了说明不在等待接单状态了,默认超时时间为15分钟
        redisTemplate.opsForValue().
                set(RedisConstant.ORDER_ACCEPT_MARK+orderId,// key为 "order:accept:mark:${orderId}"
                        "接单标识,订单号: "+orderId+" 正在接单",//value设置标识为0，等待接单状态 (此处设置什么都行)
                        RedisConstant.ORDER_ACCEPT_MARK_EXPIRES_TIME, TimeUnit.MINUTES);//设置超时时间为15分钟

        //6.返回订单id
        return orderInfo.getId();
    }

    //生成订单之后，发送延迟消息
    private void sendDelayMessage(Long orderId) {
        try{
            //1 创建一个阻塞队列
            RBlockingQueue<Object> blockingDueue = redissonClient.getBlockingQueue("queue_cancel");

            //2 把创建的队列放到延迟队列里面
            RDelayedQueue<Object> delayedQueue = redissonClient.getDelayedQueue(blockingDueue);

            //3 发送消息到延迟队列里面
            //设置过期时间 15分钟
            delayedQueue.offer(orderId.toString(),15,TimeUnit.MINUTES);

        }catch (Exception e) {
            e.printStackTrace();
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
    }

    public void log(Long orderId, Integer status) {

        OrderStatusLog orderStatusLog = new OrderStatusLog();
        orderStatusLog.setOrderId(orderId);
        orderStatusLog.setOrderStatus(status);
        orderStatusLog.setOperateTime(new Date());

        //TODO 向数据库插入订单日志
        orderStatusLogMapper.insert(orderStatusLog);
    }


    @Override
    public Integer getOrderStatus(Long orderId) {
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, orderId);

        queryWrapper.select(OrderInfo::getStatus);//查询状态
        //这行代码的作用是告诉 MyBatis-Plus 从数据库查询时只获取每个订单的状态字段，
        // 而不是加载整个订单信息。从而提高性能并减少不必要的数据传输。该操作通常在需要关注订单状态变化时非常有用。

        //从数据库查询订单状态
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);
        if(null == orderInfo) {
            //返回null，feign解析会抛出异常，给默认值，后续会用
            return OrderStatus.NULL_ORDER.getStatus();
        }
        return orderInfo.getStatus();
    }

//    @Transactional(rollbackFor = Exception.class)
//    @Override
//    public Boolean robNewOrder(Long driverId, Long orderId) {
//        //抢单成功或取消订单，都会删除该key，redis判断，减少数据库压力
//        if(!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
//            //抢单失败
//            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
//        }
//
//        //修改订单状态及司机id
//        //update order_info set status = 2, driver_id = #{driverId}, accept_time = now() where id = #{id}
//
////        //修改字段
////        OrderInfo orderInfo = new OrderInfo();
////        orderInfo.setId(orderId);
////        orderInfo.setStatus(OrderStatus.ACCEPTED.getStatus());//订单状态设置为 2 已接单状态
////        orderInfo.setAcceptTime(new Date());
////        orderInfo.setDriverId(driverId);
////
////        int rows = orderInfoMapper.updateById(orderInfo);
//
//
//
//        //修改订单状态及司机id(乐观锁)
//        //update order_info set status = 2, driver_id = #{driverId}, accept_time = now() where id = #{id} and status =1
//        //条件
//        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
//        queryWrapper.eq(OrderInfo::getId, orderId);
//
//        //查询status = 1等待接单状态的订单(乐观锁)
//        queryWrapper.eq(OrderInfo::getStatus, OrderStatus.WAITING_ACCEPT.getStatus());
//
//        //修改字段
//        OrderInfo orderInfo = new OrderInfo();
//        orderInfo.setStatus(OrderStatus.ACCEPTED.getStatus());//设置成2 已接单状态
//        orderInfo.setAcceptTime(new Date());
//        orderInfo.setDriverId(driverId);
//        int rows = orderInfoMapper.update(orderInfo, queryWrapper);
//
//        if(rows != 1) {
//            //抢单失败
//            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
//        }
//
//        //记录日志
//        this.log(orderId, orderInfo.getStatus());
//
//        //删除redis订单标识
//        redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK);
//
//        return true;
//    }
    @Autowired
    private RedissonClient redissonClient;

    //抢单
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean robNewOrder(Long driverId, Long orderId) {

        //抢单成功或取消订单，都会删除该key，redis判断，减少数据库压力
        if(!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK+orderId)) {//如果redis中没有该订单的标识
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }

        // 初始化分布式锁，创建一个RLock实例(需要注入RedissonClient)
        RLock lock = redissonClient.getLock(RedisConstant.ROB_NEW_ORDER_LOCK+orderId);
        try {
            /**
             * TryLock是一种非阻塞式的分布式锁，实现原理：Redis的SETNX命令
             * 参数：
             *     waitTime：等待获取锁的时间
             *     leaseTime：加锁的时间
             */
            //第一个参数是等待时间，第二个参数是过期时间，都设置为1秒
            boolean flag = lock.tryLock(RedisConstant.ROB_NEW_ORDER_LOCK_WAIT_TIME,
                    RedisConstant.ROB_NEW_ORDER_LOCK_LEASE_TIME, TimeUnit.SECONDS);

            //获取到锁
            if (flag){
                //二次判断，防止重复抢单
                //如果redis中没有该订单的标识
                if(!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK+orderId)) {
                    //抢单失败
                    throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
                }

                //修改订单状态
                //update order_info set status = 2, driver_id = #{driverId} where id = #{id}
                //修改字段
                OrderInfo orderInfo = new OrderInfo();
                orderInfo.setId(orderId);
                orderInfo.setStatus(OrderStatus.ACCEPTED.getStatus());//订单状态设置为(2, "已接单")
                orderInfo.setAcceptTime(new Date());
                orderInfo.setDriverId(driverId);
                //根据订单id,在数据库中进行更新(只能更新自己的订单)
                int rows = orderInfoMapper.updateById(orderInfo);
                if(rows != 1) {
                    //抢单失败
                    throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);//( 217, "抢单失败")
                }

                //调用前面的log方法,向数据库中记录日志
                this.log(orderId, orderInfo.getStatus());

                //删除redis订单标识
                redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK+orderId);
            }
        } catch (InterruptedException e) {
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);

        } finally {
            try{
                if (lock.isLocked()){//如果锁还存在
                    lock.unlock();//释放锁
                }
            }catch (Exception e){
                throw new GuiguException(ResultCodeEnum.LOCK_UNLOCK_ERROR);
            }
        }
        return true;
    }


    //乘客端查询正在进行的订单
    @Override
    public CurrentOrderInfoVo searchCustomerCurrentOrder(Long customerId) {
        //1. 查询乘客正在进行的订单
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getCustomerId, customerId);

        //2.封装订单的状态的数组(状态为正在进行中的订单,状态码为 2,3,4,5,6,7)
        //乘客端支付完订单，乘客端主要流程就走完（如果订单状态为当前这些节点，乘客端就会调整到相应的页面处理逻辑）
        Integer[] statusArray = {

                OrderStatus.ACCEPTED.getStatus(),//2 已接单
                OrderStatus.DRIVER_ARRIVED.getStatus(),//3 司机已到达
                OrderStatus.UPDATE_CART_INFO.getStatus(),//4 更新代驾车辆信息
                OrderStatus.START_SERVICE.getStatus(),//5 开始服务
                OrderStatus.END_SERVICE.getStatus(),//6 结束服务
                OrderStatus.UNPAID.getStatus()//7 未支付
        };

        //3.in条件,查询状态数组中状态的订单
        //相当于查询 sql: select * from order_info where customer_id = #{customerId} and status in (2,3,4,5,6,7)
        queryWrapper.in(OrderInfo::getStatus, statusArray);

        //4.查询最新的一条记录:先根据id降序排列(从大到小)，然后取第一条
        queryWrapper.orderByDesc(OrderInfo::getId);

        //last用于在生成的 SQL 语句的最后追加 LIMIT 1 的条件,因为queryWrapper没有提供limit方法
        queryWrapper.last("limit 1");

        //5.从数据库中查询符合条件的订单
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);

        //6.封装返回对象 有三个值，订单状态，订单id，是否有正在进行的订单
        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();

        if(null != orderInfo) {//如果查到了订单

            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setIsHasCurrentOrder(true);//设置为有正在进行的订单

        } else {//如果查不到订单

            currentOrderInfoVo.setIsHasCurrentOrder(false);//设置为没有正在进行的订单
        }
        return currentOrderInfoVo;
    }

    //司机端查询正在进行的订单
    @Override
    public CurrentOrderInfoVo searchDriverCurrentOrder(Long driverId) {
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getDriverId, driverId);
        //司机发送完账单，司机端主要流程就走完（如果状态码为当前这些节点，司机端就会调整到相应的页面处理逻辑）
        Integer[] statusArray = {
                OrderStatus.ACCEPTED.getStatus(),//2 已接单
                OrderStatus.DRIVER_ARRIVED.getStatus(),//3 司机已到达
                OrderStatus.UPDATE_CART_INFO.getStatus(),//4 更新代驾车辆信息
                OrderStatus.START_SERVICE.getStatus(),//5 开始服务
                OrderStatus.END_SERVICE.getStatus()//6 结束服务
        };
        queryWrapper.in(OrderInfo::getStatus, statusArray);
        queryWrapper.orderByDesc(OrderInfo::getId);
        queryWrapper.last("limit 1");
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);
        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();
        if(null != orderInfo) {
            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setIsHasCurrentOrder(true);
        } else {
            currentOrderInfoVo.setIsHasCurrentOrder(false);
        }
        return currentOrderInfoVo;
    }

    //司机到达起始点
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean driverArriveStartLocation(Long orderId, Long driverId) {

        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, orderId);
        queryWrapper.eq(OrderInfo::getDriverId, driverId);

        OrderInfo updateOrderInfo = new OrderInfo();
        updateOrderInfo.setStatus(OrderStatus.DRIVER_ARRIVED.getStatus());//更改订单状态为 3, "司机已到达"
        updateOrderInfo.setArriveTime(new Date());//司机到达时间修改为当前时间

        //根据订单id和司机id,在数据库中进行更新(只能更新自己的订单)
        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);

        if(row == 1) {
            //记录日志
            this.log(orderId, OrderStatus.DRIVER_ARRIVED.getStatus());
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }

    //更新代驾车辆信息
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean updateOrderCart(UpdateOrderCartForm updateOrderCartForm) {

        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, updateOrderCartForm.getOrderId());
        queryWrapper.eq(OrderInfo::getDriverId, updateOrderCartForm.getDriverId());

        OrderInfo updateOrderInfo = new OrderInfo();
        BeanUtils.copyProperties(updateOrderCartForm, updateOrderInfo);

        updateOrderInfo.setStatus(OrderStatus.UPDATE_CART_INFO.getStatus());//更改订单状态为 4, "更新代驾车辆信息"

        //只能更新自己的订单
        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);
        if(row == 1) {
            //调用前面的log方法,记录日志到数据库
            this.log(updateOrderCartForm.getOrderId(), OrderStatus.UPDATE_CART_INFO.getStatus());
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }

    @Autowired
    private OrderMonitorService orderMonitorService;

    //开始代驾
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean startDrive(StartDriveForm startDriveForm) {

        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, startDriveForm.getOrderId());//订单id
        queryWrapper.eq(OrderInfo::getDriverId, startDriveForm.getDriverId());//司机id

        OrderInfo updateOrderInfo = new OrderInfo();
        updateOrderInfo.setStatus(OrderStatus.START_SERVICE.getStatus());//(5, "开始服务"),
        updateOrderInfo.setStartServiceTime(new Date());//开始服务时间修改为当前时间

        //只能更新自己的订单
        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);

        if(row == 1) {
            //调用前面的log方法,记录日志到数据库
            this.log(startDriveForm.getOrderId(), OrderStatus.START_SERVICE.getStatus());

        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);//(204, "数据更新失败")
        }

        //初始化订单监控统计数据
        OrderMonitor orderMonitor = new OrderMonitor();
        orderMonitor.setOrderId(startDriveForm.getOrderId());

        //调用OrderMonitorService的saveOrderMonitor方法订单监控服务，向mysql数据库中保存订单的监控数据
        orderMonitorService.saveOrderMonitor(orderMonitor);

        return true;
    }

    @Override
    public Long getOrderNumByTime(String startTime, String endTime) {

        //开始时间ge(大于等于)startTime,结束时间lt(小于)endTime
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.ge(OrderInfo::getStartServiceTime, startTime);
        queryWrapper.lt(OrderInfo::getStartServiceTime, endTime);

        //查询订单数量
        Long count = orderInfoMapper.selectCount(queryWrapper);

        return count;

    }

    @Autowired
    private OrderBillMapper orderBillMapper;

    @Autowired
    private OrderProfitsharingMapper orderProfitsharingMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean endDrive(UpdateOrderBillForm updateOrderBillForm) {
        //更新订单信息
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, updateOrderBillForm.getOrderId());
        queryWrapper.eq(OrderInfo::getDriverId, updateOrderBillForm.getDriverId());

        //更新字段
        OrderInfo updateOrderInfo = new OrderInfo();
        updateOrderInfo.setStatus(OrderStatus.END_SERVICE.getStatus());//(6, "结束服务,未付款")
        updateOrderInfo.setRealAmount(updateOrderBillForm.getTotalAmount());//总金额(实际费用)
        updateOrderInfo.setFavourFee(updateOrderBillForm.getFavourFee());//顾客好处费
        updateOrderInfo.setEndServiceTime(new Date());//结束服务时间
        updateOrderInfo.setRealDistance(updateOrderBillForm.getRealDistance());//实际里程
        log.info("endDrive: 实际里程 realDistance:{}"+updateOrderBillForm.getRealDistance());
        log.info("endDrive: 实际费用 realAmount:{}"+updateOrderInfo.getRealAmount());

        //只能更新自己的订单
        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);


        if(row == 1) {
            //记录日志到数据库
            this.log(updateOrderBillForm.getOrderId(), OrderStatus.END_SERVICE.getStatus());//"6,结束服务"

            //插入实际账单数据
            OrderBill orderBill = new OrderBill();
            BeanUtils.copyProperties(updateOrderBillForm, orderBill);
            orderBill.setOrderId(updateOrderBillForm.getOrderId());
            orderBill.setPayAmount(orderBill.getTotalAmount());
            log.info("endDrive: 实际账单 orderBill:"+orderBill.toString());

            //向数据库中插入订单的实际账单数据
            orderBillMapper.insert(orderBill);

            //插入分账信息数据
            OrderProfitsharing orderProfitsharing = new OrderProfitsharing();
            orderProfitsharing.setRuleId(updateOrderBillForm.getProfitsharingRuleId());//随便设置一个分账规则ID 此处如果把mysql的非空约束去掉,就可以省略
            BeanUtils.copyProperties(updateOrderBillForm, orderProfitsharing);
            orderProfitsharing.setOrderId(updateOrderBillForm.getOrderId());
            orderProfitsharing.setRuleId(updateOrderBillForm.getProfitsharingRuleId());//分账规则ID, 此处如果把mysql的非空约束去掉,就可以省略
            orderProfitsharing.setStatus(1); //此处如果把mysql的非空约束去掉,就可以省略

            //向daijia_order数据库的order_profitsharing表中插入订单的分账信息数据
            orderProfitsharingMapper.insert(orderProfitsharing);


        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }


    @Override
    public PageVo findCustomerOrderPage(Page<OrderInfo> pageParam, Long customerId) {

        //返回一个IPage对象，里面包含了分页信息和查询出来的订单信息
        IPage<OrderListVo> pageInfo = orderInfoMapper.selectCustomerOrderPage(pageParam, customerId);
        //数据列表,总页数,总条目数
        PageVo pageVo = new PageVo(pageInfo.getRecords(), pageInfo.getPages(), pageInfo.getTotal());
        return pageVo;
    }


    @Override
    public PageVo findDriverOrderPage(Page<OrderInfo> pageParam, Long driverId) {
        IPage<OrderListVo> pageInfo = orderInfoMapper.selectDriverOrderPage(pageParam, driverId);
        return new PageVo(pageInfo.getRecords(), pageInfo.getPages(), pageInfo.getTotal());
    }

    //根据订单id查询订单的账单信息
    @Override
    public OrderBillVo getOrderBillInfo(Long orderId) {

        OrderBill orderBill = orderBillMapper.selectOne(new LambdaQueryWrapper<OrderBill>().eq(OrderBill::getOrderId, orderId));
        OrderBillVo orderBillVo = new OrderBillVo();
        if(null == orderBill) {
            throw new IllegalArgumentException("orderBill object must not be null");
        }
        BeanUtils.copyProperties(orderBill, orderBillVo);

        //返回订单账单信息
        return orderBillVo;
    }

    //根据订单id查询订单的分账信息
    @Override
    public OrderProfitsharingVo getOrderProfitsharing(Long orderId) {

        OrderProfitsharing orderProfitsharing = orderProfitsharingMapper.selectOne(new LambdaQueryWrapper<OrderProfitsharing>().eq(OrderProfitsharing::getOrderId, orderId));
        OrderProfitsharingVo orderProfitsharingVo = new OrderProfitsharingVo();
        if(null == orderProfitsharing) {
            throw new IllegalArgumentException("orderProfitsharing object must not be null");
        }
        BeanUtils.copyProperties(orderProfitsharing, orderProfitsharingVo);


        return orderProfitsharingVo;
    }

    //发送账单信息
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean sendOrderBillInfo(Long orderId, Long driverId) {

        //更新订单信息
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, orderId);
        queryWrapper.eq(OrderInfo::getDriverId, driverId);
        //更新字段
        OrderInfo updateOrderInfo = new OrderInfo();
        updateOrderInfo.setStatus(OrderStatus.UNPAID.getStatus());//修改订单状态为 (7, "待付款")

        log.info("sendOrderBillInfo: 发送账单信息 OrderInfo:"+updateOrderInfo.toString());

        //只能更新自己的订单
        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);

        if(row == 1) {
            //向数据库插入订单日志
            this.log(orderId, OrderStatus.UNPAID.getStatus());
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);//(204, "数据更新失败")
        }
        return true;
    }


    //根据订单号和用户id查询订单支付信息
    @Override
    public OrderPayVo getOrderPayVo(String orderNo, Long customerId) {

        OrderPayVo orderPayVo = orderInfoMapper.selectOrderPayVo(orderNo, customerId);

        if(null != orderPayVo) {
            String content = "代驾路线从 " + orderPayVo.getStartLocation() + " 到 " + orderPayVo.getEndLocation();
            orderPayVo.setContent(content);
        }
        return orderPayVo;
    }

    //更新订单支付状态
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean updateOrderPayStatus(String orderNo) {
        //查询订单，判断订单状态，如果已更新支付状态，直接返回
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getOrderNo, orderNo);
        queryWrapper.select(OrderInfo::getId, OrderInfo::getDriverId, OrderInfo::getStatus);

        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);

        if(null == orderInfo || orderInfo.getStatus().intValue() == OrderStatus.PAID.getStatus().intValue()) {//(8, "已付款")
            return true;
        }

        //更新订单状态
        LambdaQueryWrapper<OrderInfo> updateQueryWrapper = new LambdaQueryWrapper<>();
        updateQueryWrapper.eq(OrderInfo::getOrderNo, orderNo);
        //更新字段
        OrderInfo updateOrderInfo = new OrderInfo();
        updateOrderInfo.setStatus(OrderStatus.PAID.getStatus());//(8, "已付款")
        updateOrderInfo.setPayTime(new Date());

        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);

        if(row == 1) {
            //记录日志
            this.log(orderInfo.getId(), OrderStatus.PAID.getStatus());
        } else {
            log.error("订单支付回调更新订单状态失败，订单号为：" + orderNo);
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }

    //根据订单号查询司机订单的系统奖励费用
    @Override
    public OrderRewardVo getOrderRewardFee(String orderNo) {

        //1.根据订单号查询订单表
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<OrderInfo>();
        queryWrapper.eq(OrderInfo::getOrderNo, orderNo);
        queryWrapper.select(OrderInfo::getId, OrderInfo::getDriverId);
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);

        //2.根据订单id查询系统奖励表
        LambdaQueryWrapper<OrderBill> queryWrapper1 = new LambdaQueryWrapper<OrderBill>();
        queryWrapper1.eq(OrderBill::getOrderId, orderInfo.getId());
        queryWrapper1.select(OrderBill::getRewardFee);
        OrderBill orderBill = orderBillMapper.selectOne(queryWrapper1);

        OrderRewardVo orderRewardVo = new OrderRewardVo();
        orderRewardVo.setOrderId(orderInfo.getId());
        orderRewardVo.setDriverId(orderInfo.getDriverId());
        orderRewardVo.setRewardFee(orderBill.getRewardFee());

        return orderRewardVo;
    }

    //调用方法取消订单
    @Override
    public void orderCancel(long orderId) {
        //orderId查询订单信息
        OrderInfo orderInfo = orderInfoMapper.selectById(orderId);

        //判断 如果订单状态为 (1, "等待接单")
        if(orderInfo.getStatus()==OrderStatus.WAITING_ACCEPT.getStatus()) {
            //修改订单状态：取消状态
            orderInfo.setStatus(OrderStatus.CANCEL_ORDER.getStatus());//把订单状态改为(-1, "未接单取消订单")
            int rows = orderInfoMapper.updateById(orderInfo);
            if(rows == 1) {
                //删除redis接单标识
                redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK + orderId);}
        }

    }

    //更新订单优惠后的实际金额 向order_bill表中插入优惠后的实际金额
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean updateCouponAmount(Long orderId, BigDecimal couponAmount) {

        int row = orderBillMapper.updateCouponAmount(orderId, couponAmount);

        if(row != 1) {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }

}
