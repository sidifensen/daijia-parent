package com.atguigu.daijia.rules.service.impl;

import com.alibaba.fastjson2.JSON;
import com.atguigu.daijia.model.form.rules.FeeRuleRequest;
import com.atguigu.daijia.model.form.rules.FeeRuleRequestForm;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponse;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponseVo;
import com.atguigu.daijia.rules.mapper.FeeRuleMapper;
import com.atguigu.daijia.rules.service.FeeRuleService;
import com.atguigu.daijia.rules.utils.DroolsHelper;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class FeeRuleServiceImpl implements FeeRuleService {

//    @Autowired
//    private KieContainer kieContainer;

    private static final String RULES_CUSTOMER_RULES_DRL = "rules/FeeRule.drl";

    //计算订单费用
    @Override
    public FeeRuleResponseVo calculateOrderFee(FeeRuleRequestForm feeRuleRequestForm) {

        //1.封装request(费用规则计算请求)对象
        FeeRuleRequest feeRuleRequest = new FeeRuleRequest();

        //2.从form对象中获取参数,放入request中
        feeRuleRequest.setDistance(feeRuleRequestForm.getDistance());
        String startTime = new DateTime(feeRuleRequestForm.getStartTime()).toString("HH:mm:ss");//Joda.Time的方法:可以.toString("HH:mm:ss") 格式化时间
        feeRuleRequest.setStartTime(startTime);//把DateTime转成string再传入
        feeRuleRequest.setWaitMinute(feeRuleRequestForm.getWaitMinute());

        log.info("传入参数：{}", JSON.toJSONString(feeRuleRequest));//用fastjson2将feeRuleRequest对象转换为 JSON 字符串进行日志打印

        //3.Drools 开启会话
//        KieSession kieSession = kieContainer.newKieSession();
        KieSession kieSession = DroolsHelper.loadForRule(RULES_CUSTOMER_RULES_DRL);

        //4.封装返回response对象
        FeeRuleResponse feeRuleResponse = new FeeRuleResponse();

        //5.设置全局变量FeeRuleResponse
        kieSession.setGlobal("feeRuleResponse",feeRuleResponse);
        //6.插入订单对象
        kieSession.insert(feeRuleRequest);
        //7.启动规则引擎
        kieSession.fireAllRules();
        //8.关闭会话
        kieSession.dispose();

        log.info("计算结果：{}", JSON.toJSONString(feeRuleResponse));

        //9.封装数据到vo里,进行返回
        FeeRuleResponseVo feeRuleResponseVo = new FeeRuleResponseVo();
        BeanUtils.copyProperties(feeRuleResponse, feeRuleResponseVo);
        log.info("distanceFee为:{}", feeRuleResponseVo.getDistanceFee());

        //10.返回FeeRuleResponseVo费用规则计算结果
        return feeRuleResponseVo;
    }

}
