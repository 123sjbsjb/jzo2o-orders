package com.jzo2o.orders.manager.service.impl;

import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseBackReqDTO;
import com.jzo2o.api.market.dto.request.CouponUseReqDTO;
import com.jzo2o.common.model.PageResult;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.db.DbRuntimeException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.enums.EnableStatusEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.utils.*;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.handler.OrdersHandler;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.request.OrderPageQueryReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OperationOrdersPageResDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.redis.helper.CacheHelper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.jzo2o.orders.base.constants.FieldConstants.SORT_BY;
import static com.jzo2o.orders.base.constants.RedisConstants.RedisKey.ORDERS;
import static com.jzo2o.orders.base.constants.RedisConstants.Ttl.ORDERS_PAGE_TTL;

/**
 * <p>
 * 订单表 服务实现类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersManagerServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersManagerService {

    @Resource
    private OrdersManagerServiceImpl owner;

    @Resource
    private IOrdersCanceledService ordersCanceledService;

    @Resource
    private IOrdersCommonService ordersCommonService;

    @Resource
    private IOrdersCreateService ordersCreateService;

    @Resource
    private IOrdersRefundService ordersRefundService;

    @Resource
    private OrdersHandler ordersHandler;

    @Resource
    private OrderStateMachine orderStateMachine;

    @Resource
    private CacheHelper cacheHelper;

    @Resource
    private CouponApi couponApi;


    @Override
    public List<Orders> batchQuery(List<Long> ids) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids).ge(Orders::getUserId, 0);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public Orders queryById(Long id) {
        return baseMapper.selectById(id);
    }

    /**
     * 滚动分页查询
     *
     * @param currentUserId 当前用户id
     * @param ordersStatus  订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：已取消，700：已关闭
     * @param sortBy        排序字段
     * @return 订单列表
     */
    @Override
    public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
        //1.构件查询条件
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
                .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
                .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
                .eq(Orders::getUserId, currentUserId)
                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
                .select(Orders::getId);//只查询id列
        Page<Orders> queryPage = new Page<>();
        queryPage.addOrder(OrderItem.desc(SORT_BY));
        queryPage.setSearchCount(false);

        //2.使用覆盖索引查询订单列表（）
        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
        if (ObjectUtil.isEmpty(ordersPage.getRecords())) {
            return new ArrayList<>();
        }
        //提取订单id列表
        List<Long> orderIds= CollUtils.getFieldValues(ordersPage.getRecords(), Orders::getId);
        //先查询缓存，缓存没有再查询数据库....
        //参数1：redisKey的一部分
        String redisKey = String.format(ORDERS, currentUserId);
        //参数2：订单id列表
        //参数3：batchDataQueryExecutor 当缓存中没有时执行batchDataQueryExecutor从数据库查询
        // batchDataQueryExecutor的方法：Map<K, T> execute(List<K> objectIds, Class<T> clazz); objectIds表示缓存中未匹配到的id，clazz指定map中value的数据类型
        //参数4：返回List中的数据类型
        //参数5：过期时间
        List<OrderSimpleResDTO> orderSimpleResDTOS1 = cacheHelper.batchGet(redisKey, orderIds, (noCacheIds, clazz) -> {
            List<Orders> orders = batchQuery(noCacheIds);
            if (CollUtils.isEmpty(orders)) {
                //为了防止缓存穿透返回空数据
                return new HashMap<>();
            }
            Map<Long, OrderSimpleResDTO> collect = orders.stream().collect(Collectors.toMap(Orders::getId, o -> BeanUtils.toBean(o, OrderSimpleResDTO.class)));
            return collect;
        }, OrderSimpleResDTO.class, ORDERS_PAGE_TTL);
        return orderSimpleResDTOS1;
    }
    /**
     * 根据订单id查询
     *
     * @param id 订单id
     * @return 订单详情
     */
    @Override
    public OrderResDTO getDetail(Long id) {
        //查询订单
        //Orders orders = queryById(id);
        String currentSnapshotJson = orderStateMachine.getCurrentSnapshotCache(String.valueOf(id));
        OrderSnapshotDTO orderSnapshotDTO = JsonUtils.toBean(currentSnapshotJson, OrderSnapshotDTO.class);
        //如果支付过期则取消订单
        orderSnapshotDTO = canalIfPayOvertime(orderSnapshotDTO);
        OrderResDTO orderResDTO = BeanUtil.toBean(orderSnapshotDTO, OrderResDTO.class);

        return orderResDTO;
    }
    /**
     * 如果支付过期则取消订单
     * @param orderSnapshotDTO
     */
    private OrderSnapshotDTO canalIfPayOvertime(OrderSnapshotDTO orderSnapshotDTO){
        //创建订单未支付15分钟后自动取消
        if(Objects.equals(orderSnapshotDTO.getOrdersStatus(), OrderStatusEnum.NO_PAY.getStatus()) && orderSnapshotDTO.getCreateTime().plusMinutes(15).isBefore(LocalDateTime.now())){
            //查询支付结果，如果支付最新状态仍是未支付进行取消订单
            OrdersPayResDTO ordersPayResDTO = ordersCreateService.getPayResultFromTradServer(orderSnapshotDTO.getId());
            int payResultFromTradServer = ordersPayResDTO.getPayStatus();
            if(payResultFromTradServer != OrderPayStatusEnum.PAY_SUCCESS.getStatus()){
                //取消订单
                OrderCancelDTO orderCancelDTO = BeanUtil.toBean(orderSnapshotDTO, OrderCancelDTO.class);
                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
                orderCancelDTO.setCancelReason("订单超时支付，自动取消");
                cancel(orderCancelDTO);
                //orderCancelDTO = getById(orderSnapshotDTO.getId());
                //因为上面的cancel方法中会更新订单状态，所以这里需要重新获取订单快照，并且不能走缓存
                String currentSnapshotJson = orderStateMachine.getCurrentSnapshot(String.valueOf(orderSnapshotDTO.getId()));
                orderSnapshotDTO = JsonUtils.toBean(currentSnapshotJson, OrderSnapshotDTO.class);
                return orderSnapshotDTO;
            }
        }
        return orderSnapshotDTO;
    }
//    private Orders canalIfPayOvertime(Orders orders){
//        //创建订单未支付15分钟后自动取消
//        if(Objects.equals(orders.getOrdersStatus(), OrderStatusEnum.NO_PAY.getStatus()) && orders.getCreateTime().plusMinutes(15).isBefore(LocalDateTime.now())){
//            //查询支付结果，如果支付最新状态仍是未支付进行取消订单
//            OrdersPayResDTO ordersPayResDTO = ordersCreateService.getPayResultFromTradServer(orders.getId());
//            int payResultFromTradServer = ordersPayResDTO.getPayStatus();
//            if(payResultFromTradServer != OrderPayStatusEnum.PAY_SUCCESS.getStatus()){
//                //取消订单
//                OrderCancelDTO orderCancelDTO = BeanUtil.toBean(orders, OrderCancelDTO.class);
//                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
//                orderCancelDTO.setCancelReason("订单超时支付，自动取消");
//                cancel(orderCancelDTO);
//                orders = getById(orders.getId());
//            }
//        }
//        return orders;
//    }


    /**
     * 订单评价
     *
     * @param ordersId 订单id
     */
    @Override
    @Transactional
    public void evaluationOrder(Long ordersId) {
//        //查询订单详情
//        Orders orders = queryById(ordersId);
//
//        //构建订单快照
//        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
//                .evaluationTime(LocalDateTime.now())
//                .build();
//
//        //订单状态变更
//        orderStateMachine.changeStatus(orders.getUserId(), orders.getId().toString(), OrderStatusChangeEventEnum.EVALUATE, orderSnapshotDTO);
    }

    /**
     * 取消订单
     *
     * @param orderCancelDTO 取消订单模型
     */
    @Override
    public void cancel(OrderCancelDTO orderCancelDTO) {
        //查询订单信息
        Orders orders = getById(orderCancelDTO.getId());
        BeanUtils.copyProperties(orders,orderCancelDTO);
        if (ObjectUtil.isNull(orders)) {
            throw new DbRuntimeException("找不到要取消的订单,订单号：{}",orderCancelDTO.getId());
        }
        //订单状态
        Integer ordersStatus = orders.getOrdersStatus();

        if(Objects.equals(OrderStatusEnum.NO_PAY.getStatus(), ordersStatus)){ //订单状态为待支付
            if(orders.getDiscountAmount()!=null){
                owner.cancelByNoPayWithCoupon(orderCancelDTO);
            }
            else{
                owner.cancelByNoPay(orderCancelDTO);
            }

        }else if(Objects.equals(OrderStatusEnum.DISPATCHING.getStatus(), ordersStatus)){ //订单状态为待服务
            if(orders.getDiscountAmount()!=null){
                owner.cancelByDispatchingWithCoupon(orderCancelDTO);
            }
            else{
                owner.cancelByDispatching(orderCancelDTO);
            }
            //新启动一个线程请求退款
            ordersHandler.requestRefundNewThread(orders.getId());
        }else{
            throw new CommonException("当前订单状态不支持取消");
        }
    }

    /**
     * 派单中状态取消订单（有优惠券）
     * @param orderCancelDTO
     */
    @GlobalTransactional
    public void cancelByDispatchingWithCoupon(OrderCancelDTO orderCancelDTO) {
        CouponUseReqDTO couponUseReqDTO = new CouponUseReqDTO();
        couponUseReqDTO.setOrdersId(orderCancelDTO.getId());
        Long couponId = couponApi.getCouponId(couponUseReqDTO);
        CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
        couponUseBackReqDTO.setId(couponId);
        couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
        couponUseBackReqDTO.setUserId(orderCancelDTO.getUserId());
        couponApi.useBack(couponUseBackReqDTO);
        cancelByDispatching(orderCancelDTO);
    }

    /**
     * 未支付状态取消订单（有优惠券）
     * @param orderCancelDTO
     */
    @GlobalTransactional
    public void cancelByNoPayWithCoupon(OrderCancelDTO orderCancelDTO) {
        CouponUseReqDTO couponUseReqDTO = new CouponUseReqDTO();
        couponUseReqDTO.setOrdersId(orderCancelDTO.getId());
        Long couponId = couponApi.getCouponId(couponUseReqDTO);
        CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
        couponUseBackReqDTO.setId(couponId);
        couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
        couponUseBackReqDTO.setUserId(orderCancelDTO.getUserId());
        couponApi.useBack(couponUseBackReqDTO);
        cancelByNoPay(orderCancelDTO);
    }

    @Override
    public PageResult<OperationOrdersPageResDTO> operationQueryList(OrderPageQueryReqDTO orderPageQueryReqDTO) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
                .eq(ObjectUtils.isNotNull(orderPageQueryReqDTO.getOrdersStatus()), Orders::getOrdersStatus, orderPageQueryReqDTO.getOrdersStatus())
                .eq(ObjectUtils.isNotNull(orderPageQueryReqDTO.getRefundStatus()), Orders::getRefundStatus, orderPageQueryReqDTO.getRefundStatus())
                .eq(ObjectUtils.isNotNull(orderPageQueryReqDTO.getContactsPhone()), Orders::getContactsPhone, orderPageQueryReqDTO.getContactsPhone())
                .eq(ObjectUtils.isNotNull(orderPageQueryReqDTO.getId()), Orders::getId, orderPageQueryReqDTO.getId())
                .ge(ObjectUtils.isNotNull(orderPageQueryReqDTO.getMinCreateTime()), Orders::getCreateTime, orderPageQueryReqDTO.getMinCreateTime())
                .le(ObjectUtils.isNotNull(orderPageQueryReqDTO.getMaxCreateTime()), Orders::getCreateTime, orderPageQueryReqDTO.getMaxCreateTime())
                .select(Orders::getId);//只查询id列
        Page<Orders> queryPage = new Page<>(orderPageQueryReqDTO.getPageNo(), orderPageQueryReqDTO.getPageSize());
        queryPage.addOrder(OrderItem.desc(SORT_BY));
        queryPage.setSearchCount(false);

        //2.使用覆盖索引查询订单列表（）
        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
        if (ObjectUtil.isEmpty(ordersPage.getRecords())) {
            return new PageResult<>();
        }
        //3.再根据订单ID查询聚集索引拿到数据
        //提取订单id列表
        List<Long> orderIds= CollUtils.getFieldValues(ordersPage.getRecords(), Orders::getId);
        List<Orders> ordersList = batchQuery(orderIds);
        List<OperationOrdersPageResDTO> orderSimpleResDTOS = BeanUtil.copyToList(ordersList, OperationOrdersPageResDTO.class);
        PageResult pageResult = new PageResult();
        pageResult.setTotal(ordersPage.getTotal());
        pageResult.setList(orderSimpleResDTOS);
        return pageResult;
    }

    //未支付状态取消订单
    @Transactional(rollbackFor = Exception.class)
    public void cancelByNoPay(OrderCancelDTO orderCancelDTO) {
        //保存取消订单记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);
/*        //更新订单状态为取消订单
        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder()
                .id(orderCancelDTO.getId())
                .originStatus(OrderStatusEnum.NO_PAY.getStatus())
                .targetStatus(OrderStatusEnum.CANCELED.getStatus())
                .build();
        int result = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (result <= 0) {
            throw new DbRuntimeException("订单取消事件处理失败");
        }*/
        //使用状态机处理订单取消
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .cancellerId(orderCancelDTO.getCurrentUserId())
                .cancelTime(LocalDateTime.now())
                .cancelReason(orderCancelDTO.getCancelReason())
                .build();
        orderStateMachine.changeStatus(null, orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CANCEL, orderSnapshotDTO);

    }

    //派单中状态取消订单
    @Transactional(rollbackFor = Exception.class)
    public void cancelByDispatching(OrderCancelDTO orderCancelDTO) {
        //保存取消订单记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);
/*        //更新订单状态为关闭订单
        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder().id(orderCancelDTO.getId())
                .originStatus(OrderStatusEnum.DISPATCHING.getStatus())
                .targetStatus(OrderStatusEnum.CLOSED.getStatus())
                .refundStatus(OrderRefundStatusEnum.REFUNDING.getStatus())//退款状态为退款中
                .build();
        int result = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (result <= 0) {
            throw new DbRuntimeException("待服务订单关闭事件处理失败");
        }*/
        //使用状态机处理订单取消
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .cancellerId(orderCancelDTO.getCurrentUserId())
                .cancelTime(LocalDateTime.now())
                .cancelReason(orderCancelDTO.getCancelReason())
                .build();
        orderStateMachine.changeStatus(null, orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CLOSE_DISPATCHING_ORDER, orderSnapshotDTO);
        //添加退款记录

        OrdersRefund ordersRefund = new OrdersRefund();
        ordersRefund.setId(orderCancelDTO.getId());
        ordersRefund.setTradingOrderNo(orderCancelDTO.getTradingOrderNo());
        ordersRefund.setRealPayAmount(orderCancelDTO.getRealPayAmount());
        ordersRefundService.save(ordersRefund);
    }

}
