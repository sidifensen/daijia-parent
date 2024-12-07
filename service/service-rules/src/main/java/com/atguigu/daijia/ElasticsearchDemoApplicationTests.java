package com.atguigu.daijia;

import jakarta.annotation.Resource;
import org.junit.Test;

import java.math.BigDecimal;

import com.alibaba.fastjson.JSON;
import com.atguigu.daijia.model.form.rules.FeeRuleRequest;
import com.atguigu.daijia.model.form.rules.ProfitsharingRuleRequest;
import com.atguigu.daijia.model.form.rules.RewardRuleRequest;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponse;
import com.atguigu.daijia.model.vo.rules.ProfitsharingRuleResponse;
import com.atguigu.daijia.model.vo.rules.RewardRuleResponse;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.internal.io.ResourceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;


@SpringBootTest
public class ElasticsearchDemoApplicationTests {

    private static final String RULES_CUSTOMER_RULES_DRL =  "rules/FeeRule.drl";

    @Bean
    public KieContainer kieContainer() {
        KieServices kieServices = KieServices.Factory.get();

        KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
        kieFileSystem.write(ResourceFactory.newClassPathResource(RULES_CUSTOMER_RULES_DRL));
        KieBuilder kb = kieServices.newKieBuilder(kieFileSystem);
        kb.buildAll();

        KieModule kieModule = kb.getKieModule();
        KieContainer kieContainer = kieServices.newKieContainer(kieModule.getReleaseId());
        return kieContainer;
    }

    @Test
    public void test01() {
        // 开启会话
        KieSession kieSession = kieContainer().newKieSession();

        // 触发规则
        kieSession.fireAllRules();
        // 中止会话
        kieSession.dispose();
    }


    @Test
    public void test1() {
        FeeRuleRequest feeRuleRequest = new FeeRuleRequest();
        feeRuleRequest.setDistance(new BigDecimal(15.0));
        feeRuleRequest.setStartTime("01:59:59");
        feeRuleRequest.setWaitMinute(20);

        // 开启会话
        KieSession kieSession = kieContainer().newKieSession();

        // 设置全局变量
        FeeRuleResponse feeRuleResponse = new FeeRuleResponse();
        kieSession.setGlobal("feeRuleResponse", feeRuleResponse);
        // 设置订单对象
        kieSession.insert(feeRuleRequest);

        // 触发规则
        kieSession.fireAllRules();
        // 中止会话
        kieSession.dispose();

        System.out.println("后："+JSON.toJSONString(feeRuleResponse));
    }

    @Test
    public void test2() {
        RewardRuleRequest rewardRuleRequest = new RewardRuleRequest();
        rewardRuleRequest.setStartTime("01:59:59");
//        rewardRuleRequest.setOrderNum(10);

        // 开启会话
        KieSession kieSession = kieContainer().newKieSession();

        RewardRuleResponse rewardRuleResponse = new RewardRuleResponse();
        kieSession.setGlobal("rewardRuleResponse", rewardRuleResponse);
        // 设置订单对象
        kieSession.insert(rewardRuleRequest);
        // 触发规则
        kieSession.fireAllRules();
        // 中止会话
        kieSession.dispose();
        System.out.println("后："+JSON.toJSONString(rewardRuleRequest));
    }

    @Test
    public void test3() {
        ProfitsharingRuleRequest profitsharingRuleRequest = new ProfitsharingRuleRequest();
        profitsharingRuleRequest.setOrderAmount(new BigDecimal(34));
//        profitsharingRuleRequest.setOrderNum(0);


        BigDecimal d = profitsharingRuleRequest.getOrderAmount().multiply(new BigDecimal("0.006"));
        System.out.println(d);

//        profitsharingRuleRequest.getOrderAmount().setScale(2, RoundingMode.HALF_UP);
        // 开启会话
        KieSession kieSession = kieContainer().newKieSession();

        ProfitsharingRuleResponse profitsharingRuleResponse = new ProfitsharingRuleResponse();
        kieSession.setGlobal("profitsharingRuleResponse", profitsharingRuleResponse);
        // 设置订单对象
        kieSession.insert(profitsharingRuleRequest);
        // 触发规则
        kieSession.fireAllRules();
        // 中止会话
        kieSession.dispose();
        System.out.println("后："+JSON.toJSONString(profitsharingRuleResponse));
    }
}
