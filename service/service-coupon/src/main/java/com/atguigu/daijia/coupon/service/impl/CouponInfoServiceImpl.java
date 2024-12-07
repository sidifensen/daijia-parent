package com.atguigu.daijia.coupon.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.coupon.mapper.CouponInfoMapper;
import com.atguigu.daijia.coupon.mapper.CustomerCouponMapper;
import com.atguigu.daijia.coupon.service.CouponInfoService;
import com.atguigu.daijia.model.entity.coupon.CouponInfo;
import com.atguigu.daijia.model.entity.coupon.CustomerCoupon;
import com.atguigu.daijia.model.form.coupon.UseCouponForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.coupon.AvailableCouponVo;
import com.atguigu.daijia.model.vo.coupon.NoReceiveCouponVo;
import com.atguigu.daijia.model.vo.coupon.NoUseCouponVo;
import com.atguigu.daijia.model.vo.coupon.UsedCouponVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CouponInfoServiceImpl extends ServiceImpl<CouponInfoMapper, CouponInfo> implements CouponInfoService {

    @Autowired
    private CouponInfoMapper couponInfoMapper;

    @Override
    public PageVo<NoReceiveCouponVo> findNoReceivePage(Page<CouponInfo> pageParam, Long customerId) {
        IPage<NoReceiveCouponVo> pageInfo = couponInfoMapper.findNoReceivePage(pageParam, customerId);
        return new PageVo(pageInfo.getRecords(), pageInfo.getPages(), pageInfo.getTotal());
    }

    @Override
    public PageVo<NoUseCouponVo> findNoUsePage(Page<CouponInfo> pageParam, Long customerId) {
        IPage<NoUseCouponVo> pageInfo = couponInfoMapper.findNoUsePage(pageParam, customerId);
        return new PageVo(pageInfo.getRecords(), pageInfo.getPages(), pageInfo.getTotal());
    }

    @Override
    public PageVo<UsedCouponVo> findUsedPage(Page<CouponInfo> pageParam, Long customerId) {
        IPage<UsedCouponVo> pageInfo = couponInfoMapper.findUsedPage(pageParam, customerId);
        return new PageVo(pageInfo.getRecords(), pageInfo.getPages(), pageInfo.getTotal());
    }

    @Autowired
    private CustomerCouponMapper customerCouponMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean receive(Long customerId, Long couponId) {
        //1、查询优惠券
        CouponInfo couponInfo = this.getById(couponId);
        if(null == couponInfo) {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);//(204, "数据异常")
        }

        //2、优惠券过期日期判断
        if (couponInfo.getExpireTime().before(new Date())) {
            throw new GuiguException(ResultCodeEnum.COUPON_EXPIRE);//( 250, "优惠券已过期")
        }

        //3、校验库存，优惠券领取数量判断 如果领取数量大于等于发放数量，则报错优惠券库存不足
        if (couponInfo.getPublishCount() !=0 && couponInfo.getReceiveCount() >= couponInfo.getPublishCount()) {
            throw new GuiguException(ResultCodeEnum.COUPON_LESS);//( 250, "优惠券库存不足")
        }

        //用redisson分布式锁 防止高并发下领取优惠券时，出现领取失败的情况
        RLock lock = null;
        try{
            lock = redissonClient.getLock(RedisConstant.COUPON_RECEIVE_LOCK+customerId);
            boolean success = lock.tryLock(RedisConstant.COUPON_LOCK_WAIT_TIME,//等待获取锁的时间
                    RedisConstant.COUPON_LOCK_LEASE_TIME, //过期的时间
                    TimeUnit.SECONDS);
            if (success){
                //4、校验每人限领数量
                if (couponInfo.getPerLimit() > 0) {
                    //4.1、统计当前用户对当前优惠券的已经领取的数量
                    LambdaQueryWrapper<CustomerCoupon> queryWrapper = new LambdaQueryWrapper();
                    queryWrapper.eq(CustomerCoupon::getCouponId, couponId).eq(CustomerCoupon::getCustomerId, customerId);
                    long count = customerCouponMapper.selectCount(queryWrapper);
                    //4.2、校验限领数量 如果已经领取的数量大于等于限领数量，则报错优惠券每人限领数量已满
                    if (count >= couponInfo.getPerLimit()) {
                        throw new GuiguException(ResultCodeEnum.COUPON_USER_LIMIT);//( 250, "超出领取数量")
                    }
                    //5、更新coupon_info表的优惠券领取数量 (领取数量+1)
                    int row = couponInfoMapper.updateReceiveCount(couponId);
                    if (row == 1) {
                        //6、调用下面的方法保存领取记录到customer_coupon表
                        this.saveCustomerCoupon(customerId, couponId, couponInfo.getExpireTime());
                        return true;
                    }
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            try {
                if (null != lock){
                    lock.unlock();
                }
            }catch (Exception e){
                throw new GuiguException(ResultCodeEnum.LOCK_UNLOCK_ERROR);//(260, "锁释放异常")
            }
        }
        return true;
    }

    private void saveCustomerCoupon(Long customerId, Long couponId, Date expireTime) {
        CustomerCoupon customerCoupon = new CustomerCoupon();
        customerCoupon.setCustomerId(customerId);
        customerCoupon.setCouponId(couponId);
        customerCoupon.setStatus(1);//未使用
        customerCoupon.setReceiveTime(new Date());
        customerCoupon.setExpireTime(expireTime);

        customerCouponMapper.insert(customerCoupon);
    }

    //获取未使用的最佳优惠卷信息
    @Override
    public List<AvailableCouponVo> findAvailableCoupon(Long customerId, BigDecimal orderAmount) {

        //1 创建list集合，存储最终返回数据
        List<AvailableCouponVo> availableCouponVoList = new ArrayList<>();

        //2 根据乘客id，获取乘客已经领取但是没有使用的优惠卷列表
        //返回list集合
        List<NoUseCouponVo> list = couponInfoMapper.findNoUseList(customerId);

        //3 遍历乘客未使用优惠卷列表，得到每个优惠卷
        //3.1 判断优惠卷类型：现金卷 和 折扣卷
        List<NoUseCouponVo> typeList = list.stream()//将 list（一个包含未使用优惠券的列表）转换为一个流（Stream）
                        .filter(item -> item.getCouponType() == 1)//filter是Stream API中的一个中间操作，它用于对流中的元素进行过滤
                        .collect(Collectors.toList());//collect是一个终止操作，它用于将流中的元素收集到一个新的集合中。
                        //collect是一个终止操作，它用于将流中的元素收集到一个新的集合中。

        //3.2 如果优惠卷类型是现金券
        //判断现金卷是否满足条件
        for(NoUseCouponVo noUseCouponVo:typeList) {
            //判断使用门槛.

            //获得优惠券的减免金额
            BigDecimal reduceAmount = noUseCouponVo.getAmount();

            //3.2.1    如果ConditionAmount为0,表示没有门槛，订单金额必须大于优惠减免金额
            if(noUseCouponVo.getConditionAmount().doubleValue()==0//没有门槛
                    && orderAmount.subtract(reduceAmount).doubleValue()>0)//减免金额大于订单金额
            {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo,reduceAmount));
            }

            //3.2.2   如果ConditionAmount大于0有门槛  ，订单金额必须大于优惠门槛金额才能使用
            if(noUseCouponVo.getConditionAmount().doubleValue() > 0
                    && orderAmount.subtract(noUseCouponVo.getConditionAmount()).doubleValue()>0)
            {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo,reduceAmount));
            }
        }

        //3.3 折扣卷
        //判断折扣卷是否满足条件
        List<NoUseCouponVo> typeList2 = //过滤条件 优惠券类型为2折扣券
                list.stream().filter(item -> item.getCouponType() == 2).collect(Collectors.toList());
        for (NoUseCouponVo noUseCouponVo : typeList2) {
            //获得折扣之后的金额   100 打8折  = 100 * 8 /10= 80
            BigDecimal discountAmount = orderAmount.multiply(noUseCouponVo.getDiscount())//乘以折扣
                    .divide(new BigDecimal("10"))//除以10
                    .setScale(2, RoundingMode.HALF_UP);//保留两位小数

            //获得减免金额
            BigDecimal reduceAmount = orderAmount.subtract(discountAmount);

            //3.3.1.如果没门槛
            if (noUseCouponVo.getConditionAmount().doubleValue() == 0) {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo, reduceAmount));
            }
            //3.3.2.如果有门槛，订单折扣后金额大于优惠券门槛金额
            if (noUseCouponVo.getConditionAmount().doubleValue() > 0
                    && discountAmount.subtract(noUseCouponVo.getConditionAmount()).doubleValue() > 0) {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo, reduceAmount));
            }

        }

        //4 把满足条件优惠卷放到最终list集合
        //根据金额排序
        if (!CollectionUtils.isEmpty(availableCouponVoList)) {
            Collections.sort(availableCouponVoList,
//                    Comparator.comparing(AvailableCouponVo::getReduceAmount)//调用Comparator的comparing方法，按减免金额排序
                    new Comparator<AvailableCouponVo>() { //或者使用匿名内部类
                        @Override
                        public int compare(AvailableCouponVo o1, AvailableCouponVo o2) {
                            return o1.getReduceAmount().compareTo(o2.getReduceAmount());
                        }
                    }
            );
        }

        return availableCouponVoList;
    }

    private AvailableCouponVo buildBestNoUseCouponVo(NoUseCouponVo noUseCouponVo, BigDecimal reduceAmount) {
        //5 构建返回对象
        AvailableCouponVo bestNoUseCouponVo = new AvailableCouponVo();

        BeanUtils.copyProperties(noUseCouponVo, bestNoUseCouponVo);

        bestNoUseCouponVo.setCouponId(noUseCouponVo.getId());//优惠券id
        bestNoUseCouponVo.setReduceAmount(reduceAmount);//优惠券减免金额

        return bestNoUseCouponVo;
    }


    //使用优惠券 返回优惠券减免金额
    @Transactional(noRollbackFor = Exception.class)
    @Override
    public BigDecimal useCoupon(UseCouponForm useCouponForm) {
        //1.根据乘客优惠券id 获取乘客所拥有的优惠券
        CustomerCoupon customerCoupon = customerCouponMapper.selectById(useCouponForm.getCustomerCouponId());
        if(null == customerCoupon) {
            throw new GuiguException(ResultCodeEnum.ARGUMENT_VALID_ERROR);
        }
        //2.根据优惠券id获取优惠券的信息
        CouponInfo couponInfo = couponInfoMapper.selectById(customerCoupon.getCouponId());
        if(null == couponInfo) {
            throw new GuiguException(ResultCodeEnum.ARGUMENT_VALID_ERROR);//(210, "参数校验异常")
        }
        //3 TODO 判断该优惠券是否为乘客所有
        if(customerCoupon.getCustomerId().longValue() != useCouponForm.getCustomerId().longValue()) {
            throw new GuiguException(ResultCodeEnum.ILLEGAL_REQUEST);//(205, "非法
        }

        //4.获取优惠券减免金额
        BigDecimal reduceAmount = null;
        if(couponInfo.getCouponType().intValue() == 1) {
            //使用门槛判断
            //4.1.1.没门槛，订单金额必须大于优惠券减免金额
            if (couponInfo.getConditionAmount().doubleValue() == 0 && useCouponForm.getOrderAmount().subtract(couponInfo.getAmount()).doubleValue() > 0) {
                //
                reduceAmount = couponInfo.getAmount();
            }
            //4.1.2.有门槛，订单金额大于优惠券门槛金额
            if (couponInfo.getConditionAmount().doubleValue() > 0 && useCouponForm.getOrderAmount().subtract(couponInfo.getConditionAmount()).doubleValue() > 0) {
                //减免金额
                reduceAmount = couponInfo.getAmount();
            }
        } else {
            //使用门槛判断
            //订单折扣后金额
            BigDecimal discountOrderAmount = useCouponForm.getOrderAmount().multiply(couponInfo.getDiscount()).divide(new BigDecimal("10")).setScale(2, RoundingMode.HALF_UP);
            //订单优惠金额
            //4.2.1.没门槛
            if (couponInfo.getConditionAmount().doubleValue() == 0) {
                //减免金额
                reduceAmount = useCouponForm.getOrderAmount().subtract(discountOrderAmount);
            }
            //4.2.2.有门槛，订单折扣后金额大于优惠券门槛金额
            if (couponInfo.getConditionAmount().doubleValue() > 0 && discountOrderAmount.subtract(couponInfo.getConditionAmount()).doubleValue() > 0) {
                //减免金额
                reduceAmount = useCouponForm.getOrderAmount().subtract(discountOrderAmount);
            }
        }

        if(reduceAmount.doubleValue() > 0) {
            //5.使用redisson分布式锁 防止高并发下优惠券使用时，出现使用失败的情况
            RLock lock =null;
            try{
                lock = redissonClient.getLock(RedisConstant.COUPON_USE_LOCK+useCouponForm.getCustomerId());
                boolean success = lock.tryLock(RedisConstant.COUPON_USE_LOCK_WAIT_TIME, //等待获取锁的时间
                        RedisConstant.COUPON_USE_LOCK_LEASE_TIME, //过期的时间
                        TimeUnit.SECONDS);
                if (success){
                    //6.更新coupon_info表的优惠券的使用数量(使用数量+1)
                    int row = couponInfoMapper.updateUseCount(couponInfo.getId());

                    if(row == 1) {

                        CustomerCoupon updateCustomerCoupon = new CustomerCoupon();
                        updateCustomerCoupon.setId(customerCoupon.getId());//优惠券id
                        updateCustomerCoupon.setUsedTime(new Date());//使用时间
                        updateCustomerCoupon.setOrderId(useCouponForm.getOrderId());//订单id
                        updateCustomerCoupon.setStatus(2);//使用状态

                        //7.更新customer_coupon表的优惠券状态为已使用
                        customerCouponMapper.updateById(updateCustomerCoupon);

                        return reduceAmount;
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
            }finally {
                try {
                    if (null != lock){
                        lock.unlock();
                    }
                }catch (Exception e){
                    throw new GuiguException(ResultCodeEnum.LOCK_UNLOCK_ERROR);//(260, "锁释放异常")
                }
            }
        }
        throw new GuiguException(ResultCodeEnum.COUPON_USE_FAIL);//(251, "优惠券使用失败")
    }

}
