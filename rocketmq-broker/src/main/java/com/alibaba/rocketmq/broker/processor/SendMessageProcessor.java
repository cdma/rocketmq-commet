/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.broker.processor;

import com.alibaba.rocketmq.broker.BrokerController;
import com.alibaba.rocketmq.broker.mqtrace.ConsumeMessageContext;
import com.alibaba.rocketmq.broker.mqtrace.ConsumeMessageHook;
import com.alibaba.rocketmq.broker.mqtrace.SendMessageContext;
import com.alibaba.rocketmq.broker.mqtrace.SendMessageHook;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.TopicConfig;
import com.alibaba.rocketmq.common.TopicFilterType;
import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.constant.PermName;
import com.alibaba.rocketmq.common.help.FAQUrl;
import com.alibaba.rocketmq.common.message.MessageAccessor;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.alibaba.rocketmq.common.message.MessageDecoder;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.protocol.RequestCode;
import com.alibaba.rocketmq.common.protocol.ResponseCode;
import com.alibaba.rocketmq.common.protocol.header.ConsumerSendMsgBackRequestHeader;
import com.alibaba.rocketmq.common.protocol.header.SendMessageRequestHeader;
import com.alibaba.rocketmq.common.protocol.header.SendMessageResponseHeader;
import com.alibaba.rocketmq.common.subscription.SubscriptionGroupConfig;
import com.alibaba.rocketmq.common.sysflag.MessageSysFlag;
import com.alibaba.rocketmq.common.sysflag.TopicSysFlag;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import com.alibaba.rocketmq.remoting.exception.RemotingCommandException;
import com.alibaba.rocketmq.remoting.netty.NettyRequestProcessor;
import com.alibaba.rocketmq.remoting.protocol.RemotingCommand;
import com.alibaba.rocketmq.store.MessageExtBrokerInner;
import com.alibaba.rocketmq.store.PutMessageResult;
import com.alibaba.rocketmq.store.config.StorePathConfigHelper;
import com.alibaba.rocketmq.store.stats.BrokerStatsManager;
import io.netty.channel.ChannelHandlerContext;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * @author shijia.wxr
 */
public class SendMessageProcessor extends AbstractSendMessageProcessor implements NettyRequestProcessor {

    public SendMessageProcessor(final BrokerController brokerController) {
        super(brokerController);
    }


    @Override
    public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws RemotingCommandException {
        SendMessageContext mqtraceContext = null;
        switch (request.getCode()) {
            /**
             * 消费方发送回来的消息，单独处理
             */
            case RequestCode.CONSUMER_SEND_MSG_BACK:
                return this.consumerSendMsgBack(ctx, request);
            default:

                SendMessageRequestHeader requestHeader = parseRequestHeader(request);
                if (requestHeader == null) {
                    return null;
                }

                mqtraceContext = buildMsgContext(ctx, requestHeader);
                this.executeSendMessageHookBefore(ctx, request, mqtraceContext);

                /**
                 * 发送消息
                 */
                final RemotingCommand response = this.sendMessage(ctx, request, mqtraceContext, requestHeader);

                this.executeSendMessageHookAfter(response, mqtraceContext);
                return response;
        }
    }


    private RemotingCommand consumerSendMsgBack(final ChannelHandlerContext ctx, final RemotingCommand request)
            throws RemotingCommandException {
        final RemotingCommand response = RemotingCommand.createResponseCommand(null);
        final ConsumerSendMsgBackRequestHeader requestHeader =
                (ConsumerSendMsgBackRequestHeader) request.decodeCommandCustomHeader(ConsumerSendMsgBackRequestHeader.class);

        if (this.hasConsumeMessageHook() && !UtilAll.isBlank(requestHeader.getOriginMsgId())) {
            ConsumeMessageContext context = new ConsumeMessageContext();
            context.setConsumerGroup(requestHeader.getGroup());
            context.setTopic(requestHeader.getOriginTopic());
            context.setClientHost(RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
            context.setSuccess(false);
            context.setStatus(ConsumeConcurrentlyStatus.RECONSUME_LATER.toString());

            Map<String, Long> messageIds = new HashMap<String, Long>();
            messageIds.put(requestHeader.getOriginMsgId(), requestHeader.getOffset());
            context.setMessageIds(messageIds);
            this.executeConsumeMessageHookAfter(context);
        }

        SubscriptionGroupConfig subscriptionGroupConfig =
                this.brokerController.getSubscriptionGroupManager().findSubscriptionGroupConfig(requestHeader.getGroup());
        if (null == subscriptionGroupConfig) {
            response.setCode(ResponseCode.SUBSCRIPTION_GROUP_NOT_EXIST);
            response.setRemark("subscription group not exist, " + requestHeader.getGroup() + " "
                    + FAQUrl.suggestTodo(FAQUrl.SUBSCRIPTION_GROUP_NOT_EXIST));
            return response;
        }

        if (!PermName.isWriteable(this.brokerController.getBrokerConfig().getBrokerPermission())) {
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark("the broker[" + this.brokerController.getBrokerConfig().getBrokerIP1() + "] sending message is forbidden");
            return response;
        }

        if (subscriptionGroupConfig.getRetryQueueNums() <= 0) {
            response.setCode(ResponseCode.SUCCESS);
            response.setRemark(null);
            return response;
        }

        String newTopic = MixAll.getRetryTopic(requestHeader.getGroup());
        int queueIdInt = Math.abs(this.random.nextInt() % 99999999) % subscriptionGroupConfig.getRetryQueueNums();

        int topicSysFlag = 0;
        if (requestHeader.isUnitMode()) {
            topicSysFlag = TopicSysFlag.buildSysFlag(false, true);
        }

        TopicConfig topicConfig = this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(//
                newTopic,//
                subscriptionGroupConfig.getRetryQueueNums(), //
                PermName.PERM_WRITE | PermName.PERM_READ, topicSysFlag);
        if (null == topicConfig) {
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark("topic[" + newTopic + "] not exist");
            return response;
        }

        if (!PermName.isWriteable(topicConfig.getPerm())) {
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark(String.format("the topic[%s] sending message is forbidden", newTopic));
            return response;
        }

        MessageExt msgExt = this.brokerController.getMessageStore().lookMessageByOffset(requestHeader.getOffset());
        if (null == msgExt) {
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark("look message by offset failed, " + requestHeader.getOffset());
            return response;
        }

        final String retryTopic = msgExt.getProperty(MessageConst.PROPERTY_RETRY_TOPIC);
        if (null == retryTopic) {
            MessageAccessor.putProperty(msgExt, MessageConst.PROPERTY_RETRY_TOPIC, msgExt.getTopic());
        }
        msgExt.setWaitStoreMsgOK(false);

        int delayLevel = requestHeader.getDelayLevel();

        if (msgExt.getReconsumeTimes() >= subscriptionGroupConfig.getRetryMaxTimes()//
                || delayLevel < 0) {
            newTopic = MixAll.getDLQTopic(requestHeader.getGroup());
            queueIdInt = Math.abs(this.random.nextInt() % 99999999) % DLQ_NUMS_PER_GROUP;

            topicConfig = this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(newTopic, //
                    DLQ_NUMS_PER_GROUP,
                    PermName.PERM_WRITE, 0
            );
            if (null == topicConfig) {
                response.setCode(ResponseCode.SYSTEM_ERROR);
                response.setRemark("topic[" + newTopic + "] not exist");
                return response;
            }
        } else {
            if (0 == delayLevel) {
                delayLevel = 3 + msgExt.getReconsumeTimes();
            }

            msgExt.setDelayTimeLevel(delayLevel);
        }

        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(newTopic);
        msgInner.setBody(msgExt.getBody());
        msgInner.setFlag(msgExt.getFlag());
        MessageAccessor.setProperties(msgInner, msgExt.getProperties());
        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));
        msgInner.setTagsCode(MessageExtBrokerInner.tagsString2tagsCode(null, msgExt.getTags()));

        msgInner.setQueueId(queueIdInt);
        msgInner.setSysFlag(msgExt.getSysFlag());
        msgInner.setBornTimestamp(msgExt.getBornTimestamp());
        msgInner.setBornHost(msgExt.getBornHost());
        msgInner.setStoreHost(this.getStoreHost());
        msgInner.setReconsumeTimes(msgExt.getReconsumeTimes() + 1);

        String originMsgId = MessageAccessor.getOriginMessageId(msgExt);
        MessageAccessor.setOriginMessageId(msgInner, UtilAll.isBlank(originMsgId) ? msgExt.getMsgId() : originMsgId);

        PutMessageResult putMessageResult = this.brokerController.getMessageStore().putMessage(msgInner);
        if (putMessageResult != null) {
            switch (putMessageResult.getPutMessageStatus()) {
                case PUT_OK:
                    String backTopic = msgExt.getTopic();
                    String correctTopic = msgExt.getProperty(MessageConst.PROPERTY_RETRY_TOPIC);
                    if (correctTopic != null) {
                        backTopic = correctTopic;
                    }

                    if (!this.brokerController.getBrokerConfig().isHighSpeedMode()) {
                        this.brokerController.getBrokerStatsManager().incSendBackNums(requestHeader.getGroup(), backTopic);

                        // For commercial
                        int incValue =
                                (int) Math.ceil(putMessageResult.getAppendMessageResult().getWroteBytes() / BrokerStatsManager.SIZE_PER_COUNT);
                        this.brokerController.getBrokerStatsManager().incCommercialGroupSndBckTimes(requestHeader.getGroup(), backTopic,
                                BrokerStatsManager.StatsType.SEND_BACK_SUCCESS.toString(), incValue);

                        this.brokerController.getBrokerStatsManager().incCommercialGroupSndBckSize(requestHeader.getGroup(), backTopic,
                                BrokerStatsManager.StatsType.SEND_BACK_SUCCESS.toString(),
                                putMessageResult.getAppendMessageResult().getWroteBytes());
                    }

                    response.setCode(ResponseCode.SUCCESS);
                    response.setRemark(null);

                    return response;
                default:
                    // For commercial
                    this.brokerController.getBrokerStatsManager().incCommercialGroupSndBckTimes(requestHeader.getGroup(), msgExt.getTopic(),
                            BrokerStatsManager.StatsType.SEND_BACK_FAILURE.toString(), 1);
                    break;
            }

            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark(putMessageResult.getPutMessageStatus().name());
            return response;
        }

        response.setCode(ResponseCode.SYSTEM_ERROR);
        response.setRemark("putMessageResult is null");
        return response;
    }


    private String diskUtil() {
        String storePathPhysic = this.brokerController.getMessageStoreConfig().getStorePathCommitLog();
        double physicRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathPhysic);

        String storePathLogis =
                StorePathConfigHelper.getStorePathConsumeQueue(this.brokerController.getMessageStoreConfig().getStorePathRootDir());
        double logisRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathLogis);

        String storePathIndex =
                StorePathConfigHelper.getStorePathIndex(this.brokerController.getMessageStoreConfig().getStorePathRootDir());
        double indexRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathIndex);

        return String.format("CL: %5.2f CQ: %5.2f INDEX: %5.2f", physicRatio, logisRatio, indexRatio);
    }


    private RemotingCommand sendMessage(final ChannelHandlerContext ctx, //
                                        final RemotingCommand request,//
                                        final SendMessageContext mqtraceContext,//
                                        final SendMessageRequestHeader requestHeader) throws RemotingCommandException {

        /**
         * 构造响应
         */
        final RemotingCommand response = RemotingCommand.createResponseCommand(SendMessageResponseHeader.class);
        final SendMessageResponseHeader responseHeader = (SendMessageResponseHeader) response.readCustomHeader();

        response.setOpaque(request.getOpaque());

        if (log.isDebugEnabled()) {
            log.debug("receive SendMessage request command, " + request);
        }

        response.setCode(-1);

        /**
         * 消息合法性检查
         * 如果没有TopicConfig，则创建TopicConfig
         */
        super.msgCheck(ctx, requestHeader, response);

        /**
         * 如果校验出问题，直接返回
         */
        if (response.getCode() != -1) {
            return response;
        }


        /**
         * 存储消息
         */

        final byte[] body = request.getBody();

        /**
         * 队列ID
         */
        int queueIdInt = requestHeader.getQueueId();
        /**
         * 获取TopicConfig
         */
        TopicConfig topicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(requestHeader.getTopic());
        if (queueIdInt < 0) {
            queueIdInt = Math.abs(this.random.nextInt() % 99999999) % topicConfig.getWriteQueueNums();
        }

        int sysFlag = requestHeader.getSysFlag();
        /**
         * Topic是否支持多标签过滤
         */
        if (TopicFilterType.MULTI_TAG == topicConfig.getTopicFilterType()) {
            sysFlag |= MessageSysFlag.MultiTagsFlag;
        }

        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(requestHeader.getTopic());
        msgInner.setBody(body);
        msgInner.setFlag(requestHeader.getFlag());

        /**
         * 设置消息属性
         */
        MessageAccessor.setProperties(msgInner, MessageDecoder.string2messageProperties(requestHeader.getProperties()));
        msgInner.setPropertiesString(requestHeader.getProperties());
        /**
         * 设置消息的Tag的HashCode
         */
        msgInner.setTagsCode(MessageExtBrokerInner.tagsString2tagsCode(topicConfig.getTopicFilterType(), msgInner.getTags()));

        msgInner.setQueueId(queueIdInt);
        msgInner.setSysFlag(sysFlag);
        msgInner.setBornTimestamp(requestHeader.getBornTimestamp());

        /**
         * 生产者所在机器
         */
        msgInner.setBornHost(ctx.channel().remoteAddress());
        /**
         * 消息存储机器
         */
        msgInner.setStoreHost(this.getStoreHost());
        /**
         * 重复消费次数
         */
        msgInner.setReconsumeTimes(requestHeader.getReconsumeTimes() == null ? 0 : requestHeader.getReconsumeTimes());

        /**
         * 当前机器是否拒绝事务消息
         */
        if (this.brokerController.getBrokerConfig().isRejectTransactionMessage()) {
            String traFlag = msgInner.getProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED);
            if (traFlag != null) {
                response.setCode(ResponseCode.NO_PERMISSION);
                response.setRemark("the broker[" + this.brokerController.getBrokerConfig().getBrokerIP1()
                        + "] sending transaction message is forbidden");
                return response;
            }
        }

        /**
         * 消息存储
         */
        PutMessageResult putMessageResult = this.brokerController.getMessageStore().putMessage(msgInner);

        /**
         * 处理消息存储结果
         */
        if (putMessageResult != null) {
            boolean sendOK = false;

            /**
             * 设置响应Code
             */
            switch (putMessageResult.getPutMessageStatus()) {
                // Success
                case PUT_OK:
                    sendOK = true;
                    response.setCode(ResponseCode.SUCCESS);
                    break;
                case FLUSH_DISK_TIMEOUT:
                    response.setCode(ResponseCode.FLUSH_DISK_TIMEOUT);
                    sendOK = true;
                    break;
                case FLUSH_SLAVE_TIMEOUT:
                    response.setCode(ResponseCode.FLUSH_SLAVE_TIMEOUT);
                    sendOK = true;
                    break;
                case SLAVE_NOT_AVAILABLE:
                    response.setCode(ResponseCode.SLAVE_NOT_AVAILABLE);
                    sendOK = true;
                    break;

                // Failed
                case CREATE_MAPEDFILE_FAILED:
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("create maped file failed, please make sure OS and JDK both 64bit.");
                    break;
                case MESSAGE_ILLEGAL:
                case PROPERTIES_SIZE_EXCEEDED:
                    response.setCode(ResponseCode.MESSAGE_ILLEGAL);
                    response
                            .setRemark("the message is illegal, maybe msg body or properties length not matched. msg body length limit 128k, msg properties length limit 32k.");
                    break;
                case SERVICE_NOT_AVAILABLE:
                    response.setCode(ResponseCode.SERVICE_NOT_AVAILABLE);
                    response.setRemark("service not available now, maybe disk full, " + diskUtil()
                            + ", maybe your broker machine memory too small.");
                    break;
                case UNKNOWN_ERROR:
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("UNKNOWN_ERROR");
                    break;
                default:
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("UNKNOWN_ERROR DEFAULT");
                    break;
            }

            /**
             * 如果消息存储成功
             */
            if (sendOK) {
                /**
                 * 更新统计信息
                 */
                this.brokerController.getBrokerStatsManager().incTopicPutNums(msgInner.getTopic());
                this.brokerController.getBrokerStatsManager().incTopicPutSize(msgInner.getTopic(), putMessageResult.getAppendMessageResult().getWroteBytes());
                this.brokerController.getBrokerStatsManager().incBrokerPutNums();

                /**
                 * 如果Broker不是高速模式
                 */
                if (!this.brokerController.getBrokerConfig().isHighSpeedMode()) {
                    // For commercial
                    int incValue = (int) Math.ceil(putMessageResult.getAppendMessageResult().getWroteBytes() / BrokerStatsManager.SIZE_PER_COUNT);
                    this.brokerController.getBrokerStatsManager().incCommercialTopicSendTimes(requestHeader.getProducerGroup(),
                            msgInner.getTopic(), BrokerStatsManager.StatsType.SEND_SUCCESS.toString(), incValue);

                    this.brokerController.getBrokerStatsManager().incCommercialTopicSendSize(requestHeader.getProducerGroup(),
                            msgInner.getTopic(), BrokerStatsManager.StatsType.SEND_SUCCESS.toString(),
                            putMessageResult.getAppendMessageResult().getWroteBytes());
                }

                /**
                 * 设置响应数据
                 */

                response.setRemark(null);

                /**
                 * 消息ID
                 */
                responseHeader.setMsgId(putMessageResult.getAppendMessageResult().getMsgId());
                /**
                 * 队列ID
                 */
                responseHeader.setQueueId(queueIdInt);
                /**
                 * 队列偏移量，逻辑偏移量
                 */
                responseHeader.setQueueOffset(putMessageResult.getAppendMessageResult().getLogicsOffset());

                /**
                 * 发送响应
                 */
                doResponse(ctx, request, response);

                /**
                 * 执行回调
                 */
                if (hasSendMessageHook()) {
                    mqtraceContext.setMsgId(responseHeader.getMsgId());
                    mqtraceContext.setQueueId(responseHeader.getQueueId());
                    mqtraceContext.setQueueOffset(responseHeader.getQueueOffset());
                }
                return null;
            } else {
                /**
                 * 发送失败，也进行统计
                 */
                // For commercial
                this.brokerController.getBrokerStatsManager().incCommercialTopicSendTimes(requestHeader.getProducerGroup(),
                        msgInner.getTopic(), BrokerStatsManager.StatsType.SEND_FAILURE.toString(), 1);
            }
        } else {
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark("store putMessage return null");
        }

        return response;
    }


    public SocketAddress getStoreHost() {
        return storeHost;
    }

    private List<SendMessageHook> sendMessageHookList;

    public boolean hasSendMessageHook() {
        return sendMessageHookList != null && !this.sendMessageHookList.isEmpty();
    }


    public void registerSendMessageHook(List<SendMessageHook> sendMessageHookList) {
        this.sendMessageHookList = sendMessageHookList;
    }

    private List<ConsumeMessageHook> consumeMessageHookList;


    public boolean hasConsumeMessageHook() {
        return consumeMessageHookList != null && !this.consumeMessageHookList.isEmpty();
    }


    public void registerConsumeMessageHook(List<ConsumeMessageHook> consumeMessageHookList) {
        this.consumeMessageHookList = consumeMessageHookList;
    }


    public void executeConsumeMessageHookAfter(final ConsumeMessageContext context) {
        if (hasConsumeMessageHook()) {
            for (ConsumeMessageHook hook : this.consumeMessageHookList) {
                try {
                    hook.consumeMessageAfter(context);
                } catch (Throwable e) {
                }
            }
        }
    }
}
