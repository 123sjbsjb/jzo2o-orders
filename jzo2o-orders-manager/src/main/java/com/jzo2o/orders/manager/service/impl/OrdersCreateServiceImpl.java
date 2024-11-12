package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.db.DbRuntimeException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseReqDTO;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.market.dto.response.CouponUseResDTO;
import com.jzo2o.api.trade.TradingApi;
import com.jzo2o.api.trade.dto.request.NativePayReqDTO;
import com.jzo2o.api.trade.dto.response.NativePayResDTO;
import com.jzo2o.api.trade.dto.response.TradingResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.NumberUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.constants.RedisConstants;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.porperties.TradeProperties;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.impl.client.*;
import com.jzo2o.orders.manager.service.impl.client.NativePayClient;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.constants.ErrorInfo.Code;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.jzo2o.common.constants.ErrorInfo.Code.TRADE_FAILED;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersCreateServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersCreateService {

    @Resource
    private CustomerClient customerClient;
    @Resource
    private FoundationsClient foundationsClient;
    @Resource
    private NativePayClient nativePayClient;
    @Resource
    private TradingClient tradingClient;
    @Resource
    private TradeProperties tradeProperties;
    @Resource
    private RedisTemplate<String, Long> redisTemplate;
    @Resource
    private ServeApi serveApi;
    @Resource
    private CouponApi couponApi;
    @Resource
    private MarketClient marketClient;

    //将自己的代理对象注入
    @Resource
    private IOrdersCreateService owner;

    @Resource
    private OrderStateMachine orderStateMachine;

    /**
     * 生成订单id 格式：{yyMMdd}{13位id}
     *
     * @return
     */
    private Long generateOrderId() {
        //通过Redis自增序列得到序号
        Long id = redisTemplate.opsForValue().increment(RedisConstants.Lock.ORDERS_SHARD_KEY_ID_GENERATOR, 1);
        //生成订单号   2位年+2位月+2位日+13位序号
        long orderId = DateUtils.getFormatDate(LocalDateTime.now(), "yyMMdd") * 10000000000000L + id;
        return orderId;
    }

    @Override
    public PlaceOrderResDTO placeOrder(PlaceOrderReqDTO placeOrderReqDTO) {
        //1.远程调用获取订单相关信息
        //1.1 下单人信息-远程调用jzo2o-customer服务
        AddressBookResDTO customerDetail = customerClient.getDetail(placeOrderReqDTO.getAddressBookId());
        if (customerDetail == null) {
            throw new BadRequestException("预约地址异常，无法下单");
        }
        //1.2 订单服务信息-远程调用jzo2o-foundations服务
        ServeAggregationResDTO foundationsDetail = foundationsClient.getDetail(placeOrderReqDTO.getServeId());
        if (foundationsDetail == null || foundationsDetail.getSaleStatus() != 2) {
            throw new BadRequestException("服务不可用");
        }

        //2 下单前数据准备
        Orders orders = new Orders();
        //2.1 生成订单id
        Long orderId = generateOrderId();
        //2.2 下单人id
        orders.setUserId(UserContext.currentUserId());
        //2.3 设置订单id
        orders.setId(orderId);
        //2.4 设置服务类型id
        orders.setServeTypeId(foundationsDetail.getServeTypeId());
        //2.5 设置服务类型名称
        orders.setServeTypeName(foundationsDetail.getServeTypeName());
        //2.6 设置服务项id
        orders.setServeItemId(foundationsDetail.getServeItemId());
        //2.7 设置服务项名称
        orders.setServeItemName(foundationsDetail.getServeItemName());
        //2.8 设置服务项图片
        orders.setServeItemImg(foundationsDetail.getServeItemImg());
        //2.9 设置服务单位
        orders.setUnit(foundationsDetail.getUnit());
        //2.10 设置服务id
        orders.setServeId(placeOrderReqDTO.getServeId());
        //2.11 设置订单状态
        orders.setOrdersStatus(OrderStatusEnum.NO_PAY.getStatus());
        //2.12 设置支付状态
        orders.setPayStatus(OrderPayStatusEnum.NO_PAY.getStatus());

        //3 订单价格信息
        //3.1 单价
        orders.setPrice(foundationsDetail.getPrice());
        //3.2 购买数量
        orders.setPurNum(placeOrderReqDTO.getPurNum());
        //3.3 总价
        BigDecimal totalAmount = foundationsDetail.getPrice().multiply(new BigDecimal(placeOrderReqDTO.getPurNum()));
        orders.setTotalAmount(totalAmount);
        //3.4 优惠金额
        orders.setDiscountAmount(BigDecimal.ZERO);
        //3.5 实付金额
        orders.setRealPayAmount(NumberUtils.sub(orders.getTotalAmount(), orders.getDiscountAmount()));

        //4 服务地址信息
        //4.1 设置服务cityCode
        orders.setCityCode(foundationsDetail.getCityCode());
        //4.2 设置服务地址
        String serveAddr = new StringBuffer()
                .append(customerDetail.getProvince())
                .append(customerDetail.getCity())
                .append(customerDetail.getCounty())
                .append(customerDetail.getAddress())
                .toString();
        orders.setServeAddress(serveAddr);
        //4.3 设置服务人电话
        orders.setContactsPhone(customerDetail.getPhone());
        //4.4 设置服务人姓名
        orders.setContactsName(customerDetail.getName());

        //5 服务其他信息
        //5.1 设置服务开始时间
        orders.setServeStartTime(placeOrderReqDTO.getServeStartTime());
        //5.2 经纬度
        orders.setLon(customerDetail.getLon());
        orders.setLat(customerDetail.getLat());
        //5.3 排序字段
        long sortBy = DateUtils.toEpochMilli(orders.getServeStartTime()) + orders.getId() % 100000;
        orders.setSortBy(sortBy);

        ////6 插入数据库
        //owner.add(orders);
        // 使用优惠券下单
        if (ObjectUtils.isNotNull(placeOrderReqDTO.getCouponId())) {
            // 使用优惠券
            owner.addWithCoupon(orders, placeOrderReqDTO.getCouponId());
        } else {
            // 无优惠券下单，走本地事务
            owner.add(orders);
        }
        //7 返回结果
        return new PlaceOrderResDTO(orders.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(Orders orders) {
        boolean save = this.save(orders);
        if (!save) {
            throw new DbRuntimeException("下单失败");
        }
        //调用状态机的启动方法，尽量保存快照
        //Long dbShardId, String bizId, T bizSnapshot
        //dbShardId分库分表id bizId订单id bizSnapshot快照
        OrderSnapshotDTO orderSnapshotDTO=BeanUtils.toBean(getById(orders.getId()),OrderSnapshotDTO.class);
        //状态表已经分库了，所以这里传用户id
        orderStateMachine.start(orders.getUserId(),orders.getId().toString(),orderSnapshotDTO);
    }

    /**
     * 订单支付
     *
     * @param id              订单id
     * @param ordersPayReqDTO 订单支付请求体
     * @return 订单支付响应体
     */
    @Override
    public OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO) {
        Orders orders =  baseMapper.selectById(id);
        if (ObjectUtil.isNull(orders)) {
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        //订单的支付状态为成功直接返回
        if (OrderPayStatusEnum.PAY_SUCCESS.getStatus() == orders.getPayStatus()
                && ObjectUtil.isNotEmpty(orders.getTradingOrderNo())) {
            OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
            BeanUtil.copyProperties(orders, ordersPayResDTO);
            ordersPayResDTO.setProductOrderNo(orders.getId());
            return ordersPayResDTO;
        } else {
            //生成二维码
            NativePayResDTO nativePayResDTO = generateQrCode(orders, ordersPayReqDTO.getTradingChannel());
            OrdersPayResDTO ordersPayResDTO = BeanUtil.toBean(nativePayResDTO, OrdersPayResDTO.class);
            return ordersPayResDTO;
        }
    }

    @Override
    public OrdersPayResDTO getPayResultFromTradServer(Long id) {
        //查看订单是否存在
        Orders orders =  baseMapper.selectById(id);
        if (ObjectUtil.isNull(orders)) {
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        //如果当前订单支付状态为未支付，并且交易单号不为空，则调用支付服务查询支付结果
        Integer payStatus = orders.getPayStatus();
        if (OrderPayStatusEnum.NO_PAY.getStatus() == orders.getPayStatus()
                && ObjectUtil.isNotEmpty(orders.getTradingOrderNo())) {
            //拿到交易单号
            Long tradingOrderNo = orders.getTradingOrderNo();
            //根据交易单号请求支付结果接口
            TradingResDTO tradResultByTradingOrderNo = tradingClient.findTradResultByTradingOrderNo(tradingOrderNo);
            //如果支付结果为成果
            if (ObjectUtil.isNotNull(tradResultByTradingOrderNo)
                    && ObjectUtil.equals(tradResultByTradingOrderNo.getTradingState(), TradingStateEnum.YJS)) {
                //设置订单的支付状态成功
                TradeStatusMsg msg = TradeStatusMsg.builder()
                        .productOrderNo(orders.getId())
                        .tradingChannel(tradResultByTradingOrderNo.getTradingChannel())
                        .statusCode(TradingStateEnum.YJS.getCode())
                        .tradingOrderNo(tradResultByTradingOrderNo.getTradingOrderNo())
                        .transactionId(tradResultByTradingOrderNo.getTransactionId())
                        .build();
                owner.paySuccess(msg);
                //构造返回数据
                OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(tradResultByTradingOrderNo, OrdersPayResDTO.class);
                ordersPayResDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                return ordersPayResDTO;
            }
        }
        OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
        ordersPayResDTO.setPayStatus(payStatus);
        ordersPayResDTO.setProductOrderNo(orders.getId());
        ordersPayResDTO.setTradingOrderNo(orders.getTradingOrderNo());
        ordersPayResDTO.setTradingChannel(orders.getTradingChannel());
        return ordersPayResDTO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void paySuccess(TradeStatusMsg tradeStatusMsg) {
        //查询订单
        Orders orders = baseMapper.selectById(tradeStatusMsg.getProductOrderNo());
        if (ObjectUtil.isNull(orders)) {
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        //校验支付状态如果不是待支付状态则不作处理
        if (ObjectUtil.notEqual(OrderPayStatusEnum.NO_PAY.getStatus(), orders.getPayStatus())) {
            log.info("更新订单支付成功，当前订单:{}支付状态不是待支付状态", orders.getId());
            return;
        }
        //校验订单状态如果不是待支付状态则不作处理
        if (ObjectUtils.notEqual(OrderStatusEnum.NO_PAY.getStatus(),orders.getOrdersStatus())) {
            log.info("更新订单支付成功，当前订单:{}状态不是待支付状态", orders.getId());
        }

        //第三方支付单号校验
        if (ObjectUtil.isEmpty(tradeStatusMsg.getTransactionId())) {
            throw new CommonException("支付成功通知缺少第三方支付单号");
        }
        /*//更新订单的支付状态及第三方交易单号等信息
        boolean update = lambdaUpdate()
                .eq(Orders::getId, orders.getId())
                .set(Orders::getPayTime, LocalDateTime.now())//支付时间
                .set(Orders::getTradingOrderNo, tradeStatusMsg.getTradingOrderNo())//交易单号
                .set(Orders::getTradingChannel, tradeStatusMsg.getTradingChannel())//支付渠道
                .set(Orders::getTransactionId, tradeStatusMsg.getTransactionId())//第三方支付交易号
                .set(Orders::getPayStatus, OrderPayStatusEnum.PAY_SUCCESS.getStatus())//支付状态
                .set(Orders::getOrdersStatus, OrderStatusEnum.DISPATCHING.getStatus())//订单状态更新为派单中
                .update();
        if(!update){
            log.info("更新订单:{}支付成功失败", orders.getId());
            throw new CommonException("更新订单"+orders.getId()+"支付成功失败");
        }*/
        //使用状态机更新订单状态
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .payTime(LocalDateTime.now())
                .tradingOrderNo(tradeStatusMsg.getTradingOrderNo())
                .tradingChannel(tradeStatusMsg.getTradingChannel())
                .thirdOrderId(tradeStatusMsg.getTransactionId())
                .build();
        //Long dbShardId, String bizId, StatusChangeEvent statusChangeEventEnum, T bizSnapshot
        orderStateMachine.changeStatus(orders.getUserId(), orders.getId().toString(), OrderStatusChangeEventEnum.PAYED, orderSnapshotDTO);
    }

    /**
     * 查询超时订单id列表
     *
     * @param count 数量
     * @return 订单id列表
     */
    @Override
    public List<Orders> queryOverTimePayOrdersListByCount(Integer count) {
        //根据订单创建时间查询超过15分钟未支付的订单
        List<Orders> list = lambdaQuery()
                //查询待支付状态的订单
                .eq(Orders::getOrdersStatus, OrderStatusEnum.NO_PAY.getStatus())
                //小于当前时间减去15分钟，即待支付状态已过15分钟
                .lt(Orders::getCreateTime, LocalDateTime.now().minusMinutes(15))
                .last("limit " + count)
                .list();
        return list;
    }

    @Override
    public List<AvailableCouponsResDTO> getAvailableCoupons(Long serveId, Integer purNum) {
        // 1.获取服务
        ServeAggregationResDTO serveResDTO = serveApi.findById(serveId);
        if (serveResDTO == null || serveResDTO.getSaleStatus() != 2) {
            throw new BadRequestException("服务不可用");
        }
        // 2.计算订单总金额
        BigDecimal totalAmount = serveResDTO.getPrice().multiply(new BigDecimal(purNum));
        // 3.获取可用优惠券,并返回优惠券列表
        List<AvailableCouponsResDTO> available = marketClient.getAvailable(totalAmount);
        return available;
    }

    @GlobalTransactional
    public void addWithCoupon(Orders orders, Long couponId) {
        CouponUseReqDTO couponUseReqDTO = new CouponUseReqDTO();
        couponUseReqDTO.setOrdersId(orders.getId());
        couponUseReqDTO.setId(couponId);
        couponUseReqDTO.setTotalAmount(orders.getTotalAmount());
        //优惠券核销
        CouponUseResDTO couponUseResDTO = couponApi.use(couponUseReqDTO);
        // 设置优惠金额
        orders.setDiscountAmount(couponUseResDTO.getDiscountAmount());
        // 计算实付金额
        BigDecimal realPayAmount = orders.getTotalAmount().subtract(orders.getDiscountAmount());
        orders.setRealPayAmount(realPayAmount);
        //保存订单
        add(orders);
    }

    //生成二维码
    private NativePayResDTO generateQrCode(Orders orders, PayChannelEnum tradingChannel) {

        //判断支付渠道
        Long enterpriseId = ObjectUtil.equal(PayChannelEnum.ALI_PAY, tradingChannel) ?
                tradeProperties.getAliEnterpriseId() : tradeProperties.getWechatEnterpriseId();

        //构建支付请求参数
        NativePayReqDTO nativePayReqDTO = new NativePayReqDTO();
        //商户号
        nativePayReqDTO.setEnterpriseId(enterpriseId);
        //业务系统标识
        nativePayReqDTO.setProductAppId("jzo2o.orders");
        //家政订单号
        nativePayReqDTO.setProductOrderNo(orders.getId());
        //支付渠道
        nativePayReqDTO.setTradingChannel(tradingChannel);
        //支付金额
        nativePayReqDTO.setTradingAmount(orders.getRealPayAmount());
        //备注信息
        nativePayReqDTO.setMemo(orders.getServeItemName());
        //判断是否切换支付渠道
        if (ObjectUtil.isNotEmpty(orders.getTradingChannel())
                && ObjectUtil.notEqual(orders.getTradingChannel(), tradingChannel.toString())) {
            nativePayReqDTO.setChangeChannel(true);
        }
        //生成支付二维码
        NativePayResDTO downLineTrading = nativePayClient.createDownLineTrading(nativePayReqDTO);
        if(ObjectUtils.isNotNull(downLineTrading)){
            log.info("订单:{}请求支付,生成二维码:{}",orders.getId(),downLineTrading.toString());
            //将二维码更新到交易订单中
            boolean update = lambdaUpdate()
                    .eq(Orders::getId, downLineTrading.getProductOrderNo())
                    .set(Orders::getTradingOrderNo, downLineTrading.getTradingOrderNo())
                    .set(Orders::getTradingChannel, downLineTrading.getTradingChannel())
                    .update();
            if(!update){
                throw new CommonException("订单:"+orders.getId()+"请求支付更新交易单号失败");
            }
        }
        return downLineTrading;
    }
}
