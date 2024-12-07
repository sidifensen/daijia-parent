package com.atguigu.daijia.dispatch.xxl.job;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.common.utils.ExceptionUtil;
import com.atguigu.daijia.dispatch.mapper.XxlJobLogMapper;
import com.atguigu.daijia.dispatch.service.NewOrderService;
import com.atguigu.daijia.model.entity.dispatch.XxlJobLog;
import com.atguigu.daijia.model.vo.dispatch.NewOrderTaskVo;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JobHandler {

    @Autowired
    private XxlJobLogMapper xxlJobLogMapper;

    @Autowired
    private NewOrderService newOrderService;

    //这是一个调度任务,由NewOrderService 的addAndStartTask()方法里调用触发
    @XxlJob("newOrderTaskHandler")
    public void newOrderTaskHandler() {
        log.info("新订单调度任务：", XxlJobHelper.getJobId());

        //记录定时任务相关的日志信息

        //创建日志对象
        XxlJobLog xxlJobLog = new XxlJobLog();
        Long logId = XxlJobHelper.getJobId();

        //通过XxlJobHelper获取任务id
        xxlJobLog.setJobId(XxlJobHelper.getJobId());

        long startTime = System.currentTimeMillis();

        try {
            //TODO 执行任务:搜索附近代驾司机
            newOrderService.executeTask(logId);

            xxlJobLog.setStatus(1);//成功
            log.info("定时任务执行成功，任务id为: ", logId);

        } catch (Exception e) {

            xxlJobLog.setStatus(0);//失败

            // 从ExceptionUtil获取异常信息;ExceptionUtil是nacos-common包里面的工具类
            xxlJobLog.setError(ExceptionUtil.getAllExceptionMsg(e));
            log.error("定时任务执行失败，任务id为：", logId);

            e.printStackTrace();

        } finally {
            //耗时
            int times = (int) (System.currentTimeMillis() - startTime);
            xxlJobLog.setTimes(times);

            //TODO 向数据库中插入日志信息
            xxlJobLogMapper.insert(xxlJobLog);
        }

    }
}