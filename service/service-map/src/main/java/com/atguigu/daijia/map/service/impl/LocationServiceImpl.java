package com.atguigu.daijia.map.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.constant.SystemConstant;
import com.atguigu.daijia.common.util.LocationUtil;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.map.repository.OrderServiceLocationRepository;
import com.atguigu.daijia.map.service.LocationService;
import com.atguigu.daijia.model.entity.driver.DriverSet;
import com.atguigu.daijia.model.entity.map.OrderServiceLocation;
import com.atguigu.daijia.model.form.map.OrderServiceLocationForm;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.form.map.UpdateDriverLocationForm;
import com.atguigu.daijia.model.form.map.UpdateOrderLocationForm;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.map.OrderLocationVo;
import com.atguigu.daijia.model.vo.map.OrderServiceLastLocationVo;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class LocationServiceImpl implements LocationService {

    @Autowired
    private RedisTemplate redisTemplate;

    //(下单前)更新司机位置
    @Override
    public Boolean updateDriverLocation(UpdateDriverLocationForm updateDriverLocationForm) {
        /**
         *  Redis GEO 主要用于存储地理位置信息，并对存储的信息进行相关操作，该功能在 Redis 3.2 版本新增。
         *  后续用在，乘客下单后寻找5公里范围内开启接单服务的司机，通过Redis GEO进行计算
         */
        Point point = new Point(
                updateDriverLocationForm.getLongitude().doubleValue(),//BigDecimal 转换成double类型
                updateDriverLocationForm.getLatitude().doubleValue());

        redisTemplate.opsForGeo().add(
                RedisConstant.DRIVER_GEO_LOCATION, // "driver:geo:location"
                point,  //要添加的坐标点
                updateDriverLocationForm.getDriverId().toString());//要添加的成员标识

        log.info("locationServiceImpl更新司机位置成功，driverId: " + updateDriverLocationForm.getDriverId()
                + ", longitude: " + updateDriverLocationForm.getLongitude()
                + ", latitude: " + updateDriverLocationForm.getLatitude());

        return true;
    }

    //(下单前)移除司机位置
    @Override
    public Boolean removeDriverLocation(Long driverId) {
        redisTemplate.opsForGeo().remove(RedisConstant.DRIVER_GEO_LOCATION, driverId.toString());
        return true;
    }

    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;

    //搜索附近的司机
    @Override
    public List<NearByDriverVo> searchNearByDriver(SearchNearByDriverForm searchNearByDriverForm) {
        //搜索经纬度位置5公里以内的司机

        //(1)定义经纬度点
        Point point = new Point(searchNearByDriverForm.getLongitude().doubleValue(), searchNearByDriverForm.getLatitude().doubleValue());
        //(2)先定义查询距离：5公里(系统配置)
        Distance distance = new Distance(SystemConstant.NEARBY_DRIVER_RADIUS, RedisGeoCommands.DistanceUnit.KILOMETERS);
        //(3)定义以point点为中心，distance为距离这么一个范围的一个圆
        Circle circle = new Circle(point, distance);

        //(4)定义GEO参数,
        RedisGeoCommands.GeoRadiusCommandArgs args =
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance() //包含距离
                .includeCoordinates() //包含坐标
                .sortAscending(); //排序：升序

        //1.使用GEORADIUS 获取附近范围内的所有的司机的信息,并加上自定义参数
        GeoResults<RedisGeoCommands.GeoLocation<String>> result =
                this.redisTemplate.opsForGeo().
                        radius(RedisConstant.DRIVER_GEO_LOCATION,//定义地理位置的key
                                circle,//定义圆形范围
                                args);//定义GEO参数

        //2.收集信息，把GeoResults集合存入list
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content
                = result.getContent();//List<? extends GeoResult<T>> results;得到的是GeoResult集合

        //3.返回计算后的信息
        List<NearByDriverVo> list = new ArrayList();

        if(!CollectionUtils.isEmpty(content)) {
            //4.使用迭代器遍历list集合,获取附近全部的司机的信息
            Iterator<GeoResult<RedisGeoCommands.GeoLocation<String>>> iterator = content.iterator();
            while (iterator.hasNext()) {
                //5.获取当前GeoResult元素,用iterator.next()遍历
                GeoResult<RedisGeoCommands.GeoLocation<String>> item = iterator.next();

                //6.TODO 获取GeoResult中的content,即GeoLocation,并取出里面的name值,即driverId
                Long driverId = Long.parseLong(item.getContent().getName());

                //7.获取当前司机与乘客距离
                BigDecimal currentDistance =
                        new BigDecimal(//GeoResult 中的 Distance 是由 Redis 在后台查询和计算的结果
                                item.getDistance().getValue())//Distance由opsForGeo().radius时传入的参数args设置,并由redis自己计算得到的距离值
                                .setScale(2, RoundingMode.HALF_UP);//保留两位小数 四舍五入

                log.info("司机："+driverId+",距离："+item.getDistance().getValue());

                //8. TODO 根据司机id远程调用DriverInfoController的方法获取司机接单设置参数
                DriverSet driverSet = driverInfoFeignClient.getDriverSet(driverId).getData();

                //9.根据司机的接单设置参数判断，acceptDistance==0：不限制;同时把当前司机与乘客距离与司机的接单设置参数的acceptDistance进行相减，如果小于0，则跳过当前司机
                BigDecimal acceptDistance = driverSet.getAcceptDistance();
                if(acceptDistance.doubleValue() != 0 && acceptDistance.subtract(currentDistance).doubleValue() < 0) {
                    continue;
                }
                //10.订单里程判断，orderDistance==0：不限制;同时把当前司机与乘客距离与司机设置的订单里程进行相减，如果小于0，则跳过当前司机
                BigDecimal orderDistance = driverSet.getOrderDistance();
                if(orderDistance.doubleValue() != 0 && orderDistance.subtract(searchNearByDriverForm.getMileageDistance()).doubleValue() < 0) {
                    continue;
                }

                //11.创建NearByDriverVo(附近司机信息)对象，并添加满足条件的司机id和距离
                NearByDriverVo nearByDriverVo = new NearByDriverVo();
                nearByDriverVo.setDriverId(driverId);
                nearByDriverVo.setDistance(currentDistance);

                //12.添加到要返回的list集合中
                list.add(nearByDriverVo);
            }
        }
        //13.返回list集合
        return list;
    }

    //下单后,实时更新司机的位置信息到redis缓存(由司机端调用)
    @Override
    public Boolean updateOrderLocationToCache(UpdateOrderLocationForm updateOrderLocationForm) {

        OrderLocationVo orderLocationVo = new OrderLocationVo();
        orderLocationVo.setLongitude(updateOrderLocationForm.getLongitude());
        orderLocationVo.setLatitude(updateOrderLocationForm.getLatitude());
        Long orderId = updateOrderLocationForm.getOrderId();

        redisTemplate.opsForValue().set(RedisConstant.UPDATE_ORDER_LOCATION+orderId, orderLocationVo);

        return true;
    }

    //司机赶往代驾起始点：获取司机的经纬度位置 根据订单id从上面redis中存的订单的位置信息取出来(由乘客端调用)
    @Override
    public OrderLocationVo getCacheOrderLocation(Long orderId) {
        OrderLocationVo orderLocationVo = (OrderLocationVo)redisTemplate.opsForValue().get(RedisConstant.UPDATE_ORDER_LOCATION+orderId);
        return orderLocationVo;
    }

    @Autowired
    private OrderServiceLocationRepository orderServiceLocationRepository;

    //保存订单服务位置信息到MongoDB
    //前端传来的是OrderServiceLocationForm的集合 其中的属性只有订单id,经度和纬度
    //向MongoDB中保存的OrderServiceLocation 属性有id,订单id,经度,纬度,创建时间
    @Override
    public Boolean saveOrderServiceLocation(List<OrderServiceLocationForm> orderLocationServiceFormList) {

        List<OrderServiceLocation> list = new ArrayList<>();

        //OrderServiceLocation
        orderLocationServiceFormList.forEach(orderServiceLocationForm->{
            // 把 orderServiceLocationForm 转成 OrderServiceLocation
            OrderServiceLocation orderServiceLocation = new OrderServiceLocation();
            BeanUtils.copyProperties(orderServiceLocationForm,orderServiceLocation);

            //ObjectId 是一个用于生成唯一标识符的类，通常使用在 MongoDB 中。
            // 它是一个 12 字节的 BSON 类型的 ID，通常用于标识数据库中的文档。
            orderServiceLocation.setId(ObjectId.get().toString());
            orderServiceLocation.setCreateTime(new Date());

            //向list中添加OrderServiceLocation
            list.add(orderServiceLocation);

            //tips:或者直接调用orderServiceLocationRepository.save(orderServiceLocation); 就不用写下面的saveAll了
        });


        //把List批量添加到MongoDB
        orderServiceLocationRepository.saveAll(list);

        return true;
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    //根据订单id获取订单最后一个位置
    //- 司机开始代驾之后，乘客端获取司机最新动向，就必须获取到司机最后一个位置信息
    //- 从MongoDB获取
    //只返回经度和纬度
    @Override
    public OrderServiceLastLocationVo getOrderServiceLastLocation(Long orderId) {
        //查询MongoDB  criteria(标准) 相当于sql语句 或者QueryWrapper
        //查询条件 ：orderId
        //根据创建时间降序排列
        //最新一条数据

        Criteria criteria = Criteria.where("orderId").is(orderId);
//        Sort sort = Sort.by(Sort.Direction.DESC, "createTime");
        Sort sort = Sort.by(Sort.Order.desc("createTime"));


        Query query = new Query();
        query.addCriteria(criteria);
        query.with(sort);
        query.limit(1);

        //因为mongoTemplate.find();返回的是list集合,所以这里用findOne()
        OrderServiceLocation orderServiceLocation =
                mongoTemplate.findOne(query, OrderServiceLocation.class);

        OrderServiceLastLocationVo orderServiceLastLocationVo = new OrderServiceLastLocationVo();
        BeanUtils.copyProperties(orderServiceLocation,orderServiceLastLocationVo);

        //只返回经度和纬度
        return orderServiceLastLocationVo;
    }

    @Override
    public BigDecimal calculateOrderRealDistance(Long orderId) {
        //1 根据订单id获取代驾订单位置信息，根据创建时间排序（升序）
        //查询MongoDB
        //第一种方式
//        OrderServiceLocation orderServiceLocation = new OrderServiceLocation();
//        orderServiceLocation.setOrderId(orderId);
//        Example<OrderServiceLocation> example = Example.of(orderServiceLocation);
//        Sort sort = Sort.by(Sort.Direction.ASC, "createTime");
//        List<OrderServiceLocation> list = orderServiceLocationRepository.findAll(example, sort);

        //第二种方式
        //MongoRepository只需要 按照规则 在MongoRepository把查询方法创建出来就可以了
        // 按照规则：findBy + 字段名称 + OrderBy + 排序规则
        // 总体规则：
        //1 查询方法名称 以 get  |  find  | read开头
        //2 后面查询字段名称，满足驼峰式命名，比如OrderId
        //3 字段查询条件添加关键字，比如Like  OrderBy   Asc
        // 具体编写 ： 根据订单id获取代驾订单位置信息，根据创建时间排序（升序）
        List<OrderServiceLocation> list =
                orderServiceLocationRepository.findByOrderIdOrderByCreateTimeAsc(orderId);


        //2 第一步查询返回订单位置信息list集合
        //把list集合遍历，得到每个位置信息，计算两个位置距离
        //把计算所有距离相加操作
        double realDistance = 0;

        if(!CollectionUtils.isEmpty(list)) {

            for (int i = 0,size = list.size()-1; i < size; i++) {
                //获取两个位置信息
                OrderServiceLocation location1 = list.get(i);
                OrderServiceLocation location2 = list.get(i + 1);

                //调用自定义的LocationUtil工具类，传入两个位置的经纬度信息，计算两个位置的距离
                double distance = LocationUtil.getDistance(
                        location1.getLatitude().doubleValue(),
                        location1.getLongitude().doubleValue(),
                        location2.getLatitude().doubleValue(),
                        location2.getLongitude().doubleValue()
                );

                realDistance += distance;
            }
        }

        //3 返回最终计算实际距离
        return new BigDecimal(realDistance);
    }

}
