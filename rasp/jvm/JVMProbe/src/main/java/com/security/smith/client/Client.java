package com.security.smith.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.security.smith.client.message.*;
import com.security.smith.common.ProcessHelper;
import com.security.smith.log.SmithLogger;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

interface EventHandler {
    void onReconnect();
    void onMessage(Message message);
}

public class Client implements EventHandler {
    private static final int EVENT_LOOP_THREADS = 1;
    private static final int RECONNECT_SCHEDULE = 60;
    private static final String SOCKET_PATH = "/var/run/smith_agent.sock";
    private static final String MESSAGE_DIRECTORY = "/var/run/elkeid_rasp";

    private Channel channel;
    private final MessageHandler messageHandler;
    private final EpollEventLoopGroup group;

    public Client(MessageHandler messageHandler) {
        // note: linux use epoll, mac use kqueue
        this.messageHandler = messageHandler;
        this.group = new EpollEventLoopGroup(EVENT_LOOP_THREADS, new DefaultThreadFactory(getClass(), true));
    }

    public void start() {
        SmithLogger.logger.info("probe client start");

        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(EpollDomainSocketChannel.class)
                    .handler(new ChannelInitializer<DomainSocketChannel>() {
                        @Override
                        public void initChannel(DomainSocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();

                            p.addLast(new MessageDecoder());
                            p.addLast(new MessageEncoder());
                            p.addLast(new ClientHandlerAdapter(Client.this));
                        }
                    });

            channel = b.connect(new DomainSocketAddress(SOCKET_PATH))
                    .addListener((ChannelFuture f) -> {
                        if (!f.isSuccess()) {
                            f.channel().eventLoop().schedule(this::onReconnect, RECONNECT_SCHEDULE, TimeUnit.SECONDS);
                        }
                    }).sync().channel();

            channel.closeFuture().sync();
        } catch (Exception e) {
            SmithLogger.exception(e);
        }
    }

    public void stop() {
        group.shutdownGracefully();
    }

    public void write(Operate operate, Object object) {
        if (channel == null || !channel.isActive() || !channel.isWritable())
            return;

        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

        Message message = new Message();

        message.setOperate(operate);
        message.setData(objectMapper.valueToTree(object));

        channel.writeAndFlush(message);
    }

    @Override
    public void onReconnect() {
        SmithLogger.logger.info("reconnect");

        readMessage();
        new Thread(this::start).start();
    }

    @Override
    public void onMessage(Message message) {
        switch (message.getOperate()) {
            case EXIT:
                SmithLogger.logger.info("exit");
                break;

            case HEARTBEAT:
                SmithLogger.logger.info("heartbeat");
                break;

            case CONFIG:
                SmithLogger.logger.info("config");
                messageHandler.onConfig(message.getData().get("config").asText());
                break;

            case CONTROL:
                SmithLogger.logger.info("control");
                messageHandler.onControl(message.getData().get("action").asInt());
                break;

            case DETECT:
                SmithLogger.logger.info("detect");
                messageHandler.onDetect();
                break;

            case FILTER: {
                SmithLogger.logger.info("filter");

                ObjectMapper objectMapper = new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

                try {
                    messageHandler.onFilter(
                            objectMapper.treeToValue(
                                    message.getData(),
                                    FilterConfig.class
                            )
                    );
                } catch (JsonProcessingException e) {
                    SmithLogger.exception(e);
                }

                break;
            }

            case BLOCK: {
                SmithLogger.logger.info("block");

                ObjectMapper objectMapper = new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

                try {
                    messageHandler.onBlock(
                            objectMapper.treeToValue(
                                    message.getData(),
                                    BlockConfig.class
                            )
                    );
                } catch (JsonProcessingException e) {
                    SmithLogger.exception(e);
                }

                break;
            }

            case LIMIT: {
                SmithLogger.logger.info("limit");

                ObjectMapper objectMapper = new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

                try {
                    messageHandler.onLimit(
                            objectMapper.treeToValue(
                                    message.getData(),
                                    LimitConfig.class
                            )
                    );
                } catch (JsonProcessingException e) {
                    SmithLogger.exception(e);
                }

                break;
            }

            case PATCH: {
                SmithLogger.logger.info("patch");

                ObjectMapper objectMapper = new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

                try {
                    messageHandler.onPatch(
                            objectMapper.treeToValue(
                                    message.getData(),
                                    PatchConfig.class
                            )
                    );
                } catch (JsonProcessingException e) {
                    SmithLogger.exception(e);
                }

                break;
            }
        }
    }

    private void readMessage() {
        Path path = Paths.get(MESSAGE_DIRECTORY, String.format("%d.json", ProcessHelper.getCurrentPID()));

        if (!Files.exists(path))
            return;

        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

        try {
            for (Message message : objectMapper.readValue(path.toFile(), Message[].class))
                onMessage(message);

            Files.delete(path);
        } catch (IOException e) {
            SmithLogger.exception(e);
        }
    }

    static class ClientHandlerAdapter extends ChannelInboundHandlerAdapter {
        private final EventHandler eventHandler;

        ClientHandlerAdapter(EventHandler eventHandler) {
            this.eventHandler = eventHandler;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            SmithLogger.logger.info("channel inactive");

            ctx.channel().eventLoop().schedule(
                    eventHandler::onReconnect,
                    RECONNECT_SCHEDULE,
                    TimeUnit.SECONDS
            );
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            SmithLogger.logger.info("channel active");
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            eventHandler.onMessage((Message) msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            SmithLogger.exception(cause);
            ctx.close();
        }
    }
}
