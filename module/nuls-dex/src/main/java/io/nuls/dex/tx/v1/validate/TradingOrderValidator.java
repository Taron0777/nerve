package io.nuls.dex.tx.v1.validate;

import io.nuls.base.basic.NulsByteBuffer;
import io.nuls.base.data.CoinTo;
import io.nuls.base.data.Transaction;
import io.nuls.core.constant.ErrorCode;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.crypto.HexUtil;
import io.nuls.core.exception.NulsException;
import io.nuls.dex.context.DexConfig;
import io.nuls.dex.context.DexConstant;
import io.nuls.dex.context.DexErrorCode;
import io.nuls.dex.manager.DexManager;
import io.nuls.dex.manager.TradingContainer;
import io.nuls.dex.model.po.CoinTradingPo;
import io.nuls.dex.model.txData.TradingOrder;
import io.nuls.dex.util.LoggerUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户挂单委托验证器
 * 1.验证币对是否存在
 */
@Component
public class TradingOrderValidator {

    @Autowired
    private DexManager dexManager;
    @Autowired
    private DexConfig config;

    public Map<String, Object> validateTxs(List<Transaction> txs) {
        //存放验证不通过的交易
        List<Transaction> invalidTxList = new ArrayList<>();
        ErrorCode errorCode = null;
        TradingOrder order;
        Transaction tx;
        CoinTo coinTo;
        for (int i = 0; i < txs.size(); i++) {
            tx = txs.get(i);
            try {
                order = new TradingOrder();
                order.parse(new NulsByteBuffer(tx.getTxData()));
                coinTo = tx.getCoinDataInstance().getTo().get(0);
                validate(order, coinTo);

//                LoggerUtil.dexLog.debug("验证通过挂单交易hash:" + tx.getHash().toHex());
            } catch (NulsException e) {
                LoggerUtil.dexLog.error(e);
                LoggerUtil.dexLog.error("txHash: " + tx.getHash().toHex());
                errorCode = e.getErrorCode();
                invalidTxList.add(tx);
            }
        }
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("txList", invalidTxList);
        resultMap.put("errorCode", errorCode);
        return resultMap;
    }

    /**
     * 判断订单的币对交易是否存在
     * 验证coinFrom里的资产是否等于挂单委托单资产
     * 判断最小交易额
     *
     * @param order
     * @return
     */
    private void validate(TradingOrder order, CoinTo coinTo) throws NulsException {
        //验证交易数据合法性
        if (order.getType() != DexConstant.TRADING_ORDER_BUY_TYPE && order.getType() != DexConstant.TRADING_ORDER_SELL_TYPE) {
            throw new NulsException(DexErrorCode.DATA_ERROR, "tradingOrder type error");
        }
        //验证交易to格式
        if (coinTo.getLockTime() != DexConstant.DEX_LOCK_TIME) {
            throw new NulsException(DexErrorCode.DATA_ERROR, "coinTo error");
        }
        if (order.getPrice().compareTo(BigInteger.ZERO) == 0) {
            throw new NulsException(DexErrorCode.DATA_ERROR, "price error");
        }
        if (order.getAmount().compareTo(BigInteger.ZERO) == 0) {
            throw new NulsException(DexErrorCode.DATA_ERROR, "orderAmount error");
        }
        //判断订单的币对交易是否存在
        String hashHex = HexUtil.encode(order.getTradingHash());
        TradingContainer container = dexManager.getTradingContainer(hashHex);
        if (container == null) {
            throw new NulsException(DexErrorCode.DATA_NOT_FOUND, "coinTrading not exist");
        }

        //判断coinTo里的资产是否和order订单的数量匹配
        CoinTradingPo coinTrading = container.getCoinTrading();
        if (order.getType() == DexConstant.TRADING_ORDER_BUY_TYPE) {
            //验证coinFrom里的资产是否等于挂单委托单资产
            if (coinTo.getAssetsChainId() != coinTrading.getQuoteAssetChainId() ||
                    coinTo.getAssetsId() != coinTrading.getQuoteAssetId()) {
                throw new NulsException(DexErrorCode.ORDER_COIN_NOT_EQUAL);
            }
            //验证最小交易额，以及最小小数位数
            if (order.getAmount().compareTo(coinTrading.getMinTradingAmount()) < 0) {
                throw new NulsException(DexErrorCode.BELOW_TRADING_MIN_SIZE);
            }

            //计算可兑换交易币种数量
            //计价货币数量 / 价格 = 实际可兑换交易币种数量
            BigDecimal price = new BigDecimal(order.getPrice()).movePointLeft(coinTrading.getQuoteDecimal());
            BigDecimal amount = new BigDecimal(coinTo.getAmount()).movePointLeft(coinTrading.getQuoteDecimal());
            amount = amount.divide(price, coinTrading.getBaseDecimal(), RoundingMode.DOWN);
            amount = amount.movePointRight(coinTrading.getBaseDecimal());

            if (amount.toBigInteger().compareTo(order.getAmount()) < 0) {
                throw new NulsException(DexErrorCode.DATA_ERROR, "coinTo amount error");
            }

        } else {
            if (coinTo.getAssetsChainId() != coinTrading.getBaseAssetChainId() ||
                    coinTo.getAssetsId() != coinTrading.getBaseAssetId()) {
                throw new NulsException(DexErrorCode.ORDER_COIN_NOT_EQUAL);
            }
            //验证最小交易额
            if (order.getAmount().compareTo(coinTrading.getMinTradingAmount()) < 0) {
                throw new NulsException(DexErrorCode.BELOW_TRADING_MIN_SIZE);
            }
            if (coinTo.getAmount().compareTo(order.getAmount()) != 0) {
                throw new NulsException(DexErrorCode.DATA_ERROR, "coinTo amount error");
            }
        }
    }
}
