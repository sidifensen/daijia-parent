package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.driver.client.CiFeignClient;
import com.atguigu.daijia.driver.service.FileService;
import com.atguigu.daijia.driver.service.MonitorService;
import com.atguigu.daijia.model.entity.order.OrderMonitor;
import com.atguigu.daijia.model.entity.order.OrderMonitorRecord;
import com.atguigu.daijia.model.form.order.OrderMonitorForm;
import com.atguigu.daijia.model.vo.order.TextAuditingVo;
import com.atguigu.daijia.order.client.OrderMonitorFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class MonitorServiceImpl implements MonitorService {

    @Autowired
    private FileService fileService;

    @Autowired
    private OrderMonitorFeignClient orderMonitorFeignClient;

    @Autowired
    private CiFeignClient ciFeignClient;

    @Override
    public Boolean upload(MultipartFile file, OrderMonitorForm orderMonitorForm) {

        //上传到文件到minio,调用本模块内的中的minio的upload方法,返回url
        String url = fileService.upload(file);

        //保存订单监控记录信息
        OrderMonitorRecord orderMonitorRecord = new OrderMonitorRecord();
        orderMonitorRecord.setOrderId(orderMonitorForm.getOrderId());
        orderMonitorRecord.setFileUrl(url);
        orderMonitorRecord.setContent(orderMonitorForm.getContent());//录音内容

        //TODO 远程调用service-driver的CiController的textAuditing方法进行文本审核,返回审核结果
        TextAuditingVo textAuditingVo = ciFeignClient.textAuditing(orderMonitorForm.getContent()).getData();
        orderMonitorRecord.setResult(textAuditingVo.getResult());//审核结果
        orderMonitorRecord.setKeywords(textAuditingVo.getKeywords());//审核结果中的风险关键词


        //TODO 远程调用service-order的OrderMonitorController的saveMonitorRecord方法,保存监控记录到mongodb
        orderMonitorFeignClient.saveMonitorRecord(orderMonitorRecord);

        //TODO 远程调用service-order的OrderMonitorController的getOrderMonitor方法,从mysql的order_monitor中获取订单监控状态
        OrderMonitor orderMonitor = orderMonitorFeignClient.getOrderMonitor(orderMonitorForm.getOrderId()).getData();
        int fileNum = orderMonitor.getFileNum() + 1;
        orderMonitor.setFileNum(fileNum);

        //审核结果: 0（审核正常），1 （判定为违规敏感文件），2（疑似敏感，建议人工复核）。
        if("2".equals(orderMonitorRecord.getResult())) {
            int auditNum = orderMonitor.getAuditNum() + 1;
            orderMonitor.setAuditNum(auditNum);//需要审核的风险关键词的个数
        }

        //TODO 远程调用service-order的OrderMonitorController的updateOrderMonitor方法,更新订单监控状态到mysql的order_monitor(订单监控的总状态表)
        orderMonitorFeignClient.updateOrderMonitor(orderMonitor);

        return true;
    }


}