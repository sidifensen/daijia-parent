package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.dispatch.client.NewOrderFeignClient;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.driver.service.DriverService;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.model.form.driver.DriverFaceModelForm;
import com.atguigu.daijia.model.form.driver.UpdateDriverAuthInfoForm;
import com.atguigu.daijia.model.vo.driver.DriverAuthInfoVo;
import com.atguigu.daijia.model.vo.driver.DriverLoginVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class DriverServiceImpl implements DriverService {


    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public String login(String code) {
        //1.用code远程调用service 的login方法 获得司机id
        Result<Long> loginResult = driverInfoFeignClient.login(code);

        if (loginResult.getCode() != 200){
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }

        Long driverId = loginResult.getData();

        //2.创建token
        String token = UUID.randomUUID().toString().replaceAll("-","");

        //3.保存token和司机id到redis
        redisTemplate.opsForValue().set(RedisConstant.USER_LOGIN_KEY_PREFIX +token,
                driverId.toString(),RedisConstant.USER_LOGIN_KEY_TIMEOUT,
                TimeUnit.SECONDS );

        return token;
    }

    @Override
    public DriverLoginVo getDriverLoginInfo(Long driverId) {
        return driverInfoFeignClient.getDriverLoginInfo(driverId).getData();
    }

    //司机认证信息
    @Override
    public DriverAuthInfoVo getDriverAuthInfo(Long driverId) {
        Result<DriverAuthInfoVo> authInfoVoResult = driverInfoFeignClient.getDriverAuthInfo(driverId);
        DriverAuthInfoVo driverAuthInfoVo = authInfoVoResult.getData();
        return driverAuthInfoVo;
    }

    //更新司机认证信息
    @Override
    public Boolean updateDriverAuthInfo(UpdateDriverAuthInfoForm updateDriverAuthInfoForm) {
        Result<Boolean> booleanResult = driverInfoFeignClient.UpdateDriverAuthInfo(updateDriverAuthInfoForm);
        Boolean data = booleanResult.getData();//true or false 是否通过认证
        return data;
    }

    //创建司机人脸模型
    @Override
    public Boolean creatDriverFaceModel(DriverFaceModelForm driverFaceModelForm) {
        Result<Boolean> booleanResult = driverInfoFeignClient.creatDriverFaceModel(driverFaceModelForm);
        return booleanResult.getData();
    }

    //判断是否进行了人脸识别
    @Override
    public Boolean isFaceRecognition(Long driverId) {
        return driverInfoFeignClient.isFaceRecognition(driverId).getData();
    }

    //司机人脸识别
    @Override
    public Boolean verifyDriverFace(DriverFaceModelForm driverFaceModelForm) {
        return driverInfoFeignClient.verifyDriverFace(driverFaceModelForm).getData();
    }

    @Autowired
    private LocationFeignClient locationFeignClient;

    @Autowired
    private NewOrderFeignClient newOrderDispatchFeignClient;

    //司机开启接单服务
    @Override
    public Boolean startService(Long driverId) {
        //1.远程调用DriverInfoController的getDriverLoginInfo方法,获取获取司机登录信息,判断认证状态
        DriverLoginVo driverLoginVo = driverInfoFeignClient.getDriverLoginInfo(driverId).getData();
        if(driverLoginVo.getAuthStatus().intValue() != 2) {//如果状态不是2(认证通过),则抛出异常
            throw new GuiguException(ResultCodeEnum.AUTH_ERROR);
        }

        //2.远程调用DriverInfoController的isFaceRecognition方法,判断当日是否人脸识别
        Boolean isFaceRecognition = driverInfoFeignClient.isFaceRecognition(driverId).getData();
        if(!isFaceRecognition) {
            throw new GuiguException(ResultCodeEnum.FACE_ERROR);
        }

        //3.远程调用 DriverInfoController的updateServiceStatus方法,更新司机接单状态
        driverInfoFeignClient.updateServiceStatus(driverId, 1);

        //4.远程调用service-map的LocationController的removeDriverLocation方法,删除司机位置信息
        locationFeignClient.removeDriverLocation(driverId);

        //5.远程调用service-dispatch的NewOrderController的clearNewOrderQueueData方法,清空司机新订单队列
        newOrderDispatchFeignClient.clearNewOrderQueueData(driverId);

        return true;
    }

    //司机停止接单服务
    //司机抢成功单，就要关闭接单服务。
    @Override
    public Boolean stopService(Long driverId) {
        //更新司机接单状态
        driverInfoFeignClient.updateServiceStatus(driverId, 0);//0

        //删除司机位置信息
        locationFeignClient.removeDriverLocation(driverId);

        //清空司机新订单队列
        newOrderDispatchFeignClient.clearNewOrderQueueData(driverId);
        return true;
    }

}
