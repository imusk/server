/*
 * Copyright (c) 2012-2017 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.moquette.spi.impl;

import cn.wildfirechat.pojos.OutputMessageData;
import cn.wildfirechat.proto.ProtoConstants;
import cn.wildfirechat.proto.WFCMessage;
import cn.wildfirechat.push.PushServer;
import com.google.gson.Gson;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.util.StringUtil;
import cn.wildfirechat.pojos.OutputNotifyChannelSubscribeStatus;
import cn.wildfirechat.pojos.SendMessageData;
import com.xiaoleilu.hutool.system.UserInfo;
import com.xiaoleilu.loServer.model.FriendData;
import io.moquette.persistence.*;
import io.moquette.persistence.MemorySessionStore.Session;
import io.moquette.server.ConnectionDescriptorStore;
import io.moquette.spi.IMessagesStore;
import io.moquette.spi.ISessionsStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.*;
import win.liyufan.im.HttpUtils;
import win.liyufan.im.IMTopic;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import win.liyufan.im.Utility;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

import static cn.wildfirechat.proto.ProtoConstants.PersistFlag.Transparent;

public class MessagesPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MessagesPublisher.class);
    private final ConnectionDescriptorStore connectionDescriptors;
    private final ISessionsStore m_sessionsStore;
    private final IMessagesStore m_messagesStore;
    private final PersistentQueueMessageSender messageSender;
    private ConcurrentHashMap<UserClientEntry, Long> chatRoomHeaders = new ConcurrentHashMap<>();
    private ExecutorService chatroomScheduler = Executors.newFixedThreadPool(1);
    private boolean schedulerStarted = false;
    private static ExecutorService executorCallback = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public void startChatroomScheduler() {
        schedulerStarted = true;
        chatroomScheduler.execute(() -> {
            while (schedulerStarted) {
                try {
                    if (chatRoomHeaders.size() < 100) {
                        Thread.sleep(500);
                    } else if(chatRoomHeaders.size() < 500) {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Utility.printExecption(LOG, e);
                }

                chatRoomHeaders.forEach(100, (s, aLong) -> {
                    chatRoomHeaders.remove(s, aLong);
                    publish2ChatroomReceivers(s.userId, s.clientId, aLong);
                });
            }
        });
    }

    public void stopChatroomScheduler() {
        schedulerStarted = false;
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
            Utility.printExecption(LOG, e);
        }
        chatroomScheduler.shutdown();
    }

    public MessagesPublisher(ConnectionDescriptorStore connectionDescriptors, ISessionsStore sessionsStore,
                             PersistentQueueMessageSender messageSender, HazelcastInstance hz, IMessagesStore messagesStore) {
        this.connectionDescriptors = connectionDescriptors;
        this.m_sessionsStore = sessionsStore;
        this.messageSender = messageSender;
        this.m_messagesStore = messagesStore;
        this.startChatroomScheduler();
    }

    static MqttPublishMessage notRetainedPublish(String topic, MqttQoS qos, ByteBuf message) {
        return notRetainedPublishWithMessageId(topic, qos, message, 0);
    }

    private static MqttPublishMessage notRetainedPublishWithMessageId(String topic, MqttQoS qos, ByteBuf message,
            int messageId) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, qos, false, 0);
        MqttPublishVariableHeader varHeader = new MqttPublishVariableHeader(topic, messageId);
        return new MqttPublishMessage(fixedHeader, varHeader, message);
    }

    private void publish2ChatroomReceivers(String user, String clientId, long messageHead) {
        publish2ChatroomReceiversDirectly(user, clientId, messageHead);
    }

    public void publish2ChatroomReceiversDirectly(String user, String clientId, long messageHead) {
        try {
            Session session = m_sessionsStore.getSession(clientId);

            if (session != null) {
                LOG.warn("session for {} not exit", clientId);
                return;
            }
            if(!session.getUsername().equals(user)) {
                LOG.warn("session {} user is not {}", clientId, user);
                return;
            }
            if (!this.connectionDescriptors.isConnected(clientId)) {
                LOG.warn("session {} not connected", clientId);
                return;
            }

            WFCMessage.NotifyMessage notifyMessage = WFCMessage.NotifyMessage
                .newBuilder()
                .setType(ProtoConstants.PullType.Pull_ChatRoom)
                .setHead(messageHead)
                .build();

            ByteBuf payload = Unpooled.buffer();
            byte[] byteData = notifyMessage.toByteArray();
            payload.ensureWritable(byteData.length).writeBytes(byteData);
            MqttPublishMessage publishMsg;
            publishMsg = notRetainedPublish(IMTopic.NotifyMessageTopic, MqttQoS.AT_MOST_ONCE, payload);

            boolean result = !this.messageSender.sendPublish(session.getClientSession(), publishMsg);
            if (!result) {
                LOG.warn("send publish to {} failure", clientId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Utility.printExecption(LOG, e);
        }
    }

    private void publish2Receivers(String sender, int conversationType, String target, int line, long messageHead, Collection<String> receivers, String pushContent, String pushData, String exceptClientId, int pullType, int messageContentType, long serverTime, int mentionType, List<String> mentionTargets, int persistFlag) {
        if (persistFlag == Transparent) {
            publishTransparentMessage2Receivers(messageHead, receivers, pullType);
            return;
        }

        WFCMessage.Message message = null;
        for (String user : receivers) {
            if (!user.equals(sender)) {
                WFCMessage.User userInfo = m_messagesStore.getUserInfo(user);
                // 判断接收对象是不是机器人
                if (userInfo != null && userInfo.getType() == ProtoConstants.UserType.UserType_Robot) {
                    WFCMessage.Robot robot = m_messagesStore.getRobot(user);
                    // 如果该机器人配置了消息回调地址，则通过Http的方式转发该消息
                    if (robot != null && !StringUtil.isNullOrEmpty(robot.getCallback())) {
                        if (message == null) {
                            message = m_messagesStore.getMessage(messageHead);
                        }
                        final WFCMessage.Message finalMsg = message;
                        executorCallback.execute(() -> HttpUtils.httpJsonPost(robot.getCallback(), new Gson().toJson(SendMessageData.fromProtoMessage(finalMsg), SendMessageData.class)));
                        continue;
                    }
                }
            }
            long messageSeq;
            // 入库保存给每个接收对象发送的消息
            if (pullType != ProtoConstants.PullType.Pull_ChatRoom) {
                messageSeq = m_messagesStore.insertUserMessages(sender, conversationType, target, line, messageContentType, user, messageHead);
            } else {
                messageSeq = m_messagesStore.insertChatroomMessages(user, line, messageHead);
            }

            // 获取接收对象的Session信息，包含了客户端信息、最后活跃时间，一个用户可能存在多个Session，例如：同时在手机和Web设备登录
            Collection<Session> sessions = m_sessionsStore.sessionForUser(user);
            String senderName = null;
            String targetName = null;
            boolean nameLoaded = false;


            Collection<String> targetClients = null;
            if (pullType == ProtoConstants.PullType.Pull_ChatRoom) {
                targetClients = m_messagesStore.getChatroomMemberClient(user);
            }
            for (Session targetSession : sessions) {
                //超过7天不活跃的用户忽略
                if(System.currentTimeMillis() - targetSession.getLastActiveTime() > 7 * 24 * 60 * 60 * 1000) {
                    continue;
                }

                if (exceptClientId != null && exceptClientId.equals(targetSession.getClientSession().clientID)) {
                    continue;
                }

                if (targetSession.getClientID() == null) {
                    continue;
                }

                if (pullType == ProtoConstants.PullType.Pull_ChatRoom && !targetClients.contains(targetSession.getClientID())) {
                    continue;
                }

                if (pullType == ProtoConstants.PullType.Pull_ChatRoom) {
                    if (exceptClientId != null && exceptClientId.equals(targetSession.getClientID())) {
                        // 刷新
                        targetSession.refreshLastChatroomActiveTime();
                    }

                    if (!m_messagesStore.checkChatroomParticipantIdelTime(targetSession)) {
                        // 这样应该是顺便清理未活跃用户，具体未深入查看
                        m_messagesStore.handleQuitChatroom(user, targetSession.getClientID(), target);
                        continue;
                    }
                }

                // 是否静默推送 (勿扰)
                boolean isSlient;
                if (pullType == ProtoConstants.PullType.Pull_ChatRoom) {
                    isSlient = true;
                } else {
                    isSlient = false;

                    if (!user.equals(sender)) {
                        WFCMessage.Conversation conversation;
                        if (conversationType == ProtoConstants.ConversationType.ConversationType_Private) {
                            conversation = WFCMessage.Conversation.newBuilder().setType(conversationType).setLine(line).setTarget(sender).build();
                        } else {
                            conversation = WFCMessage.Conversation.newBuilder().setType(conversationType).setLine(line).setTarget(target).build();
                        }

                        // 是否设置消息勿扰
                        if (m_messagesStore.getUserConversationSlient(user, conversation)) {
                            LOG.info("The conversation {}-{}-{} is slient", conversation.getType(), conversation.getTarget(), conversation.getLine());
                            isSlient = true;
                        }

                        // 是否设置全局勿扰
                        if (m_messagesStore.getUserGlobalSlient(user)) {
                            LOG.info("The user {} is global sliented", user);
                            isSlient = true;
                        }
                    }

                    if (!StringUtil.isNullOrEmpty(pushContent) || messageContentType == 400) {
                        if (!isSlient && (persistFlag & 0x02) > 0) {
                            targetSession.setUnReceivedMsgs(targetSession.getUnReceivedMsgs() + 1);
                        }
                    }

                    if (isSlient) {
                        if (mentionType == 2 || (mentionType == 1 && mentionTargets.contains(user))) {
                            isSlient = false;
                        }
                    }
                }

                // 是否需要推送的用户
                boolean needPush = !user.equals(sender);

                // 判断当前接收对象(用户)是否处于活跃状态(在线)
                boolean targetIsActive = this.connectionDescriptors.isConnected(targetSession.getClientSession().clientID);
                if (targetIsActive) {
                    WFCMessage.NotifyMessage notifyMessage = WFCMessage.NotifyMessage
                        .newBuilder()
                        .setType(pullType)
                        .setHead(messageSeq)
                        .build();

                    ByteBuf payload = Unpooled.buffer();
                    byte[] byteData = notifyMessage.toByteArray();
                    payload.ensureWritable(byteData.length).writeBytes(byteData);
                    MqttPublishMessage publishMsg;
                    publishMsg = notRetainedPublish(IMTopic.NotifyMessageTopic, MqttQoS.AT_MOST_ONCE, payload);

                    // 通过MQTT给指定的接收对象发布 MN 的消息
                    boolean sent = this.messageSender.sendPublish(targetSession.getClientSession(), publishMsg);
                    // 判断消息是否发送 成功，这边设置 needPush 变量的原因，估计是可能用户刚好下线，系统还认为是在线的，就给ClientSession推送消息，但是用户已经接收不到消息了，所以还需要通过 needPush 变量再推送 客户端的通知消息
                    if (sent) {
                        needPush = false;
                    }
                } else {
                    LOG.info("the target {} of user {} is not active", targetSession.getClientID(), targetSession.getUsername());
                }

                if (needPush && pullType != ProtoConstants.PullType.Pull_ChatRoom) {
                    int curMentionType = 0;
                    if (mentionType == 2) {
                        curMentionType = 2;
                        isSlient = false;
                    } else if (mentionType == 1){
                        if (mentionTargets != null && mentionTargets.contains(user)) {
                            curMentionType = 1;
                            isSlient = false;
                        }
                    }

                    if ((StringUtil.isNullOrEmpty(pushContent) && messageContentType != 402 && messageContentType != 400)) {
                        LOG.info("push content is empty and contenttype is {}", messageContentType);
                        continue;
                    }

                    if (StringUtil.isNullOrEmpty(targetSession.getDeviceToken())) {
                        LOG.info("device token not exist");
                        continue;
                    }

                    if (isSlient) {
                        LOG.info("Slient of user or conversation");
                        continue;
                    }

                    // 获取用户是否隐藏 通知栏详情
                    boolean isHiddenDetail = m_messagesStore.getUserPushHiddenDetail(user);

                    if(!nameLoaded) {
                        senderName = getUserDisplayName(sender, conversationType == ProtoConstants.ConversationType.ConversationType_Group ? target : null);
                        targetName = getTargetName(target, conversationType);
                        nameLoaded = true;
                    }

                    String name = senderName;
                    if (!sender.equals(user)) {
                        FriendData fd = m_messagesStore.getFriendData(user, sender);
                        if (fd != null && !StringUtil.isNullOrEmpty(fd.getAlias())) {
                            name = fd.getAlias();
                        }
                    }
                    // 请求移动设备消息推送服务接口
                    this.messageSender.sendPush(sender, conversationType, target, line, messageHead, targetSession.getClientID(), pushContent, pushData, messageContentType, serverTime, name, targetName, targetSession.getUnReceivedMsgs(), curMentionType, isHiddenDetail, targetSession.getLanguage());
                }

            }
        }
    }

    private void publishTransparentMessage2Receivers(long messageHead, Collection<String> receivers, int pullType) {
        WFCMessage.Message message = m_messagesStore.getMessage(messageHead);

        if (message != null) {
            for (String user : receivers) {
                Collection<Session> sessions = m_sessionsStore.sessionForUser(user);

                for (Session targetSession : sessions) {
                    if(System.currentTimeMillis() - targetSession.getLastActiveTime() > 60 * 60 * 1000) {
                        continue;
                    }

                    if (targetSession.getClientID() == null) {
                        continue;
                    }

                    boolean targetIsActive = this.connectionDescriptors.isConnected(targetSession.getClientSession().clientID);
                    if (targetIsActive) {
                        ByteBuf payload = Unpooled.buffer();
                        byte[] byteData = message.toByteArray();
                        payload.ensureWritable(byteData.length).writeBytes(byteData);
                        MqttPublishMessage publishMsg;
                        publishMsg = notRetainedPublish(IMTopic.SendMessageTopic, MqttQoS.AT_MOST_ONCE, payload);

                        this.messageSender.sendPublish(targetSession.getClientSession(), publishMsg);
                    } else {
                        LOG.info("the target {} of user {} is not active", targetSession.getClientID(), targetSession.getUsername());
                    }
                }
            }
        }
    }

    String getUserDisplayName(String userId, String groupId) {
        WFCMessage.User user = m_messagesStore.getUserInfo(userId);
        String userName = null;
        if(user != null) {
            userName = user.getDisplayName();
        }
        if (!StringUtil.isNullOrEmpty(groupId)) {
            WFCMessage.GroupMember member = m_messagesStore.getGroupMember(groupId, userId);
            if (member != null && !StringUtil.isNullOrEmpty(member.getAlias())) {
                userName = member.getAlias();
            }
        }
        return userName;
    }

    String getTargetName(String targetId, int cnvType) {
        if (cnvType == ProtoConstants.ConversationType.ConversationType_Private) {
            return getUserDisplayName(targetId, null);
        } else if(cnvType == ProtoConstants.ConversationType.ConversationType_Group) {
            WFCMessage.GroupInfo group = m_messagesStore.getGroupInfo(targetId);
            if(group != null) {
                return group.getName();
            }
        } else if(cnvType == ProtoConstants.ConversationType.ConversationType_Channel) {
            WFCMessage.ChannelInfo channelInfo = m_messagesStore.getChannelInfo(targetId);
            if (channelInfo != null) {
                return channelInfo.getName();
            }
        }
        return null;
    }

    public void publishNotification(String topic, String receiver, long head) {
        publishNotification(topic, receiver, head, null, null);
    }

    public void publishNotification(String topic, String receiver, long head, String fromUser, String pushContent) {
        publishNotificationLocal(topic, receiver, head, fromUser, pushContent);
    }

    void publishNotificationLocal(String topic, String receiver, long head, String fromUser, String pushContent) {
        Collection<Session> sessions = m_sessionsStore.sessionForUser(receiver);
        String fromUserName = null;
        for (Session targetSession : sessions) {
            boolean needPush = !StringUtil.isNullOrEmpty(pushContent);
            boolean targetIsActive = this.connectionDescriptors.isConnected(targetSession.getClientSession().clientID);
            if (targetIsActive) {
                ByteBuf payload = Unpooled.buffer();
                payload.writeLong(head);
                MqttPublishMessage publishMsg;
                publishMsg = notRetainedPublish(topic, MqttQoS.AT_MOST_ONCE, payload);

                boolean result = this.messageSender.sendPublish(targetSession.getClientSession(), publishMsg);
                if (!result) {
                    LOG.warn("Publish friend request failure");
                } else {
                    needPush = false;
                }
            }
            if (needPush) {
                if (fromUserName == null) {
                    WFCMessage.User userInfo = m_messagesStore.getUserInfo(fromUser);
                    if (userInfo == null) {
                        fromUserName = "";
                    } else {
                        fromUserName = userInfo.getDisplayName();
                    }
                }

                if (IMTopic.NotifyFriendRequestTopic.equals(topic)) {
                    messageSender.sendPush(fromUser, receiver, targetSession.getClientID(), pushContent, PushServer.PushMessageType.PUSH_MESSAGE_TYPE_FRIEND_REQUEST, System.currentTimeMillis(), fromUserName, targetSession.getUnReceivedMsgs() + 1, targetSession.getLanguage());
                }
            }
        }
    }

    public void updateChatroomMembersQueue(String chatroomId, int line, long messageId) {
        final long messageSeq = m_messagesStore.insertChatroomMessages(chatroomId, line, messageId);
        Collection<UserClientEntry> members = m_messagesStore.getChatroomMembers(chatroomId);
        for (UserClientEntry member : members
             ) {
            chatRoomHeaders.compute(member, new BiFunction<UserClientEntry, Long, Long>() {
                @Override
                public Long apply(UserClientEntry s, Long aLong) {
                    if (aLong == null) {
                        return messageSeq;
                    }
                    if (messageSeq > aLong) {
                        return messageSeq;
                    }
                    return aLong;
                }
            });
        }
    }

    public void publishRecall2ReceiversLocal(long messageUid, String operatorId, Collection<String> receivers, String exceptClientId) {
        for (String user : receivers) {


            Collection<Session> sessions = m_sessionsStore.sessionForUser(user);
            for (Session targetSession : sessions) {
                if (exceptClientId != null && exceptClientId.equals(targetSession.getClientSession().clientID)) {
                    continue;
                }

                if (targetSession.getClientID() == null) {
                    continue;
                }

                boolean targetIsActive = this.connectionDescriptors.isConnected(targetSession.getClientSession().clientID);
                if (targetIsActive) {
                    WFCMessage.NotifyRecallMessage notifyMessage = WFCMessage.NotifyRecallMessage
                        .newBuilder()
                        .setFromUser(operatorId)
                        .setId(messageUid)
                        .build();

                    ByteBuf payload = Unpooled.buffer();
                    byte[] byteData = notifyMessage.toByteArray();
                    payload.ensureWritable(byteData.length).writeBytes(byteData);
                    MqttPublishMessage publishMsg;
                    publishMsg = notRetainedPublish(IMTopic.NotifyRecallMessageTopic, MqttQoS.AT_MOST_ONCE, payload);

                    this.messageSender.sendPublish(targetSession.getClientSession(), publishMsg);
                } else {
                    LOG.info("the target {} of user {} is not active", targetSession.getClientID(), targetSession.getUsername());
                }
            }
        }
    }

    public void publishRecall2Receivers(long messageUid, String operatorId, Set<String> receivers, String exceptClientId) {
        publishRecall2ReceiversLocal(messageUid, operatorId, receivers, exceptClientId);
    }

    public void publish2Receivers(WFCMessage.Message message, Set<String> receivers, String exceptClientId, int pullType) {
        if (message.getConversation().getType() == ProtoConstants.ConversationType.ConversationType_Channel) {
            WFCMessage.ChannelInfo channelInfo = m_messagesStore.getChannelInfo(message.getConversation().getTarget());
            if (channelInfo != null && !StringUtil.isNullOrEmpty(channelInfo.getCallback())) {
                executorCallback.execute(() -> HttpUtils.httpJsonPost(channelInfo.getCallback() + "/message", new Gson().toJson(SendMessageData.fromProtoMessage(message), SendMessageData.class)));
            }
        }
        long messageId = message.getMessageId();

        String pushContent = message.getContent().getPushContent();
        if (StringUtil.isNullOrEmpty(pushContent)) {
            int type = message.getContent().getType();
            if (type == ProtoConstants.ContentType.Image) {
                pushContent = "[图片]";
            } else if(type == ProtoConstants.ContentType.Location) {
                pushContent = "[位置]";
            } else if(type == ProtoConstants.ContentType.Text) {
                pushContent = message.getContent().getSearchableContent();
            } else if(type == ProtoConstants.ContentType.Voice) {
                pushContent = "[语音]";
            } else if(type == ProtoConstants.ContentType.Video) {
                pushContent = "[视频]";
            } else if(type == ProtoConstants.ContentType.RichMedia) {
                pushContent = "[图文]";
            } else if(type == ProtoConstants.ContentType.File) {
                pushContent = "[文件]";
            } else if(type == ProtoConstants.ContentType.Sticker) {
                pushContent = "[表情]";
            }
        }

        if (message.getContent().getPersistFlag() == Transparent) {
            pushContent = null;
        }

        publish2Receivers(message.getFromUser(),
                    message.getConversation().getType(), message.getConversation().getTarget(), message.getConversation().getLine(),
                    messageId,
                    receivers,
                    pushContent, message.getContent().getPushData(), exceptClientId, pullType, message.getContent().getType(), message.getServerTimestamp(), message.getContent().getMentionedType(), message.getContent().getMentionedTargetList(), message.getContent().getPersistFlag());

    }

    public void forwardMessage(final WFCMessage.Message message, String forwardUrl) {
        executorCallback.execute(() -> HttpUtils.httpJsonPost(forwardUrl, new Gson().toJson(OutputMessageData.fromProtoMessage(message), OutputMessageData.class)));
    }

    public void notifyChannelListenStatusChanged(WFCMessage.ChannelInfo channelInfo, String user, boolean listen) {
        if (channelInfo == null || StringUtil.isNullOrEmpty(channelInfo.getCallback())) {
            return;
        }
        executorCallback.execute(() -> HttpUtils.httpJsonPost(channelInfo.getCallback() + "/subscribe", new Gson().toJson(new OutputNotifyChannelSubscribeStatus(user, channelInfo.getTargetId(), listen), OutputNotifyChannelSubscribeStatus.class)));
    }
}
