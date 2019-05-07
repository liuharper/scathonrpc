package com.scathon.tech.rpc.client.netty;

import com.scathon.tech.rpc.client.cache.LockCache;
import com.scathon.tech.rpc.client.cache.RpcResponseCache;
import com.scathon.tech.rpc.common.CommonModuleBeanScanFlag;
import com.scathon.tech.rpc.common.proto.RequestMsgEntity;
import com.scathon.tech.rpc.common.proto.ResponseMsgEntity;
import com.scathon.tech.rpc.registry.common.ServiceInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Rpc Client 请求客户端引导类.
 *
 * @ClassName RpcClientBootstrap.
 * @Description TODO.
 * @Author linhd eng:ScathonLin
 * @Date 2019/5/4
 * @Version 1.0
 */
public class RpcClientBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(RpcClientBootstrap.class);

    private static EventLoopGroup group = null;
    private static volatile Bootstrap bootstrap;

    private static final ApplicationContext CONTEXT =
            new AnnotationConfigApplicationContext(CommonModuleBeanScanFlag.class);

    private static final Object LOCK = new Object();
    private static final RpcClientBootstrap INSTANCE = new RpcClientBootstrap();

    public static RpcClientBootstrap getInstance() {
        return INSTANCE;
    }

    private RpcClientBootstrap() {
    }

    public static Bootstrap getBootstrap() {
        if (bootstrap == null) {
            synchronized (LOCK) {
                if (bootstrap == null) {
                    // 先关闭有可能已经打开的group，防止句柄泄露.
                    closeGroupGracefully();
                    // 初始化netty客户端配置.
                    group = new NioEventLoopGroup();
                    bootstrap = new Bootstrap();
                    bootstrap.group(group)
                            .channel(NioSocketChannel.class)
                            .handler(new RpcClientCannnelInitializer())
                            .option(ChannelOption.SO_KEEPALIVE, true);
                }
            }
        }
        return bootstrap;
    }

    public static void closeGroupGracefully() {
        if (group != null) {
            group.shutdownGracefully();
            //optimize garbage collection.
            group = null;
        }
    }

    private static final class RpcClientCannnelInitializer extends ChannelInitializer<SocketChannel> {

        @Override
        protected void initChannel(SocketChannel channel) throws Exception {
            channel.pipeline()
                    .addLast(new ProtobufVarint32FrameDecoder())
                    .addLast(new ProtobufDecoder(ResponseMsgEntity.ResponseMessage.getDefaultInstance()))
                    .addLast(new ProtobufVarint32LengthFieldPrepender())
                    .addLast(new ProtobufEncoder())
                    .addLast(new ClientDataProcessHandler());
        }
    }

    /**
     * 发起远程服务调用.
     *
     * @param reqMsg      请求消息体.
     * @param serviceInfo service信息.
     * @return 响应消息体.
     */
    public ResponseMsgEntity.ResponseMessage callRemoteService(RequestMsgEntity.RequestMessage reqMsg,
                                                               ServiceInfo serviceInfo) {

        String uuid = reqMsg.getRequestUUID();
        EventLoopGroup group = null;
        try {
            group = new NioEventLoopGroup();
            Bootstrap bs = new Bootstrap();
            bs.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new RpcClientCannnelInitializer())
                    .option(ChannelOption.SO_KEEPALIVE, true);
            String[] addressArr = serviceInfo.getServiceAddrList().split(",");
            String[] socketInfo = addressArr[0].split(":");
            String host = socketInfo[0];
            int port = Integer.parseInt(socketInfo[1]);
            ChannelFuture future = bs.connect(host, port);
            LOGGER.info("connect to rpc server endpoint {}:{} successfully! ^_^", host, port);
            // 发起rpc请求.
            LOGGER.info("start calling remote servive,req msg is : {}", reqMsg.toString());

            future.channel().writeAndFlush(reqMsg).sync();

            synchronized (LockCache.get(uuid)) {
                LockCache.get(uuid).wait(2000);
            }

            ResponseMsgEntity.ResponseMessage respMsg = RpcResponseCache.get(uuid);
            if (respMsg != null) {
                future.channel().closeFuture().sync();
            }

            return respMsg;

        } catch (InterruptedException e) {
            LOGGER.error("interrupted exception occured, err msg is : {}", e.getMessage());
        } catch (Exception ex) {
            LOGGER.info("unknown exception found while calling remote service, msg is : {}", ex.getMessage());
        } finally {
            LockCache.remove(uuid);
            RpcResponseCache.remove(uuid);
            if (group != null) {
                group.shutdownGracefully();
            }
        }
        return null;
    }
}