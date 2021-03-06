/**
 * MIT License
 * <p>
 Copyright (c) 2019-2020 nerve.network
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package nerve.network.converter.v1;

/**
 * @author: Chino
 * @date: 2020/3/19
 */

import io.nuls.base.basic.AddressTool;
import io.nuls.base.data.*;
import io.nuls.base.protocol.TransactionProcessor;
import nerve.network.converter.config.ConverterContext;
import nerve.network.converter.constant.ConverterConstant;
import nerve.network.converter.constant.ConverterErrorCode;
import nerve.network.converter.manager.ChainManager;
import nerve.network.converter.model.bo.Chain;
import nerve.network.converter.model.bo.HeterogeneousAddress;
import nerve.network.converter.model.po.ConfirmWithdrawalPO;
import nerve.network.converter.model.po.DistributionFeePO;
import nerve.network.converter.model.txdata.DistributionFeeTxData;
import nerve.network.converter.rpc.call.TransactionCall;
import nerve.network.converter.storage.ConfirmWithdrawalStorageService;
import nerve.network.converter.storage.DistributionFeeStorageService;
import nerve.network.converter.utils.ConverterUtil;
import io.nuls.core.constant.TxType;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.exception.NulsException;
import io.nuls.core.log.logback.NulsLogger;
import io.nuls.core.model.BigIntegerUtils;

import java.math.BigInteger;
import java.util.*;

@Component("DistributionFeeV1")
public class DistributionFeeProcessor implements TransactionProcessor {

    @Autowired
    private ChainManager chainManager;
    @Autowired
    private ConfirmWithdrawalStorageService confirmWithdrawalStorageService;
    @Autowired
    private DistributionFeeStorageService distributionFeeStorageService;

    @Override
    public int getType() {
        return TxType.DISTRIBUTION_FEE;
    }

    @Override
    public Map<String, Object> validate(int chainId, List<Transaction> txs, Map<Integer, List<Transaction>> txMap, BlockHeader blockHeader) {
        if (txs.isEmpty()) {
            return null;
        }
        Chain chain = null;
        Map<String, Object> result = null;
        try {
            chain = chainManager.getChain(chainId);
            NulsLogger log = chain.getLogger();
            String errorCode = null;
            result = new HashMap<>(ConverterConstant.INIT_CAPACITY_4);
            List<Transaction> failsList = new ArrayList<>();
            //区块内业务重复交易检查
            Set<String> setDuplicate = new HashSet<>();
            for (Transaction tx : txs) {
                DistributionFeeTxData txData = ConverterUtil.getInstance(tx.getTxData(), DistributionFeeTxData.class);
                NulsHash basisTxHash = txData.getBasisTxHash();
                String originalHash = basisTxHash.toHex();
                if(setDuplicate.contains(originalHash)){
                    // 区块内业务重复交易
                    failsList.add(tx);
                    errorCode = ConverterErrorCode.BLOCK_TX_DUPLICATION.getCode();
                    log.error(ConverterErrorCode.BLOCK_TX_DUPLICATION.getMsg());
                    continue;
                }
                // 验证是否重复发奖励
                DistributionFeePO po = distributionFeeStorageService.findByBasisTxHash(chain, basisTxHash);
                if(null != po){
                    // 说明该提现交易 已经发出过确认提现交易,本次交易为重复的确认提现交易
                    failsList.add(tx);
                    errorCode = ConverterErrorCode.DISTRIBUTION_FEE_IS_DUPLICATION.getCode();
                    log.error(ConverterErrorCode.DISTRIBUTION_FEE_IS_DUPLICATION.getMsg());
                    continue;
                }

                // 获取原始交易
                Transaction basisTx = TransactionCall.getConfirmedTx(chain, basisTxHash);
                if (null == basisTx) {
                    failsList.add(tx);
                    // Nerve原始交易不存在
                    errorCode = ConverterErrorCode.WITHDRAWAL_TX_NOT_EXIST.getCode();
                    log.error(ConverterErrorCode.WITHDRAWAL_TX_NOT_EXIST.getMsg());
                    continue;
                }

                // 根据原始交易 来验证
                switch (basisTx.getType()) {
                    case TxType.WITHDRAWAL:
                            String eCode = validWithdrawalDistribution(chain, tx, basisTx);
                            if(null != eCode){
                                failsList.add(tx);
                                errorCode = eCode;
                                continue;
                            }
                        break;
                    case TxType.PROPOSAL:

                        break;
                    default:
                }
                setDuplicate.add(originalHash);
            }
            result.put("txList", failsList);
            result.put("errorCode", errorCode);
        } catch (Exception e) {
            chain.getLogger().error(e);
            result.put("txList", txs);
            result.put("errorCode", ConverterErrorCode.SYS_UNKOWN_EXCEPTION.getCode());
        }
        return result;
    }

    /**
     * 验证提现交易的手续费补贴分发
     * 通过则返回空, 不通过返回错误码
     *
     * @param chain
     * @param tx
     * @param basisTx
     * @return
     */
    private String validWithdrawalDistribution(Chain chain, Transaction tx, Transaction basisTx) {
        // 获取提现确认交易中的 分发手续费地址
        ConfirmWithdrawalPO po = confirmWithdrawalStorageService.findByWithdrawalTxHash(chain, basisTx.getHash());
        List<HeterogeneousAddress> listDistributionFee = po.getListDistributionFee();
        if (null == listDistributionFee || listDistributionFee.isEmpty()) {
            chain.getLogger().error(ConverterErrorCode.DISTRIBUTION_ADDRESS_LIST_EMPTY.getMsg());
            return ConverterErrorCode.DISTRIBUTION_ADDRESS_LIST_EMPTY.getCode();
        }
        // 确认提现交易应发手续费的地址列表
        List<byte[]> listBasisTxRewardAddressBytes = new ArrayList<>();
        for (HeterogeneousAddress addr : listDistributionFee) {
            String address = chain.getDirectorRewardAddress(addr);
            listBasisTxRewardAddressBytes.add(AddressTool.getAddress(address));
        }
        CoinData coinData = null;
        try {
            coinData = ConverterUtil.getInstance(tx.getCoinData(), CoinData.class);
        } catch (NulsException e) {
            chain.getLogger().error(e);
            return e.getErrorCode().getCode();
        }
        if(listBasisTxRewardAddressBytes.size() != coinData.getTo().size()){
            chain.getLogger().error(ConverterErrorCode.DISTRIBUTION_ADDRESS_MISMATCH.getMsg());
            return ConverterErrorCode.DISTRIBUTION_ADDRESS_MISMATCH.getCode();
        }
        // 补贴交易的收到手续费的地址列表
        List<byte[]> listDistributionTxRewardAddressBytes = new ArrayList<>();
        // 计算 每个节点补贴多少手续费
        BigInteger count = BigInteger.valueOf(listBasisTxRewardAddressBytes.size());
        BigInteger amount = ConverterContext.WITHDRAWAL_DISTRIBUTION_FEE.divide(count);
        for (CoinTo coinTo : coinData.getTo()) {
            if(!BigIntegerUtils.isEqual(coinTo.getAmount(), amount)){
                chain.getLogger().error(ConverterErrorCode.DISTRIBUTION_FEE_ERROR.getMsg());
                return ConverterErrorCode.DISTRIBUTION_FEE_ERROR.getCode();
            }
            listDistributionTxRewardAddressBytes.add(coinTo.getAddress());
        }

        for(byte[] addrBasisBytes : listBasisTxRewardAddressBytes){
            boolean hit = false;
            for(byte[] addrDistributionBytes : listDistributionTxRewardAddressBytes) {
                if (Arrays.equals(addrBasisBytes, addrDistributionBytes)){
                    hit = true;
                }
            }
            if(!hit){
                chain.getLogger().error(ConverterErrorCode.DISTRIBUTION_ADDRESS_MISMATCH.getMsg());
                return ConverterErrorCode.DISTRIBUTION_ADDRESS_MISMATCH.getCode();
            }
        }
        return null;
    }

    /**
     * 验证提案投票交易的手续费补贴分发
     * 通过则返回空, 不通过返回错误码
     *
     * @param chain
     * @param tx
     * @param basisTx
     * @return
     */
    private String validProposalDistribution(Chain chain, Transaction tx, Transaction basisTx) {
        // TODO: 2020/3/20 验证为提案交易投票后的手续费补贴交易
        return null;
    }


    @Override
    public boolean commit(int chainId, List<Transaction> txs, BlockHeader blockHeader, int syncStatus) {
        return commit(chainId, txs, blockHeader, syncStatus, true);
    }

    private boolean commit(int chainId, List<Transaction> txs, BlockHeader blockHeader, int syncStatus, boolean failRollback) {
        if (txs.isEmpty()) {
            return true;
        }
        Chain chain = chainManager.getChain(chainId);
        try {
            for(Transaction tx : txs) {
                DistributionFeeTxData txData = ConverterUtil.getInstance(tx.getTxData(), DistributionFeeTxData.class);
                distributionFeeStorageService.save(chain, new DistributionFeePO(txData.getBasisTxHash(), tx.getHash()));
                chain.getLogger().debug("[commit] 补贴手续费交易 hash:{}", tx.getHash().toHex());
            }
            return true;
        } catch (Exception e) {
            chain.getLogger().error(e);
            if (failRollback) {
                rollback(chainId, txs, blockHeader, false);
            }
            return false;
        }
    }


    @Override
    public boolean rollback(int chainId, List<Transaction> txs, BlockHeader blockHeader) {
        return rollback(chainId, txs, blockHeader, true);
    }

    private boolean rollback(int chainId, List<Transaction> txs, BlockHeader blockHeader, boolean failCommit) {
        if (txs.isEmpty()) {
            return true;
        }
        Chain chain = chainManager.getChain(chainId);
        try {
            for(Transaction tx : txs) {
                DistributionFeeTxData txData = ConverterUtil.getInstance(tx.getTxData(), DistributionFeeTxData.class);
                distributionFeeStorageService.deleteByBasisTxHash(chain, txData.getBasisTxHash());
                chain.getLogger().debug("[rollback] 补贴手续费交易 hash:{}", tx.getHash().toHex());
            }
            return true;
        } catch (Exception e) {
            chain.getLogger().error(e);
            if (failCommit) {
                commit(chainId, txs, blockHeader, 0, false);
            }
            return false;
        }
    }
}
