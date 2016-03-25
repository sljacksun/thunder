package network.thunder.core.communication.layer.high.channel.establish;

import network.thunder.core.communication.ClientObject;
import network.thunder.core.communication.ServerObject;
import network.thunder.core.communication.layer.ContextFactory;
import network.thunder.core.communication.layer.Message;
import network.thunder.core.communication.layer.MessageExecutor;
import network.thunder.core.communication.layer.high.Channel;
import network.thunder.core.communication.layer.high.channel.ChannelManager;
import network.thunder.core.communication.layer.high.channel.establish.messages.*;
import network.thunder.core.communication.layer.middle.broadcasting.gossip.BroadcastHelper;
import network.thunder.core.communication.layer.middle.broadcasting.types.ChannelStatusObject;
import network.thunder.core.communication.layer.middle.broadcasting.types.PubkeyChannelObject;
import network.thunder.core.communication.processor.ConnectionIntent;
import network.thunder.core.communication.processor.exceptions.LNEstablishException;
import network.thunder.core.database.DBHandler;
import network.thunder.core.etc.Tools;
import network.thunder.core.helper.blockchain.BlockchainHelper;
import network.thunder.core.helper.callback.results.ChannelCreatedResult;
import network.thunder.core.helper.events.LNEventHelper;
import network.thunder.core.helper.wallet.WalletHelper;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.TransactionSignature;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by matsjerratsch on 03/12/2015.
 */
public class LNEstablishProcessorImpl extends LNEstablishProcessor {
    public static final double PERCENTAGE_OF_FUNDS_PER_CHANNEL = 0.1;

    WalletHelper walletHelper;
    LNEstablishMessageFactory messageFactory;
    BroadcastHelper broadcastHelper;
    LNEventHelper eventHelper;
    DBHandler dbHandler;
    ClientObject node;
    ServerObject serverObject;
    BlockchainHelper blockchainHelper;
    ChannelManager channelManager;

    MessageExecutor messageExecutor;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public Channel channel;
    int status = 0;

    public LNEstablishProcessorImpl (ContextFactory contextFactory, DBHandler dbHandler, ClientObject node) {
        this.walletHelper = contextFactory.getWalletHelper();
        this.messageFactory = contextFactory.getLNEstablishMessageFactory();
        this.broadcastHelper = contextFactory.getBroadcastHelper();
        this.eventHelper = contextFactory.getEventHelper();
        this.dbHandler = dbHandler;
        this.node = node;
        this.serverObject = contextFactory.getServerSettings();
        this.blockchainHelper = contextFactory.getBlockchainHelper();
        this.channelManager = contextFactory.getChannelManager();
    }

    @Override
    public void onInboundMessage (Message message) {
        try {
            consumeMessage(message);
        } catch (Exception e) {
            e.printStackTrace();
            messageExecutor.sendMessageUpwards(messageFactory.getFailureMessage(e.getMessage()));
            node.resultCallback.execute(new ChannelCreatedResult(channel));
            throw e;
        }
    }

    @Override
    public boolean consumesInboundMessage (Object object) {
        return object instanceof LNEstablish;
    }

    @Override
    public boolean consumesOutboundMessage (Object object) {
        return false;
    }

    @Override
    public void onLayerActive (MessageExecutor messageExecutor) {
        //TODO check for existing channels, check if we are still waiting for them to gather enough confirmations, ...
        this.messageExecutor = messageExecutor;
        List<Channel> openChannel = dbHandler.getChannel(node.pubKeyClient);
        if (openChannel.size() > 0) {
            messageExecutor.sendNextLayerActive();
        } else {
            if (!node.isServer) {
                if (node.intent == ConnectionIntent.OPEN_CHANNEL) {
                    sendEstablishMessageA();
                }
            }
        }

    }

    private void consumeMessage (Message message) {
        if (message instanceof LNEstablishAMessage) {
            processMessageA(message);
        } else if (message instanceof LNEstablishBMessage) {
            processMessageB(message);
        } else if (message instanceof LNEstablishCMessage) {
            processMessageC(message);
        } else if (message instanceof LNEstablishDMessage) {
            processMessageD(message);
        } else {
            throw new UnsupportedOperationException("Don't know this LNEstablish Message: " + message);
        }
    }

    private void processMessageA (Message message) {
        checkStatus(0);
        LNEstablish m = (LNEstablish) message;
        prepareNewChannel();
        m.saveToChannel(channel);
        sendEstablishMessageB();
    }

    private void processMessageB (Message message) {
        checkStatus(2);
        LNEstablish m = (LNEstablish) message;
        m.saveToChannel(channel);
        sendEstablishMessageC();
    }

    private void processMessageC (Message message) {
        checkStatus(3);
        LNEstablish m = (LNEstablish) message;
        m.saveToChannel(channel);
        channel.verifyEscapeSignatures();
        sendEstablishMessageD();
        onChannelEstablished();
    }

    private void processMessageD (Message message) {
        checkStatus(4);
        LNEstablish m = (LNEstablish) message;
        m.saveToChannel(channel);
        channel.verifyEscapeSignatures();
        onChannelEstablished();
    }

    private void onChannelEstablished () {
        dbHandler.saveChannel(channel);
//        channelManager.onExchangeDone(channel, this::onEnoughConfirmations);
        this.onEnoughConfirmations();
        blockchainHelper.broadcastTransaction(channel.getAnchorTransactionServer());

    }

    private void onEnoughConfirmations () {
        channel.initiateChannelStatus(serverObject.configuration);
        dbHandler.updateChannel(channel);
        startScheduledBroadcasting();
        eventHelper.onChannelOpened(channel);
        messageExecutor.sendNextLayerActive();
    }

    private void sendEstablishMessageA () {
        prepareNewChannel();
        Message message = messageFactory.getEstablishMessageA(channel);
        messageExecutor.sendMessageUpwards(message);
        status = 2;
    }

    private void sendEstablishMessageB () {
        Transaction anchor = channel.getAnchorTransactionServer(walletHelper);
        Message message = messageFactory.getEstablishMessageB(channel, anchor);
        messageExecutor.sendMessageUpwards(message);
        status = 3;
    }

    private void sendEstablishMessageC () {
        Transaction anchor = channel.getAnchorTransactionServer(walletHelper);
        Transaction escape = channel.getEscapeTransactionClient();
        Transaction fastEscape = channel.getFastEscapeTransactionClient();

        TransactionSignature escapeSig = Tools.getSignature(escape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel.getKeyServerA());
        TransactionSignature fastEscapeSig = Tools.getSignature(fastEscape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel
                .getKeyServerA());

        Message message = messageFactory.getEstablishMessageC(anchor, escapeSig, fastEscapeSig);
        messageExecutor.sendMessageUpwards(message);

        status = 4;
    }

    private void sendEstablishMessageD () {
        Transaction escape = channel.getEscapeTransactionClient();
        Transaction fastEscape = channel.getFastEscapeTransactionClient();
        TransactionSignature escapeSig = Tools.getSignature(escape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel.getKeyServerA());
        TransactionSignature fastEscapeSig = Tools.getSignature(fastEscape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel
                .getKeyServerA());

        Message message = messageFactory.getEstablishMessageD(escapeSig, fastEscapeSig);
        messageExecutor.sendMessageUpwards(message);
        status = 5;
    }

    private void prepareNewChannel () {
        channel = new Channel(node.pubKeyClient.getPubKey(), serverObject.pubKeyServer, getAmountForNewChannel());
        status = 1;
    }

    private void broadcastChannelObject () {
        //TODO only broadcast the channel object here and the status object in some other processor
        PubkeyChannelObject channelObject = PubkeyChannelObject.getRandomObject();
        ChannelStatusObject statusObject = new ChannelStatusObject();
        statusObject.pubkeyA = serverObject.pubKeyServer.getPubKey();
        statusObject.pubkeyB = node.pubKeyClient.getPubKey();
        statusObject.timestamp = Tools.currentTime();
        broadcastHelper.broadcastNewObject(channelObject);
        broadcastHelper.broadcastNewObject(statusObject);
    }

    private long getAmountForNewChannel () {
        return (long) (walletHelper.getSpendableAmount() * PERCENTAGE_OF_FUNDS_PER_CHANNEL);
    }

    private void checkStatus (int expected) {
        if (status != expected) {
            throw new LNEstablishException("Status not correct.. Is: " + status + " Expected: " + expected);
        }
    }

    @Override
    public void onLayerClose () {
        scheduler.shutdown();
    }

    private void startScheduledBroadcasting () {
        broadcastChannelObject();
        scheduler.scheduleAtFixedRate((Runnable) () -> broadcastChannelObject(), 1, 1, TimeUnit.HOURS);
    }

}