package com.atguigu.daijia.dispatch.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.dispatch.mapper.OrderJobMapper;
import com.atguigu.daijia.dispatch.service.NewOrderService;
import com.atguigu.daijia.dispatch.xxl.client.XxlJobClient;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.model.entity.dispatch.OrderJob;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.vo.dispatch.NewOrderTaskVo;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.order.NewOrderDataVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class NewOrderServiceImpl implements NewOrderService {

    @Autowired
    private XxlJobClient xxlJobClient;

    @Autowired
    private OrderJobMapper orderJobMapper;

    //新增订单任务，并启动定时任务
    @Transactional(rollbackFor = Exception.class)//事务注解，用于事务回滚
    @Override
    public Long addAndStartTask(NewOrderTaskVo newOrderTaskVo) {
        //1.查询订单任务是否存在
        LambdaQueryWrapper<OrderJob> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderJob::getOrderId, newOrderTaskVo.getOrderId());
        OrderJob orderJob = orderJobMapper.selectOne(queryWrapper);

        if(orderJob == null) {
            //2.新增订单任务
            //调用JobHandler的newOrderTaskHandler任务,传入参数
            Long jobId = xxlJobClient.addAndStart(
                    "newOrderTaskHandler",//执行任务job的方法名
                    "",//参数
                    "0 0/1 * * * ?", //执行corn表达式，每隔一分钟执行一次
                    "新订单任务,订单id："+newOrderTaskVo.getOrderId());//订单描述

            log.info("newOrderTaskHandler新增订单任务成功,订单id: "+newOrderTaskVo.getOrderId()+",任务id:", jobId);

            //3.记录订单与任务的关联信息
            orderJob = new OrderJob();
            orderJob.setOrderId(newOrderTaskVo.getOrderId());//传入订单id
            orderJob.setJobId(jobId);//传入任务id
            orderJob.setParameter(JSONObject.toJSONString(newOrderTaskVo));//把NewOrderTaskVo转成json字符串,传入OrderJob的parameter字段

            //4.存入daijia_dispatch 的order_job表中，记录 orderId(订单号)和jobId(任务号)以及Parameter(参数,即NewOrderTaskVo)的关联信息
            orderJobMapper.insert(orderJob);
        }

        //5.返回任务id
        return orderJob.getJobId();
    }

    @Autowired
    private LocationFeignClient locationFeignClient;

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;

    @Autowired
    private RedisTemplate redisTemplate;

    //由JobHandler调用,执行任务:搜索附近代驾司机
    @Override
    public void executeTask(Long jobId) {
        //1.根据jobId查询数据库,获取OrderJob(订单任务关联信息),查看当前任务是否已创建
        LambdaQueryWrapper<OrderJob> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderJob::getJobId, jobId);
        OrderJob orderJob = orderJobMapper.selectOne(queryWrapper);

        //1.1如果任务没有创建，则不执行了
        if(null == orderJob) {
            return ;
        }

        //1.2 TODO 从OrderJob的parameter字段中获取NewOrderTaskVo对象,并将json字符串转成NewOrderTaskVo对象
        NewOrderTaskVo newOrderTaskVo = JSONObject.parseObject(orderJob.getParameter(), NewOrderTaskVo.class);
        Long orderId = newOrderTaskVo.getOrderId();

        //2查询订单状态，如果该订单还在接单状态，继续执行；如果不在接单状态，则停止定时调度

        //2.1 TODO 远程调用service-order的getOrderStatus方法,根据orderId从数据库中查询订单状态
        Integer orderStatus = orderInfoFeignClient.getOrderStatus(orderId).getData();

        //2.2如果订单状态不为等待接单状态，停止定时调度
        if(orderStatus.intValue() != OrderStatus.WAITING_ACCEPT.getStatus().intValue()) {//WAITING_ACCEPT = 1 等待接单

            //调用XxlJobClient的stopJob方法，停止定时调度
            xxlJobClient.stopJob(jobId);
            log.info("停止任务调度: ", JSON.toJSONString(newOrderTaskVo));
            return ;
        }

        //3.创建SearchNearByDriverForm对象,设置司机的经度,纬度,以及乘客与司机的距离
        SearchNearByDriverForm searchNearByDriverForm = new SearchNearByDriverForm();
        searchNearByDriverForm.setLongitude(newOrderTaskVo.getStartPointLongitude());
        searchNearByDriverForm.setLatitude(newOrderTaskVo.getStartPointLatitude());
        searchNearByDriverForm.setMileageDistance(newOrderTaskVo.getExpectDistance());

        //4.TODO 远程调用service-map的searchNearByDriver方法,根据司机的经度,纬度,以及乘客与司机的距离，搜索附近的司机,获取满足可以接单的司机列表List
        List<NearByDriverVo> nearByDriverVoList = locationFeignClient.searchNearByDriver(searchNearByDriverForm).getData();

        //给司机派发订单信息
        //5. ①遍历司机列表，给每个司机派发订单信息(把司机id添加进新订单的临时set集合里); ②为每个司机创建订单的临时队列,存储新订单信息
        nearByDriverVoList.forEach( nearByDriverVo -> { //这里的nearByDriverVo是上面List<NearByDriverVo>中的元素，即NearByDriverVo对象

            //5.1 被遍历的List集合中的NearByDriverVo对象中有driverId(司机id)和distance(距离司机的距离)属性
            Long driverId = nearByDriverVo.getDriverId();//从遍历的NearByDriverVo对象中获取司机id
            BigDecimal distance = nearByDriverVo.getDistance();//从遍历的NearByDriverVo对象中获取距离司机的距离

            //5.2 设置订单Set集合的key,key的后缀为订单id
            String repeatKey = RedisConstant.DRIVER_ORDER_REPEAT_LIST+orderId;//key为  driver:order:repeat:list:${OrderId}

            //5.3 判断司机id是否在订单set中 (set不可以重复，所以用set判断是否重复)
            boolean isMember = redisTemplate.opsForSet().isMember(repeatKey,driverId);

            //5.4 如果不在，则推送订单信息
            if(!isMember) {

                //5.5 把订单信息推送给满足条件的司机,用redis的set集合来实现,防止重复推送订单信息;即一个订单set里有多个司机id
                redisTemplate.opsForSet().add(repeatKey,driverId); //此时redis中的Set集合为 driver:order:repeat:list:${OrderId} = "${DriverId}"

                //5.6 设置过期时间：15分钟，新订单15分钟没人接单自动取消
                redisTemplate.expire(repeatKey, RedisConstant.DRIVER_ORDER_REPEAT_LIST_EXPIRES_TIME, TimeUnit.MINUTES);//15分钟过期

                //5.7 创建新订单数据对象NewOrderDataVo
                NewOrderDataVo newOrderDataVo = new NewOrderDataVo();
                BeanUtils.copyProperties(newOrderTaskVo, newOrderDataVo);
                newOrderDataVo.setDistance(nearByDriverVo.getDistance());

                String orderDataVo = JSONObject.toJSONString(newOrderDataVo);//JSON.toJSONString() 把对象转换成Json字符串


                //5.8 设置司机的订单的临时队列的key
                String key = RedisConstant.DRIVER_ORDER_TEMP_LIST+driverId;//key为 driver:order:temp:list:${DriverId}

                //5.9 将消息保存到司机的订单的临时队列里面，司机接单了会定时轮询到他的临时队列获取订单消息
                redisTemplate.opsForList().leftPush(key, orderDataVo);//左侧入队 队列(先进先出)

                //5.10 设置过期时间，防止订单消息一直在队列中堆积. 过期时间：1分钟，1分钟未消费，自动过期. 注：司机端开启接单，前端每5秒（远小于1分钟）拉取1次“司机临时队列”里面的新订单消息
                redisTemplate.expire(key, RedisConstant.DRIVER_ORDER_TEMP_LIST_EXPIRES_TIME, TimeUnit.MINUTES);//设置队列1分钟过期

                log.info("该新订单信息已放入司机临时队列: ", orderDataVo); //JSON.toJSONString() 把对象转换成Json字符串
            }
        });
    }

    //查询司机的临时队列，获取新订单信息
    @Override
    public List<NewOrderDataVo> findNewOrderQueueData(Long driverId) {

        //1.创建一个list集合
        List<NewOrderDataVo> list = new ArrayList<>();
        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST+driverId;//key为 "driver:order:temp:list:'DriverId' "

        //2.判断容器是否为空
        long size = redisTemplate.opsForList().size(key);
        if(size > 0) {
            for(int i=0; i<size; i++) {

                //3.从司机的临时队列里面获取订单消息,因为是LeftPush左侧入队,所以LeftPop先出队
                String content = (String)redisTemplate.opsForList().leftPop(key);

                //4.使用JSON.parseObject方法把json字符串转成NewOrderDataVo对象
                NewOrderDataVo newOrderDataVo = JSONObject.parseObject(content, NewOrderDataVo.class);

                log.info("从司机的临时队列获取订单消息: ", content);

                //5.向list中添加订单消息
                list.add(newOrderDataVo);
            }
        }
        //6.返回list集合
        return list;
    }

    //清空司机的临时队列，防止重复推送订单信息
    @Override
    public Boolean clearNewOrderQueueData(Long driverId) {

        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST+driverId;
        //直接删除，司机开启服务后，有新订单会自动创建容器
        redisTemplate.delete(key);
        return true;
    }

}
