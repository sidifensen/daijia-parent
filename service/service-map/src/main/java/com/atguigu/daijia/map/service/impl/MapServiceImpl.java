package com.atguigu.daijia.map.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.map.service.MapService;
import com.atguigu.daijia.model.form.map.CalculateDrivingLineForm;
import com.atguigu.daijia.model.vo.map.DrivingLineVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class MapServiceImpl implements MapService {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${tencent.map.key}")
    private String key;

    //计算驾驶线路
    @Override
    public DrivingLineVo calculateDrivingLine(CalculateDrivingLineForm calculateDrivingLineForm) {
        //请求腾讯提供接口，按照接口要求传递相关参数，返回需要结果
        //使用HttpClient，目前Spring封装调用工具使用RestTemplate
        //1.定义调用腾讯地址
        String url = "https://apis.map.qq.com/ws/direction/v1/driving/?from={from}&to={to}&key={key}";

        //2.封装传递参数
        Map<String,String> map = new HashMap();
        //开始位置
        // 经纬度：比如 北纬40 东经116
        map.put("from",calculateDrivingLineForm.getStartPointLatitude()+","+calculateDrivingLineForm.getStartPointLongitude());
        //结束位置
        map.put("to",calculateDrivingLineForm.getEndPointLatitude()+","+calculateDrivingLineForm.getEndPointLongitude());
        //key
        map.put("key",key);


        //3.使用RestTemplate调用地图服务,返回result  (规定用GET请求) (导入FastJSON依赖)
        JSONObject result = restTemplate.getForObject(url, JSONObject.class, map);

        //处理返回结果
        //判断调用是否成功 0成功 非0失败
        int status = result.getIntValue("status");


        if(status != 0) {//失败
            throw new GuiguException(ResultCodeEnum.MAP_FAIL);
        }

        //4.从result中获取返回路线信息
        JSONObject route =
                result.getJSONObject("result")//取出result对象
                        .getJSONArray("routes")//取出result对象中的routes数组
                        .getJSONObject(0);//取出第一个路线对象

        //5.创建驾驶路线vo对象
        DrivingLineVo drivingLineVo = new DrivingLineVo();

        //6预估时间
        drivingLineVo.setDuration(route.getBigDecimal("duration"));
        //7距离  6.583 == 6.58 / 6.59
        drivingLineVo.setDistance(route.getBigDecimal("distance")//默认单位是米
                .divide(new BigDecimal(1000))//除以1000 转换成千米
                .setScale(2, RoundingMode.HALF_UP));//保留两位小数 使用四舍五入(向上取整)
        //8路线
        drivingLineVo.setPolyline(route.getJSONArray("polyline"));
        log.info("驾驶路线：{}", drivingLineVo);

        return drivingLineVo;
    }


}
