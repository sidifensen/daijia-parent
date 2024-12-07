package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.driver.mapper.DriverAccountDetailMapper;
import com.atguigu.daijia.driver.mapper.DriverAccountMapper;
import com.atguigu.daijia.driver.service.DriverAccountService;
import com.atguigu.daijia.model.entity.driver.DriverAccount;
import com.atguigu.daijia.model.entity.driver.DriverAccountDetail;
import com.atguigu.daijia.model.form.driver.TransferForm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class DriverAccountServiceImpl extends ServiceImpl<DriverAccountMapper, DriverAccount> implements DriverAccountService {

    @Autowired
    private DriverAccountMapper driverAccountMapper;

    @Autowired
    private DriverAccountDetailMapper driverAccountDetailMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean transfer(TransferForm transferForm) {

        //去重,一次系统只能奖励一次给司机 根据交易编号来判断是否已经奖励过了
        LambdaQueryWrapper<DriverAccountDetail> queryWrapper = new LambdaQueryWrapper<DriverAccountDetail>();
        queryWrapper.eq(DriverAccountDetail::getTradeNo, transferForm.getTradeNo());
        long count = driverAccountDetailMapper.selectCount(queryWrapper);
        if(count > 0) {
            return true;
        }

        //添加账号金额
        driverAccountMapper.add(transferForm.getDriverId(), transferForm.getAmount());

        //添加交易记录
        DriverAccountDetail driverAccountDetail = new DriverAccountDetail();
        BeanUtils.copyProperties(transferForm, driverAccountDetail);
        driverAccountDetailMapper.insert(driverAccountDetail);

        return true;
    }


}
